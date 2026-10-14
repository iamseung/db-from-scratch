# M2 · engine-lsm 상세 설계 (LSM-tree)

> 쓰기는 메모리의 정렬된 표(memtable)에 모았다가 정렬된 불변 파일(SSTable)로 한꺼번에 내리고, 쌓인 파일은 백그라운드에서 합친다(compaction).
> 참고: LevelDB `doc/impl.md`·`table_format.md`, DDIA 3장 "SSTables and LSM-Trees", Database Internals 7장, mini-lsm.

## 1. 이 단계에서 배우려는 것

M1 측정이 남긴 질문에서 출발한다 ([M1 측정 결과](../measurements/m1-log-engine.md)).

| M1이 남긴 문제 | M2가 푸는 방식 | 확인 방법 |
|---|---|---|
| 범위 scan이 전체 키 수에 비례 (키 100만에서 get보다 122배 느림) | SSTable은 키가 정렬되어 있어 시작 위치만 찾으면 범위만큼만 읽는다 | M1 §4 표를 같은 조건으로 다시 채움 |
| 모든 키가 메모리에 있어야 함 (키당 130 B) | 메모리에는 블록 단위 희소 인덱스와 bloom filter만 둔다 | 키 100만 개일 때 힙 사용량 |
| 없는 키 조회도 파일을 뒤져야 함 | bloom filter로 파일을 열기 전에 걸러 낸다 | bloom 끄고/켜고 없는 키 조회 비교 |
| merge가 모든 데이터를 한 번에 다시 씀 | 크기가 비슷한 파일끼리만 합친다 (size-tiered) | 쓰기 증폭, 디스크 사용량 추이 |

새로 생기는 질문:

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 같은 키가 여러 파일에 있으면 어느 것이 최신인가 | §4.3 테이블 순서 |
| 여러 파일에 걸친 변경(flush, compaction)을 어떻게 원자적으로 만드는가 | §7 MANIFEST |
| 읽는 중인 파일을 compaction이 지우면 어떻게 되는가 | §8.3 Version 고정 |
| tombstone은 언제 버릴 수 있는가 | §9.3 |
| compaction 입력은 왜 나이가 연속이어야 하는가 | §9.2 |

## 2. 범위

| 이번에 한다 | 이번에 하지 않는다 (이유) |
|---|---|
| WAL + memtable + SSTable + bloom + 희소 인덱스 | 블록 압축, 키 prefix 압축 (포맷 확장으로 나중에) |
| size-tiered compaction | leveled compaction (M4에서 비교 대상으로 추가할지 결정) |
| MANIFEST로 파일 집합의 원자적 변경 | MANIFEST 자체의 압축(재작성) |
| 동기 flush·compaction (쓰기 스레드가 직접 수행) | 백그라운드 스레드, write stall 제어 |
| scan 중 파일 삭제 지연 (Version 참조 카운트) | 블록 캐시 (OS 페이지 캐시에 맡김) |
| | 엔트리별 sequence number (§4.3에서 대신 테이블 순서를 씀) |
| | 그룹 커밋 (M4에서 M1·M2 공통으로 측정) |

memtable은 JDK `ConcurrentSkipListMap`을 쓴다. skiplist를 직접 구현하는 것은 LSM 구조와 독립된 주제라 선택 과제로 남긴다(§14).

## 3. 모듈과 패키지

```
engine-lsm → storage-common → (kv-api)
           → kv-api
```

```
engine-lsm/src/main/kotlin/dev/mydb/engine/lsm/
├── LsmEngine.kt              // KvEngine 구현, open/복구, 쓰기·읽기 경로
├── LsmOptions.kt
├── FileNames.kt              // 000012.sst / 000013.wal / MANIFEST / *.tmp 규칙
├── FaultInjector.kt          // flush·compaction 단계별 크래시 흉내 (M1과 같은 방식)
├── memtable/
│   └── MemTable.kt           // 정렬 맵 + 크기 추적, Put/Tombstone
├── sstable/
│   ├── BlockBuilder.kt       // 정렬된 엔트리를 ~4 KiB 블록으로
│   ├── BlockReader.kt        // 블록 안 순차 탐색
│   ├── BloomFilter.kt
│   ├── Footer.kt
│   ├── SSTableWriter.kt      // tmp에 쓰고 sync 후 rename
│   └── SSTableReader.kt      // 인덱스·bloom을 메모리에 올리고 블록 단위로 읽음
├── manifest/
│   ├── ManifestEdit.kt       // 파일 추가/삭제, 로그 번호, 다음 파일 번호
│   ├── Manifest.kt           // 편집 로그 기록·재생
│   └── Version.kt            // 특정 시점의 테이블 목록 (불변) + 참조 카운트
├── compaction/
│   ├── SizeTieredPicker.kt   // 어떤 테이블들을 합칠지
│   └── Compactor.kt          // 병합 실행
└── iterator/
    └── MergingIterator.kt    // k-way 병합, 최신 우선, tombstone 숨김
```

## 4. 데이터 모델

### 4.1 엔트리

```kotlin
sealed interface MemValue {
    class Put(val value: ByteArray) : MemValue
    data object Tombstone : MemValue
}
```

memtable·SSTable·병합 반복자가 모두 이 두 가지만 다룬다. LSM에서 삭제는 "삭제했다는 값"을 쓰는 것이다. 지울 대상이 이미 다른 불변 파일에 있어서 그 파일을 고칠 수 없기 때문이다.

### 4.2 키 정렬

모든 정렬은 `ByteKeys.COMPARATOR`(부호 없는 바이트 사전순)다. memtable 맵, SSTable 안 엔트리, 인덱스 이진 탐색, 병합 반복자가 같은 비교자를 써야 한다. 하나라도 다르면 scan 결과가 조용히 틀린다.

### 4.3 최신 값 판정: 테이블 순서

같은 키가 memtable과 여러 SSTable에 있을 수 있다. 최신 값을 고르는 방법은 두 가지다.

| 방법 | 장점 | 대가 |
|---|---|---|
| 엔트리마다 sequence number (LevelDB) | 어떤 파일 조합이든 compaction 가능, 스냅샷 읽기 가능 | 엔트리마다 8 B, 포맷·비교 로직 복잡 |
| **테이블 순서 (채택)** | 포맷 단순, 엔트리 오버헤드 없음 | compaction 입력이 나이순으로 연속이어야 함 (§9.2) |

- 각 SSTable은 `age`(u64)를 가진다. flush된 테이블은 단조 증가하는 flush 번호를 받는다.
- compaction 결과는 **입력 중 가장 큰 age**를 물려받는다. 입력이 나이순으로 연속이므로, 결과는 입력보다 새 테이블들보다 여전히 오래되고 입력보다 오래된 테이블들보다 새롭다.
- 파일 번호(id)는 나이와 다르다. compaction 결과는 새 파일 번호를 받지만 나이는 입력에서 물려받는다. 이 둘을 섞으면 compaction 결과가 실제보다 새것으로 취급되어 옛 값이 새 값을 가린다.
- 읽기 우선순위: memtable → SSTable을 age 내림차순.
- MVCC(M7)는 KV 키 안에 commitTs를 인코딩하므로 엔진 수준 sequence number가 없어도 된다.

## 5. 디스크 레이아웃

```
data/
├── LOCK
├── MANIFEST          편집 로그 (AppendOnlyFile, 프레임 = 편집 1건)
├── 000009.wal        현재 memtable의 WAL
├── 000004.sst        age 3
├── 000007.sst        age 6
└── 000010.sst.tmp    쓰는 중인 테이블 (완성 전, 복구 시 삭제)
```

- 파일 번호는 `.wal`과 `.sst`가 공유하는 하나의 카운터에서 받는다(LevelDB와 같음). 다음 번호는 MANIFEST에 기록된다.
- 디렉터리에 있어도 **MANIFEST가 모르는 `.sst`는 쓰레기**다. 복구가 지운다.

## 6. SSTable 포맷

### 6.1 전체 구조

SSTable은 M0 프레임의 연속이다. 블록마다 CRC가 붙으므로 손상 감지를 새로 만들 필요가 없고, 블록 읽기는 `AppendOnlyFile.readFrame(offset)` 한 번이다.

```
[frame: data block 0]
[frame: data block 1]
...
[frame: data block n-1]
[frame: index block]
[frame: bloom filter]
[frame: footer]          ← payload 고정 44 B, 프레임 전체 52 B. 파일 끝에서 52 B 앞이 시작
```

### 6.2 data block

목표 크기 `blockSizeBytes`(기본 4 KiB). 엔트리를 정렬 순서로 채우다가 목표를 넘으면 블록을 닫는다. 엔트리 하나가 목표보다 크면 그 블록에 혼자 들어간다.

```
| entryCount u32 |
| keyLen u32 | valLen i32 | key | value |   × entryCount
```

- `valLen = -1`이면 tombstone이고 value 바이트가 없다.
- 블록 안은 순차 탐색한다. 4 KiB에 100 B 값이면 엔트리 약 30개라 이진 탐색이 필요 없다. 키 prefix 압축과 restart point(LevelDB)는 선택 과제(§14).

### 6.3 index block

data block마다 엔트리 하나. **블록의 마지막 키**를 기록한다.

```
| blockCount u32 |
| lastKeyLen u32 | lastKey | blockOffset u64 | blockFrameSize u32 |   × blockCount
```

- 찾는 키 `k`에 대해 `lastKey ≥ k`인 첫 블록을 이진 탐색한다. 없으면 이 테이블에 `k`가 없다.
- 첫 키를 기록해도 동작한다("`firstKey ≤ k`인 마지막 블록"). 마지막 키를 고른 이유는 get과 scan이 같은 규칙 하나를 쓰기 때문이다. scan의 시작 블록도 "`lastKey ≥ from`인 첫 블록"이다. LevelDB는 마지막 키 대신 그보다 크거나 같은 더 짧은 구분 키를 써서 인덱스를 줄인다(선택 과제).
- 인덱스 전체를 테이블을 열 때 메모리에 올린다. 메모리는 키 수가 아니라 **블록 수**에 비례한다.

### 6.4 bloom filter

```
| bitCount u32 | hashCount u8 | bits (ceil(bitCount / 8) 바이트) |
```

- 키당 `bloomBitsPerKey`(기본 10) 비트, 해시 수 `k = round(bitsPerKey × ln 2)` = 7. 이론 위양성률 약 0.8%.
- 해시: 키 바이트의 64비트 FNV-1a → 상위·하위 32비트를 `h1`, `h2`로 나눠 `h1 + i × h2` (i = 0..k-1)로 k개 위치를 만든다(Kirsch–Mitzenmacher 이중 해싱).
- FNV-1a는 분포가 최상급은 아니다. 테스트에서 실제 위양성률을 재서 2%를 넘으면 해시를 바꾼다(§12.1).
- 테이블을 열 때 메모리에 올린다. 키당 1.25 B.
- `bloomBitsPerKey = 0`이면 bloom을 만들지 않는다(측정 비교용).

### 6.5 footer (payload 44 B)

```
| indexOffset u64 | indexFrameSize u32 | bloomOffset u64 | bloomFrameSize u32 |
| entryCount u64 | age u64 | magic u32 = 0x4C534D31 ("LSM1") |
```

- footer는 고정 크기 프레임이라 파일 크기만 알면 위치가 정해진다.
- magic이 다르거나 프레임 CRC가 틀리면 `CorruptionException`. SSTable은 sync 후 rename된 완성 파일만 MANIFEST에 오르므로, 여기서 손상이 보이면 잘린 쓰기가 아니라 디스크 결함이나 버그다.
- `age`를 footer에도 두는 이유: MANIFEST와 파일이 어긋났는지 열 때 대조할 수 있다.

### 6.6 쓰기 절차

```
1. 000012.sst.tmp 에 data block들 → index → bloom → footer 순으로 append
2. sync
3. 000012.sst.tmp → 000012.sst rename (ATOMIC_MOVE)
4. 디렉터리 sync
```

이 시점에도 테이블은 아직 "존재하지 않는다". MANIFEST에 기록되어야(§7) 읽기 대상이 된다.

## 7. MANIFEST

### 7.1 왜 필요한가

M1의 merge는 "footer가 있는 merge 파일 하나"가 commit point였다. LSM에서는 한 번의 변경이 여러 파일에 걸친다.

- flush: 새 SSTable 추가 + 이전 WAL 폐기
- compaction: 새 SSTable 추가 + 입력 SSTable 여러 개 제거

이것을 원자적으로 만들기 위해 "지금 살아 있는 파일 집합"을 별도의 편집 로그에 기록한다. **편집 한 건 = 프레임 한 개**이므로, M0의 프레임 원자성이 그대로 변경의 원자성이 된다.

### 7.2 편집 포맷

```
edit = | changeCount u32 | change × changeCount |

ADD_TABLE (0x10)    | fileId u64 | age u64 | entryCount u64 | fileSize u64 |
                    | minKeyLen u32 | minKey | maxKeyLen u32 | maxKey |
REMOVE_TABLE (0x11) | fileId u64 |
LOG_NUMBER (0x12)   | walFileId u64 |        ← 이 번호 미만의 WAL은 이미 SSTable에 반영됨
NEXT_FILE_ID (0x13) | fileId u64 |
NEXT_AGE (0x14)     | age u64 |
```

- `minKey`/`maxKey`는 읽기에서 범위 밖 테이블을 건너뛰는 데 쓴다. bloom보다 먼저, 비용 0으로 거른다.
- MANIFEST는 엔진 수명 동안 계속 커진다. 편집은 flush·compaction마다 한 건이라 학습 규모에서는 문제없다. 일정 크기를 넘으면 현재 상태 스냅샷으로 새 MANIFEST를 쓰고 `CURRENT` 파일로 가리키는 방식(LevelDB)은 선택 과제(§14).

### 7.3 재생

```
1. MANIFEST의 프레임을 순서대로 디코딩해 상태에 적용
     tables: Map<fileId, TableMeta>, logNumber, nextFileId, nextAge
2. 마지막 프레임이 잘렸으면 TOLERATE_TAIL 규칙대로 잘라낸다
     (sync 전에 죽은 편집 = commit되지 않은 변경)
3. 결과 = 현재 Version
```

## 8. 엔진 상태와 Version

### 8.1 메모리 상태

```kotlin
class LsmEngine(
    private var memtable: MemTable,
    private var wal: AppendOnlyFile,          // memtable 내용의 내구성
    private var current: Version,             // 살아 있는 SSTable 목록 (age 내림차순)
    private val manifest: Manifest,
    private val readers: Map<Long, SSTableReader>,  // fileId → 열린 테이블
)
```

### 8.2 Version

```kotlin
class Version(val tables: List<TableMeta>) {   // age 내림차순, 불변
    private val refs = AtomicInteger(1)         // 엔진의 current 참조 1
    fun retain()
    fun release()                               // 0이 되면 이 Version에만 있던 파일을 지울 수 있다
}
```

### 8.3 scan 중 compaction

scan 반복자는 만들 때 current Version을 `retain()`하고, `close()`에서 `release()`한다. compaction은 새 Version을 만들고 이전 Version을 `release()`한다. 입력 파일은 **그 파일을 가진 모든 Version의 참조가 0이 될 때** 지운다.

- 반복자를 닫지 않으면 파일이 영원히 남는다. `KvIterator`가 `AutoCloseable`인 이유가 여기서 처음 실체가 된다.
- memtable은 scan 생성 시점에 범위만큼 복사한다(memtable은 최대 `memtableSizeBytes`라 작다). M1 LogEngine과 같은 "생성 시점 스냅샷" 의미가 된다.

## 9. 쓰기 경로

### 9.1 put / delete / writeBatch

```
1. LogRecord(PUT | DELETE | BATCH)를 WAL에 append → (EVERY_WRITE면 sync)
2. memtable에 반영 (Put 또는 Tombstone)
3. memtable.approximateBytes ≥ memtableSizeBytes 이면 flush (§9.2)
4. flush 후 compaction 조건이 맞으면 compaction (§9.3)
```

- WAL 포맷은 M0의 `LogRecordCodec` 그대로다. M1과 달리 WAL은 위치 인덱스가 아니라 **memtable 복구용**이고, flush되면 버린다.
- M1과 마찬가지로 디스크(WAL) 먼저, 메모리 나중이다.
- 없는 키의 delete도 tombstone을 쓴다. M1은 keydir로 존재 여부를 알았지만 LSM은 확인하려면 디스크를 읽어야 해서, 확인하지 않고 쓰는 쪽이 싸다.

### 9.2 flush

```
1. 새 WAL 파일 번호 w' 발급, 새 WAL 생성, 디렉터리 sync
2. memtable 전체를 순서대로 SSTableWriter에 → 000012.sst (§6.6)       [FLUSH_TABLE_WRITTEN]
3. MANIFEST에 편집 { ADD_TABLE(12, age=nextAge), NEXT_AGE, LOG_NUMBER(w'), NEXT_FILE_ID } append + sync   ← commit point
                                                                       [FLUSH_COMMITTED]
4. 이전 WAL 삭제, 새 Version 설치, memtable 비우기
```

크래시 지점별 복구 결과:

| 죽은 지점 | 재시작 후 |
|---|---|
| 2 도중 / 2 후 3 전 | `.tmp` 또는 MANIFEST가 모르는 `.sst` → 삭제. 이전 WAL이 `LOG_NUMBER` 이상이라 재생됨. 데이터 동일 |
| 3 후 4 전 | 이전 WAL 번호 < `LOG_NUMBER` → 이미 반영된 WAL이므로 삭제. 데이터 동일 |

### 9.3 compaction (size-tiered)

**고르기** (`SizeTieredPicker`):

```
1. 테이블을 age 순으로 놓는다
2. 나이순으로 연속인 구간 중, 모든 테이블 크기가 구간 평균의 [0.5, 1.5]배 안이고 개수가 minThreshold(4) 이상인 것을 찾는다
3. 여러 개면 테이블 수가 가장 많은 구간, 같으면 가장 오래된 구간
4. 최대 maxThreshold(32)개까지만
```

**왜 나이가 연속이어야 하는가**: 테이블 A(오래됨) - B - C(새것)에서 A와 C만 합쳐 결과 R(age = C의 age)을 만들었다고 하자. A에 `k=1`, B에 `k=2`가 있고 C에는 `k`가 없으면, R에 `k=1`이 들어가 B보다 새것으로 취급된다. 이제 읽기는 옛 값 1을 돌려준다. sequence number가 있으면 이 문제가 없다(§4.3의 대가).

**실행** (`Compactor`):

```
1. 입력 테이블 반복자들을 MergingIterator로 병합 (같은 키는 age가 큰 쪽이 이김)
2. tombstone 처리 (아래)
3. 결과를 새 SSTable 하나로 (§6.6), age = 입력 중 최댓값                [COMPACTION_TABLE_WRITTEN]
4. MANIFEST 편집 { ADD_TABLE(결과), REMOVE_TABLE(입력...), NEXT_FILE_ID } + sync   ← commit point
                                                                        [COMPACTION_COMMITTED]
5. 새 Version 설치, 이전 Version release → 참조가 0인 입력 파일 삭제      [INPUT_DELETED]
```

**tombstone을 버릴 수 있는 조건**: 입력 구간이 **가장 오래된 테이블을 포함**할 때만. 그보다 오래된 테이블이 없으니 tombstone이 가릴 옛 값도 없다. 그렇지 않으면 tombstone을 결과에 그대로 남긴다. M1 merge가 "활성 세그먼트를 뺀 전부"라서 항상 버릴 수 있었던 것과 대비된다.

**쓰기 증폭 예상**: size-tiered에서 한 엔트리는 크기 단계가 바뀔 때마다 한 번씩 다시 쓰인다. 단계 수는 대략 log₄(전체 크기 / memtable 크기)다. 100 MiB / 1 MiB면 약 3.3단계 → WAL 1 + flush 1 + compaction 약 3 = 쓰기 증폭 약 5 예상. M4에서 검증한다.

## 10. 읽기 경로

### 10.1 get

```
1. memtable에서 찾음 → Put이면 값, Tombstone이면 null
2. current Version의 테이블을 age 내림차순으로:
     a. k가 [minKey, maxKey] 밖이면 건너뜀
     b. bloom이 "없음"이면 건너뜀
     c. 인덱스 이진 탐색 → 블록 하나 읽기 → 블록 안 순차 탐색
     d. 찾으면 Put → 값, Tombstone → null 로 종료
3. 끝까지 없으면 null
```

디스크 읽기는 bloom이 맞을 때 테이블당 블록 하나다. 최악은 테이블 수만큼이고, compaction이 테이블 수를 줄이는 이유가 이것이다(읽기 증폭).

### 10.2 scan

```
MergingIterator(
    memtable 범위 스냅샷,          ← 우선순위 가장 높음
    테이블 반복자들 (age 내림차순)  ← 각자 [from, to) 범위만
)
```

- 반복자들을 현재 키 기준 최소 힙(`PriorityQueue`)에 넣는다. 같은 키가 여러 반복자에 있으면 우선순위가 높은(새) 것 하나만 내보내고 나머지는 같은 키를 건너뛴다.
- 내보낼 값이 Tombstone이면 건너뛴다.
- 테이블 반복자는 인덱스로 `from`이 있는 블록을 찾아 거기서부터 블록 단위로 읽는다. 결과 100건이면 블록 몇 개만 읽는다. M1에서는 이 부분이 전체 키 순회였다.

## 11. 복구 (open)

```
1. LOCK 획득
2. MANIFEST 재생 → tables, logNumber, nextFileId, nextAge
3. 디렉터리 정리: *.tmp, MANIFEST가 모르는 *.sst, logNumber 미만의 *.wal 삭제
4. 각 테이블을 SSTableReader로 연다 (footer·인덱스·bloom 로드, footer.age = MANIFEST age 대조)
5. logNumber 이상의 WAL을 번호 순으로 memtable에 재생 (마지막 WAL의 잘린 꼬리는 M1과 같은 규칙)
6. memtable이 비어 있지 않으면 즉시 flush
7. 새 WAL을 열고 시작
```

6에서 바로 flush하는 이유: 재생한 WAL에 이어 쓰면 "이 WAL이 어느 memtable 것인가"가 흐려진다. flush하고 새 WAL로 시작하면 WAL 하나 = memtable 하나가 항상 성립한다(LevelDB 방식).

## 12. 테스트 계획

### 12.1 단위

| 테스트 | 검증 |
|---|---|
| `MemTableTest` | 정렬 순서, 덮어쓰기, tombstone, 크기 추적 |
| `BlockTest` | 왕복, 블록 경계, 목표보다 큰 엔트리, tombstone |
| `BloomFilterTest` | 넣은 키는 항상 "있음", 넣지 않은 키 10만 개로 위양성률 < 2% |
| `SSTableTest` | 왕복, 블록 수천 개일 때 모든 키 get, 범위 밖 키, 블록 경계 직전·직후 키, footer·블록 손상 감지 |
| `ManifestTest` | 편집 왕복, 재생, 잘린 마지막 편집 무시 |
| `MergingIteratorTest` | 최신 우선, tombstone 숨김, 빈 반복자, 모든 반복자에 같은 키 |
| `SizeTieredPickerTest` | 연속 구간만 고름, 크기 범위, 임계값, tombstone 버림 가능 여부 판정 |

### 12.2 엔진 공통 (M1에서 만든 것 재사용)

- `KvEngineContractTest` 상속 2개: 기본 설정, 그리고 memtable 256 B·블록 64 B·minThreshold 2로 flush와 compaction이 쉴 새 없이 일어나는 설정.
- 모델 기반 무작위 테스트: M1의 `LogEngineRandomTest`를 `kv-api` testFixtures의 `KvEngineRandomTest`로 옮기고, 엔진별 유지 작업(M1 `merge()`, M2 `compact()`)을 훅으로 받는다.
- `kill -9` 크래시 테스트: `CrashWriterMain`과 검증 로직을 엔진 독립으로 옮기고(엔진 생성 함수를 인자로), M1·M2가 같은 하네스를 쓴다. M2는 flush·compaction 직전에 신호를 출력해 그 구간을 노린다(M1 merge 모드와 같은 기법).

### 12.3 M2 전용

- FaultInjector 4지점(`FLUSH_TABLE_WRITTEN`, `FLUSH_COMMITTED`, `COMPACTION_TABLE_WRITTEN`, `COMPACTION_COMMITTED`) + `INPUT_DELETED`에서 크래시 후 재시작 → 모델과 동일, 고아 파일 없음.
- MANIFEST 마지막 편집을 바이트 단위로 잘라 재시작 → 그 편집 이전 상태.
- scan 반복자를 연 채로 compaction → 반복자는 끝까지 옛 데이터를 돌려주고, 닫은 뒤에야 입력 파일이 지워짐.
- age 연속 규칙: 비연속 구간을 강제로 합치는 테스트용 경로로 §9.3의 반례가 실제로 틀린 값을 돌려주는지 보여 주고(규칙의 근거), 정상 경로는 그런 구간을 고르지 않음을 확인.
- tombstone: 가장 오래된 테이블을 포함하지 않는 compaction 뒤에도 지운 키가 되살아나지 않음.

## 13. 설정

```kotlin
data class LsmOptions(
    val dataDir: Path,
    val memtableSizeBytes: Long = 1L * 1024 * 1024,   // RocksDB 기본 64 MiB. 학습 중 flush를 자주 보려고 작게
    val blockSizeBytes: Int = 4 * 1024,
    val bloomBitsPerKey: Int = 10,                     // 0이면 bloom 없음
    val compactionMinThreshold: Int = 4,
    val compactionMaxThreshold: Int = 32,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
    val faultInjector: FaultInjector = FaultInjector.NONE,
)
```

공개 API: `KvEngine` + `LsmEngine.compact(): CompactionResult?`(조건이 맞는 구간이 없으면 null) + `LsmEngine.compactAll()`(전체를 하나로, tombstone 전부 제거).

## 14. 선택 과제 (M2 이후 필요하면)

| 과제 | 배우는 것 |
|---|---|
| skiplist 직접 구현 | 확률적 균형, lock-free 읽기 |
| 키 prefix 압축 + restart point | 블록 크기와 탐색 비용의 균형 |
| MANIFEST 스냅샷 + CURRENT | 로그 압축, 원자적 포인터 교체 |
| 백그라운드 flush·compaction | write stall, 동시성 제어 |
| leveled compaction | 쓰기 증폭 ↔ 읽기·공간 증폭 교환 (M4 비교) |
| 엔트리별 sequence number | 비연속 compaction, 스냅샷 읽기 |

## 15. 측정 (M1과 같은 표로 비교, M4 준비)

| 항목 | 예상 (검증 대상) |
|---|---|
| put 처리량 MANUAL | M1과 비슷하거나 약간 낮음 (memtable 삽입 비용 + 주기적 flush) |
| 쓰기 증폭 | 약 5 (§9.3 계산) — M1의 1.2보다 높음 |
| scan 100건, 키 100만 | 블록 몇 개 읽기 → M1의 16 ms보다 크게 낮음 |
| 없는 키 get, bloom 켬/끔 | bloom 없으면 테이블 수만큼 블록 읽기 |
| 키 100만 개 힙 | 블록 인덱스 + bloom ≈ 키당 수 B — M1의 130 B보다 크게 낮음 |
| 재시작 시간 | MANIFEST 재생 + 인덱스·bloom 로드 + WAL 하나 재생 → 데이터 크기와 거의 무관 |

## 16. 구현 순서

1. 공통 하네스 이동: `KvEngineRandomTest`(testFixtures), 엔진 독립 크래시 하네스 → M1이 그대로 통과하는지 확인
2. `MemTable` + `MergingIterator`
3. `BlockBuilder`/`BlockReader` → `BloomFilter` → `Footer` → `SSTableWriter`/`SSTableReader`
4. `ManifestEdit`/`Manifest`/`Version`
5. `LsmEngine`: WAL + memtable + flush + get/scan → 계약 테스트 통과
6. 복구 (§11) → 크래시 테스트
7. `SizeTieredPicker` + `Compactor` + Version 참조 카운트
8. FaultInjector 테스트, 무작위 테스트, 크래시 테스트 (flush·compaction 구간)
9. 측정 (§15)
