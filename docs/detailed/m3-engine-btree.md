# M3 · engine-btree 상세 설계 (B+tree)

> 데이터를 고정 크기 페이지로 나누고, 정렬된 트리의 노드 = 페이지로 둔다. 갱신은 해당 페이지를 **제자리에서** 고친다.
> 참고: Database Internals 2~5장, CMU 15-445 (Buffer Pool, B+Tree, Logging & Recovery), SQLite file format, InnoDB doublewrite buffer.

## 1. 이 단계에서 배우려는 것

M1·M2는 쓰기를 항상 파일 끝에 추가했다. M3는 반대 축이다.

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 제자리 갱신은 왜 읽기에 유리한가 | §8 읽기 경로: 트리 높이만큼의 페이지, 대부분 버퍼 풀 적중 |
| 100 B를 고쳐도 왜 4 KiB를 쓰는가 | §10 쓰기 증폭 예상, M4 측정 |
| 페이지 여러 개를 제자리에서 고치다 죽으면 어떻게 되는가 | §9 체크포인트·doublewrite |
| 버퍼 풀은 무엇을 언제 디스크로 내보내는가 | §6 no-steal 정책 |
| split 도중 크래시가 나면 트리가 어떻게 되는가 | §9.3 — split은 메모리에서만 일어나고 디스크 반영은 체크포인트 단위로 원자적 |

## 2. 범위

| 이번에 한다 | 이번에 하지 않는다 (이유) |
|---|---|
| 고정 크기 페이지, pager, LRU 버퍼 풀 (pin·dirty) | 페이지 압축 |
| 슬롯 페이지 (leaf·internal), 리프 연결 리스트 | overflow page — 셀 크기 상한을 둔다 (§4.4) |
| insert·update·delete, leaf·internal split, 루트 split | **merge·재분배** — 삭제는 셀만 지우고 빈 리프도 남긴다 (§7.3) |
| 논리 WAL + 체크포인트 + doublewrite | ARIES식 물리 로그·undo (no-steal이라 undo가 필요 없다) |
| 동기 체크포인트 | 백그라운드 체크포인트, 퍼지 체크포인트 |
| 단일 쓰기 스레드 (@Synchronized) | 래치 커플링(crabbing) 동시성 — M7 이후 |

**merge를 하지 않는 결정**: 로드맵 M3 완료 조건에 "페이지 채움률"이 있지만, 삭제가 섞이면 merge 없이는 채움률이 떨어진다. PostgreSQL B-tree도 빈 페이지만 회수하고 적극적으로 합치지 않는다. M3는 삽입 위주 워크로드에서 채움률 하한을 검사하고, 삭제 후 채움률은 측정값으로 기록한다. 필요해지면 merge를 선택 과제로 추가한다.

## 3. 모듈과 패키지

```
engine-btree/src/main/kotlin/dev/mydb/engine/btree/
├── BTreeEngine.kt        // KvEngine 구현, WAL, 체크포인트(doublewrite), 복구, 버퍼 풀 여유 확인
├── BTreeOptions.kt       // 설정 + BTreeFaultInjector/BTreeStep
├── Page.kt               // 슬롯 페이지 한 장: 헤더·체크섬, leaf 셀과 internal 셀 연산 (meta도 같은 클래스)
├── Storage.kt            // Pager(제자리 읽기·쓰기), BufferPool(LRU, no-steal), DoubleWrite
├── BTree.kt              // 탐색, 삽입·split, 삭제, 범위
└── BTreeVerifier.kt      // 불변식 검사 → VerifyReport
```

> 구현하면서 설계의 파일 구성(LeafPage/InternalPage/MetaPage, page/·storage/ 하위 패키지)을 합쳤다. leaf와 internal은 셀 모양만 다르고 슬롯 관리가 같아서 `Page` 하나가 두 셀 형식을 모두 다룬다.

의존: `api(kv-api)`, `api(storage-common)` — WAL은 M0의 `AppendOnlyFile` + `LogRecordCodec` 그대로, doublewrite 파일도 M0 프레임.

## 4. 페이지 포맷

### 4.1 공통 헤더 (16 B)

```
offset size field
0      4    checksum   CRC32C(페이지[4..pageSize)) — 찢어진 페이지(torn page) 감지
4      1    type       0 = meta, 1 = leaf, 2 = internal
5      1    reserved
6      2    cellCount
8      2    freeStart  슬롯 배열 끝 (앞에서 뒤로 자람)
10     2    freeEnd    셀 영역 시작 (뒤에서 앞으로 자람)
12     4    link       leaf: 오른쪽 형제 페이지 번호 (0 = 없음) / internal: 가장 오른쪽 자식
```

- 페이지 번호는 u32, 0번은 meta라서 "0 = 없음"으로 쓸 수 있다.
- 체크섬은 디스크에 쓰기 직전에 계산하고 읽은 직후 검사한다. 버퍼 풀 안에서는 검사하지 않는다.

### 4.2 슬롯 페이지

```
[header 16][slot 0][slot 1]...[slot n-1] → 빈 공간 ← [cell n-1]...[cell 1][cell 0]
            u16 오프셋 × n                            freeEnd                  pageSize
```

- 슬롯 배열은 **키 순서**로 유지한다. 셀은 들어온 순서대로 뒤에서부터 쌓인다. 삽입은 슬롯 배열에 끼워 넣기(memmove) + 셀 추가다.
- 삭제·덮어쓰기로 생긴 셀 영역의 구멍은 바로 메우지 않는다. 공간이 모자랄 때 페이지를 **재압축(compact)**하고, 그래도 모자라면 split한다.

### 4.3 셀

```
leaf     | keyLen u16 | valLen u16 | key | value |
internal | keyLen u16 | childPageId u32 | key |
```

internal 페이지에서 슬롯 i의 `(key_i, child_i)`는 "`key < key_i`인 키는 `child_i` 쪽"이고, 모든 key_i 이상은 헤더의 `link`(가장 오른쪽 자식)로 간다.

### 4.4 셀 크기 상한

overflow page를 만들지 않는 대신 `key.size + value.size ≤ maxCellPayload`로 제한한다. 리프 하나에 셀이 최소 4개는 들어가야 split이 의미가 있으므로 `maxCellPayload = (pageSize − 16) / 4 − 6`(4 KiB 페이지면 1,014 B). 넘으면 `IllegalArgumentException`.

- `KvEngine` 계약의 키 64 KiB·값 16 MiB보다 훨씬 작다. 계약 테스트는 작은 값만 써서 통과하지만, **엔진마다 값 크기 상한이 다르다**는 점을 README와 M4 측정 조건에 명시한다.

### 4.5 meta 페이지 (0번)

| 필드 | 크기 |
|---|---|
| magic `"BTR1"` | u32 |
| pageSize | u32 |
| rootPageId | u32 |
| pageCount | u32 (다음 할당 번호) |
| height | u32 (리프만 있으면 1) |

## 5. Pager

```kotlin
class Pager(path: Path, pageSize: Int, counters: IoCounters) {
    fun read(pageId: Int, into: ByteArray)       // pread(pageId × pageSize), 체크섬 검사
    fun write(pageId: Int, from: ByteArray)      // 체크섬 기록 후 pwrite (제자리)
    fun allocate(): Int                          // pageCount++ (파일은 체크포인트 때 커진다)
    fun sync()
}
```

`AppendOnlyFile`을 쓰지 않는다. 처음으로 **파일 중간을 덮어쓰는** 저장소다.

## 6. 버퍼 풀

```kotlin
class BufferPool(capacityPages: Int, pager: Pager) {
    fun fetch(pageId: Int): Frame       // pin++. 없으면 빈 프레임을 확보해 읽어 온다
    fun newPage(type): Frame            // pager.allocate + pin, dirty
    fun unpin(frame: Frame)
    fun dirtyFrames(): List<Frame>      // 체크포인트가 쓴다
}
```

- LRU: 접근 순서 `LinkedHashMap(accessOrder = true)`.
- **no-steal**: 내보낼 수 있는 것은 pin이 0이고 **깨끗한** 프레임뿐이다. 더러운 페이지는 체크포인트 전까지 디스크에 가지 않는다. 그래서 디스크의 트리는 항상 "마지막 체크포인트 시점"의 일관된 상태다.
- 내보낼 깨끗한 프레임이 없으면 `BufferPoolFullException` → 엔진이 체크포인트를 하고 다시 시도한다.
- 대가: 한 체크포인트 구간에 바뀔 수 있는 페이지 수가 버퍼 풀 크기로 제한된다. 대량 쓰기는 체크포인트가 잦아진다.

## 7. B+tree 연산

### 7.1 탐색

루트부터 internal 페이지의 슬롯을 이진 탐색해 자식을 고르고 리프까지 내려간다. 리프에서 슬롯 이진 탐색.

### 7.2 삽입·갱신

```
1. 리프까지 내려가며 경로(페이지 번호 스택)를 기억
2. 키가 있으면 셀 교체, 없으면 삽입
3. 공간 부족 → compact → 그래도 부족 → split
     리프 split: 셀을 바이트 기준 절반으로 나눠 오른쪽 절반을 새 리프 R로, 링크 L → R → (L의 옛 형제)
                 부모에서 L을 가리키던 포인터(슬롯 또는 link)를 R로 바꾸고, (R의 첫 키, L) 슬롯을 삽입
                 → "R의 첫 키 미만은 L, 이상은 R" (§4.3 규칙과 일치)
     internal split: 가운데 키를 부모로 올리고(자기에게서는 제거) 나머지를 둘로
     루트 split: 새 루트를 만들고 meta의 rootPageId·height 갱신
4. 부모도 가득 차면 위로 반복
```

### 7.3 삭제

리프에서 셀을 지운다. 페이지가 비어도 그대로 둔다(부모의 구분 키도 그대로). scan은 빈 리프를 건너뛴다. 트리 높이는 줄지 않는다.

### 7.4 범위 반복자

`from`이 들어갈 리프를 찾고, 슬롯 순서대로 읽다가 리프 끝에서 `link`로 오른쪽 형제로 넘어간다. 반복 중에는 현재 리프만 pin한다.

**scan 스냅샷 의미**: 반복자 생성 이후의 쓰기가 보일 수 있다(M1·M2와 다르다). 계약 테스트는 이것을 요구하지 않는다. 반복 중에 같은 엔진으로 쓰면 split으로 셀이 다른 페이지로 옮겨 누락·중복이 생길 수 있으므로, 반복자는 생성 시점에 결과를 **복사**한다(범위 크기만큼 메모리). 단순함을 택하고 대가를 문서에 남긴다.

## 8. 읽기 경로 비용

- get: 높이 h만큼 페이지 접근. 키 100만 개, 셀 약 120 B면 리프당 약 30개 → 리프 3.3만 장, internal fan-out 약 150 → **h = 3**. 상위 두 단계는 거의 항상 버퍼 풀에 있다.
- 없는 키 get도 같은 비용(bloom 같은 사전 필터가 필요 없음).
- scan: 시작 리프까지 h, 이후 리프를 순서대로.

## 9. 내구성: WAL + 체크포인트 + doublewrite

### 9.1 쓰기

```
1. LogRecord(PUT | DELETE | BATCH)를 WAL에 append → (EVERY_WRITE면 sync)
2. 버퍼 풀의 페이지를 고친다 (dirty, 디스크에는 아직 안 감)
```

M2와 같은 "디스크(WAL) 먼저, 메모리 나중"이다. 페이지 변경은 체크포인트 전까지 디스크에 없으므로 크래시 후에는 WAL 재생으로 되살린다.

### 9.2 체크포인트

조건: 버퍼 풀에 내보낼 깨끗한 프레임이 없을 때, WAL이 `checkpointWalBytes`(기본 16 MiB)를 넘을 때, `checkpoint()` 호출 시.

```
1. 새 WAL 파일 생성 (이후 쓰기는 여기로), 디렉터리 sync
2. dirty 페이지 전부 + meta 페이지를 doublewrite 파일에 기록
     프레임: | pageId u32 | page bytes |  … 마지막에 footer 프레임 { pageCount, walIdAfter }
3. doublewrite sync                                        ← commit point         [DW_SYNCED]
4. 각 페이지를 DB 파일 제자리에 write                        (한 장씩)              [PAGE_WRITTEN]
5. DB 파일 sync
6. doublewrite 파일 삭제, 이전 WAL 삭제, 디렉터리 sync                              [CHECKPOINT_DONE]
7. 프레임 dirty 표시 해제
```

### 9.3 왜 doublewrite인가

4단계에서 페이지를 한 장씩 덮어쓰다 죽으면 두 가지가 깨진다.

- **여러 페이지 사이의 일관성**: split은 리프 2장 + 부모 1장 + (meta)를 함께 바꾼다. 일부만 디스크에 가면 부모가 없는 자식, 자식이 없는 구분 키가 생긴다.
- **페이지 하나의 일관성 (torn page)**: 4 KiB 쓰기도 디스크에서 원자적이라는 보장이 없다. 체크섬이 감지는 하지만 복구할 원본이 없다.

doublewrite 파일에 바뀔 페이지 전체가 먼저 확정되어 있으면, 4단계가 어디서 멈췄든 그 페이지들을 다시 덮어써서 "체크포인트 완료" 상태로 만들 수 있다. M1 merge의 footer, M2 MANIFEST 편집과 같은 **commit point 하나로 여러 파일 변경을 원자화**하는 패턴의 세 번째 변형이다.

### 9.4 구현하면서 찾은 두 가지 문제

설계 초안에는 없었고 코드를 쓰기 전에 발견했다.

**연산 도중 버퍼 풀이 가득 차는 문제**: split은 새 페이지를 여러 장 잡는다. 도중에 내보낼 깨끗한 프레임이 없으면 트리가 메모리에서 반쯤 바뀐 채로 멈춘다(no-steal이라 디스크로 내보낼 수도 없다). → 연산 **전에** `2 × 높이 + 2`장의 여유가 있는지 확인하고, 없으면 먼저 체크포인트한다(`ensureHeadroom`). 체크포인트 후에도 여유가 없으면 버퍼 풀이 트리 높이에 비해 작은 것이라 예외.

**배치 도중 체크포인트하면 배치 나머지가 사라지는 문제**: 배치 레코드는 이미 옛 WAL에 있다. 배치 중간에 여유가 모자라 체크포인트하면 반쯤 적용된 페이지가 확정되고 옛 WAL은 지워진다. 그 직후 죽으면 배치 나머지가 없다. → 체크포인트가 새 WAL을 만들 때 **진행 중인 레코드(carry)를 먼저 새 WAL에 쓰고 sync**한 뒤 옛 WAL을 지운다. 재생이 멱등이라 배치 전체를 다시 적용해도 결과가 같다.

같은 이유로 **복구 중 WAL 재생 때의 체크포인트는 WAL을 바꾸거나 지우지 않는다**(`replaying`). 재생이 끝난 뒤 한 번 더 체크포인트하고 그때 옛 WAL을 지운다.

### 9.5 복구 (open)

```
1. LOCK
2. doublewrite 파일이 있고 footer가 온전하면 → 안의 페이지를 전부 DB 파일에 다시 쓰고 sync (멱등)
   footer가 없거나 깨졌으면 → 체크포인트는 일어나지 않은 것, doublewrite 삭제
3. meta 페이지 읽기 → 루트, 페이지 수
   (체크섬 검사 전에 파일에 기록된 pageSize를 먼저 확인한다. 설정과 다른 크기로 읽으면 체크섬부터 틀려 "손상"으로 보이기 때문 — 테스트에서 발견)
4. 남은 WAL 전부를 번호 순으로 재생 (트리에 put/delete 적용)
5. 재생한 내용이 있으면 체크포인트 (WAL 하나 = 체크포인트 구간 하나 유지)
```

**재생이 멱등인 이유**: WAL 레코드는 "k를 v로", "k를 삭제"라는 논리 연산이다. 2에서 체크포인트가 완료된 상태가 되어 이미 반영된 레코드를 다시 적용해도 결과가 같다. 그래서 "어느 WAL까지 반영됐는가"를 정확히 기록할 필요가 없다.

**undo가 필요 없는 이유**: no-steal이라 커밋되지 않은(=WAL에 없는) 변경이 디스크에 갈 수 없다. 이것이 ARIES의 steal/no-force 조합보다 단순해지는 지점이고, 대가는 §6의 버퍼 풀 크기 제약이다.

## 10. 쓰기 증폭 예상 (M4에서 검증)

| 워크로드 | 예상 | 이유 |
|---|---|---|
| 순차 삽입 | ~~낮음 (약 3)~~ → **실측 5.39** | 리프가 꽉 찬다고 가정했으나 순차 삽입 채움률은 0.51이라 페이지 바이트가 데이터의 2.1배: 1.15 + 2 × 2.1 ≈ 5.4 ([측정](../measurements/m3-three-engines.md) §2) |
| 무작위 갱신, 키 공간 ≫ 버퍼 풀 | 매우 높음 (수십) | 체크포인트 구간마다 갱신 하나가 4 KiB 페이지 하나를 두 번 씀: 4096 × 2 / 114 ≈ 72 |
| 같은 키 반복 갱신 | 낮음 | 같은 페이지가 한 구간에 여러 번 바뀌어도 체크포인트 때 한 번만 쓰임 |

LSM은 무작위 쓰기를 순차 쓰기로 바꿔 이 차이를 줄이는 구조다. M4의 핵심 비교 항목이다.

## 11. 테스트 계획

### 11.1 단위

| 테스트 | 검증 |
|---|---|
| `PageTest` | 헤더 왕복, 체크섬 감지, 슬롯 삽입·삭제·compact 후 내용 동일 |
| `LeafPageTest` / `InternalPageTest` | 이진 탐색, 공간 부족 판정, split 결과 키 범위 |
| `BufferPoolTest` | LRU 순서, pin된 프레임은 내보내지 않음, no-steal(더러운 프레임 미방출), 가득 차면 예외 |
| `BTreeTest` | 순차·역순·무작위 삽입 각 10만 건 후 `BTreeVerifier` 통과, 높이 = 예상치 |

### 11.2 BTreeVerifier (불변식)

- 모든 페이지 체크섬 정상, 슬롯 오프셋이 셀 영역 안
- 페이지 안 키 엄격 오름차순
- internal의 구분 키가 자식 범위를 정확히 나눔 (자식의 모든 키 < key_i, 오른쪽 자식은 ≥ 마지막 key)
- 모든 리프 깊이 = meta.height
- 리프 연결 리스트 순서 = 트리 순회 순서, 키 전체가 오름차순
- 삽입 전용 워크로드에서 루트·마지막 리프를 제외한 페이지 채움률 ≥ 40%

로드맵 M3 완료 조건 "무작위 100만 건 삽입·삭제 후 불변식 검사 통과"는 채움률 항목을 삽입 전용 단계에만 적용한다(§2 merge 결정).

**관찰된 리프 채움률** (페이지 512 B, 키 2만 개):

| 삽입 순서 | 평균 | 최소 | 해석 |
|---|---:|---:|---|
| 순차 | 0.51 | 0.49 | 오른쪽 끝에서만 split이 일어나 왼쪽 절반이 영원히 반만 찬다. 실제 DB가 "가장 오른쪽 리프는 비대칭 split"을 하는 이유 |
| 역순 | 0.54 | 0.52 | 같은 현상의 거울상 |
| 무작위 | **0.69** | 0.50 | B-tree 이론값 ln 2 ≈ 0.693 (Yao, 1978)과 일치 |
| 1/3 삭제 후 | 0.36 | 0.33 | merge가 없어 지운 만큼 그대로 빈다 (§2 결정의 대가) |

### 11.3 공통 하네스

- `KvEngineContractTest` 2종: 기본, 그리고 페이지 512 B·버퍼 풀 8장(split과 체크포인트가 쉴 새 없이 일어남).
- `KvEngineRandomTest`: 유지 작업 = `checkpoint()`, 매 단계 후 `BTreeVerifier`도 돌린다.
- `KvEngineCrashTest`: 유지 작업 = `checkpoint()` (신호 직후 kill로 체크포인트 구간을 노림).

### 11.4 M3 전용

- FaultInjector: `DW_SYNCED`, `PAGE_WRITTEN`(첫 장, 중간 장), `CHECKPOINT_DONE` 각각에서 크래시 → 재시작 후 모델과 동일, 불변식 통과.
- torn page: DB 파일의 페이지 한 장 중간을 덮어써 체크섬을 깨고, 같은 페이지가 doublewrite에 있으면 복구됨 / 없으면 `CorruptionException`.
- 돌연변이 검증 (2026-10-25 실행):

| 넣은 버그 | 잡은 테스트 |
|---|---|
| (a) doublewrite 생략 | 1개 — "찢어진 페이지는 doublewrite로 복구된다"만. 체크포인트 크래시 지점 테스트는 못 잡았다. 제자리에 일부만 쓰인 페이지 조합이 우연히 서로 맞는 경우가 많기 때문으로 보인다(원인 미확인). 검증이 얇은 지점 |
| (b) no-steal 위반 (더러운 페이지를 써서 내보냄) | 3개 — 무작위, 버퍼 풀보다 큰 배치, BufferPoolTest. 크래시가 없어도 실패한다: 내보낸 페이지가 체크포인트 대상에서 빠지고, 재시작 때 디스크에 "체크포인트 이후 페이지"와 "체크포인트 시점 페이지"가 섞인다 |
| (c) split 시 리프 링크 미갱신 | 10개 — 불변식 검사·범위 스캔이 있는 거의 모든 테스트 |
| (d) carry 누락 | 7개 — 크래시 3종, 무작위, 큰 배치, 체크포인트 크래시 지점, 찢어진 페이지. 배치뿐 아니라 단일 put도 WAL 기록 **뒤에** 여유 확인·체크포인트를 하므로 put 하나가 사라진다 |

## 12. 설정

```kotlin
data class BTreeOptions(
    val dataDir: Path,
    val pageSize: Int = 4096,                 // 512 ~ 32768의 2의 거듭제곱 (헤더 u16에 페이지 안 오프셋이 들어가야 함)
    val bufferPoolPages: Int = 1024,          // 4 MiB, 최소 16
    val checkpointWalBytes: Long = 16L * 1024 * 1024,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
    val faultInjector: BTreeFaultInjector = BTreeFaultInjector.NONE,
)
```

공개 API: `KvEngine` + `checkpoint()` + `verify(): VerifyReport`(높이, 페이지 수, 리프 채움률).

## 13. 측정 (M1·M2와 같은 표 + M3 전용)

결과: [docs/measurements/m3-three-engines.md](../measurements/m3-three-engines.md). 7개 예상 중 5개 적중, 순차 삽입 쓰기 증폭 틀림(채움률 누락), scan은 규모에 따라 엇갈림.

| 항목 | 예상 |
|---|---|
| put MANUAL 처리량 | M1·M2보다 낮음 (페이지 내 memmove, split, 체크포인트) |
| 쓰기 증폭: 순차 / 무작위 갱신 | ~~약 3~~ 실측 5.39 / 수십 → 실측 67.08 (§10) |
| scan 100건 | M2와 비슷하거나 빠름 (리프 몇 장) |
| 없는 키 get | 존재하는 키와 같은 비용 |
| 키 100만 개 힙 | 버퍼 풀 크기로 고정 (4 MiB) — 키 수와 무관 |
| 재시작 | 마지막 체크포인트 이후 WAL 재생 시간 |

## 14. 구현 순서

1. ✅ `Page`(헤더·체크섬) → `LeafPage`/`InternalPage`(슬롯·compact) + 단위 테스트
2. ✅ `Pager` → `BufferPool` + 단위 테스트
3. ✅ `BTree` 탐색·삽입·split·삭제·반복자 + `BTreeVerifier` + 대량 삽입 테스트
4. ✅ `BTreeEngine`: WAL, 체크포인트(doublewrite), 복구 → 계약 테스트
5. ✅ 공통 하네스(무작위·크래시) + FaultInjector + torn page 테스트
6. ✅ 돌연변이 검증
7. ✅ 측정
