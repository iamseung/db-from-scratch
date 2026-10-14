# M1 · engine-log 상세 설계 (Bitcask형 로그 KV)

> 가장 단순한 영속 KV. 모든 쓰기를 파일 끝에 추가하고, 메모리 해시맵이 "각 키의 최신 값이 어느 파일 몇 번째 바이트에 있는지"를 기억한다.
> 참고: Bitcask 논문 (Basho, 2010), DDIA 3장 "Hash Indexes".

## 1. 이 단계에서 배우려는 것

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 추가 전용 쓰기는 왜 빠른가 | §5 쓰기 경로, M4 벤치마크 |
| 크래시 후 무엇을 근거로 상태를 되살리는가 | §7 복구 |
| 삭제는 왜 "지우기"가 아니라 "지웠다는 기록"인가 | §5.2 tombstone |
| 로그가 무한히 커지는 문제를 어떻게 푸는가 | §8 merge |
| 여러 파일을 바꾸는 작업을 어떻게 원자적으로 만드는가 | §8.3 commit point |
| 해시 인덱스로는 왜 범위 스캔이 비싼가 | §6.2 → M2(LSM) 동기 |

## 2. 모듈 의존성과 패키지

```
engine-log → storage-common → kv-api
```

```
engine-log/src/main/kotlin/dev/mydb/engine/log/
├── LogEngine.kt          // KvEngine 구현 + 복구(open) + rollover. 세그먼트는 TreeMap<id, AppendOnlyFile>
├── LogEngineOptions.kt
├── Keydir.kt             // 메모리 인덱스, BytesKey, Location, Keydir.apply(record, location)
├── SegmentFiles.kt       // 파일 이름 규칙(000001.log), id 순 목록
├── Merger.kt             // merge 파일 완료 판정·설치·복구 (엔진 merge와 재시작 복구가 공유)
├── MergeResult.kt        // merge 결과: 대상 세그먼트, 살아 있는 엔트리, 전후 바이트
└── FaultInjector.kt      // 테스트용 크래시 지점 주입 (MergeStep 5곳)
```

## 3. 디스크 레이아웃

```
data/
├── LOCK
├── 000001.log      불변
├── 000002.log      불변
├── 000003.log      활성 (쓰기 대상, 항상 id가 가장 큼)
└── 000002.merge    merge 진행 중에만 존재
```

- 파일 이름 = 6자리 0 채움 id. **id 순서 = 시간 순서**라는 불변식이 복구의 근거다.
- 세그먼트 크기 상한 `segmentSizeBytes` 기본 4 MiB. 학습 중 회전(rollover)과 merge를 자주 보기 위해 일부러 작게 잡는다. 실제 Bitcask 기본은 2 GiB.
- 각 `.log` 파일은 M0의 프레임 연속이고, 프레임 payload는 `PUT` / `DELETE` / `BATCH` 레코드다.

## 4. 메모리 구조

```kotlin
class BytesKey(bytes: ByteArray) {                  // equals/hashCode를 내용 기반으로 → 아래 주의
    val bytes: ByteArray = bytes.copyOf()
    override fun equals(other: Any?) = other is BytesKey && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
}

data class Location(
    val segmentId: Int,
    val frameOffset: Long,    // 프레임 시작 위치
    val frameSize: Int,       // 한 번의 pread로 읽을 크기
    val indexInBatch: Int = -1, // BATCH 프레임 안의 위치, -1 = 단일 레코드 (§5.3)
)

class Keydir {
    private val map = HashMap<BytesKey, Location>()
    fun get(key: ByteArray): Location?
    fun put(key: ByteArray, loc: Location)
    fun remove(key: ByteArray)
    fun keys(): Collection<BytesKey>
    val size: Int
}
```

- **주의**: `ByteArray`의 `equals`는 내용이 아니라 참조를 비교해서, `HashMap` 키로 그대로 쓰면 같은 내용의 다른 배열을 찾지 못한다. 그래서 `BytesKey`로 감싸 `contentEquals` / `contentHashCode`를 쓴다. `Keydir.put`만 배열을 **복사**해 호출자가 원본을 바꿔도 인덱스가 깨지지 않게 한다. 조회용 `BytesKey`는 복사하지 않는다.
- keydir 변경은 `Keydir.apply(record, location)` 한 곳에서만 한다. 쓰기 경로와 복구 경로가 다른 규칙으로 keydir를 바꾸면 재시작 전후 상태가 달라지기 때문이다.
- 메모리 비용: 키 1개당 키 바이트 + 약 60~80바이트(객체 헤더, 해시 엔트리). 모든 키가 메모리에 있어야 한다는 것이 Bitcask의 핵심 제약이고, M2에서 LSM이 이것을 푸는 방식과 비교한다.
- 정렬하지 않는 `HashMap`을 일부러 쓴다. `TreeMap`을 쓰면 범위 스캔이 쉬워지지만 그건 사실상 메모리 B-tree를 하나 더 두는 것이라 이 단계의 약점이 가려진다.

## 5. 쓰기 경로

### 5.1 put

```
1. 크기 검증 (key ≤ 64 KiB, value ≤ 16 MiB)
2. 활성 세그먼트 size + 예상 프레임 크기 > segmentSizeBytes 이면 rollover (§5.4)
3. payload = LogRecordCodec.encode(Put(key, value))
4. offset = active.append(payload)
5. syncPolicy == EVERY_WRITE 이면 active.sync()
6. keydir.put(key, Location(activeId, offset, 8 + payload.size))
7. userBytesWritten += key.size + value.size
```

**4·5 다음에 6을 하는 이유**: keydir를 먼저 갱신하고 append가 실패하면, 메모리는 존재하지 않는 데이터를 가리킨다. 디스크가 먼저, 메모리가 나중이다. 이 순서는 이후 모든 엔진에 적용된다.

### 5.2 delete

```
1. keydir에 키가 없으면 아무것도 하지 않음 (tombstone도 쓰지 않음)
2. DELETE 레코드(tombstone) append → (sync) → keydir.remove(key)
```

tombstone이 필요한 이유: 이전 세그먼트에 그 키의 PUT이 남아 있다. tombstone 없이 메모리에서만 지우면 재시작 시 복구 과정이 옛 PUT을 다시 읽어 키가 살아난다.

### 5.3 writeBatch

- `ops`를 `LogRecord.Batch` 하나로 인코딩해 **프레임 하나**로 append → (sync) → 각 op를 순서대로 keydir에 적용.
- 같은 배치 안에 같은 키가 여러 번 나오면 뒤의 것이 이긴다(순서대로 적용하므로 자연스럽게 성립).
- 배치 안의 모든 op는 같은 `Location`(배치 프레임)을 가리킨다. 그래서 `get`은 프레임을 읽은 뒤 배치 안에서 해당 키의 **마지막** op를 찾아야 한다 → `Location.indexInBatch`에 키가 배치 안에서 마지막으로 등장한 위치를 기록한다.

### 5.4 rollover

```
1. active.sync()                      // 불변이 되기 전에 확정
2. 새 id = active.id + 1 로 파일 생성
3. Fsync.directory(dataDir)           // 새 파일의 존재 확정
4. 기존 active는 읽기 전용으로 다시 열어 immutable 목록에 추가
```

## 6. 읽기 경로

### 6.1 get

```
1. loc = keydir.get(key) ?: return null
2. result = segments[loc.segmentId].readFrame(loc.frameOffset)
3. Ok가 아니면 CorruptionException  (keydir가 가리키는 곳은 반드시 온전해야 함)
4. 레코드 디코딩 → 값 반환 (Batch면 indexInBatch 위치의 op)
```

디스크 읽기는 정확히 1회(pread 1번). Bitcask가 "조회 시 탐색 1회"를 내세우는 근거다. OS 페이지 캐시가 자주 읽는 세그먼트를 메모리에 두므로 별도 캐시는 만들지 않는다.

### 6.2 scan(from, to) — 의도적으로 비싼 구현

```
1. keydir.keys() 전체를 훑어 [from, to) 에 드는 키만 추림       O(N)
2. ByteKeys.COMPARATOR로 정렬                                   O(M log M)
3. 정렬된 키마다 get                                            M번의 무작위 pread
4. 스냅샷 시점: 1번 시점의 Location 목록을 고정해 순회
```

- 범위가 작아도 전체 키 수 N에 비례한다. 이 비용을 M4에서 측정해 두면 M2(정렬된 SSTable)가 무엇을 해결하는지 숫자로 보인다.
- `KvIterator.close()`는 아무 자원도 잡지 않지만 계약상 구현한다.

## 7. 복구 (open 시)

```
1. DirectoryLock 획득
2. *.merge 파일 처리 (§8.4) — 반드시 세그먼트 재생보다 먼저
3. *.log 파일을 id 오름차순으로 정렬
4. 각 세그먼트의 프레임을 처음부터 순회
     Ok(PUT)    → keydir.put
     Ok(DELETE) → keydir.remove
     Ok(BATCH)  → 내부 op를 순서대로 적용
     Corrupted  → 마지막(최신) 세그먼트면 그 offset으로 truncate 후 종료
                  중간 세그먼트면 CorruptionException (불변 파일은 sync 후 닫혔으므로 손상될 수 없음)
5. 마지막 세그먼트를 활성 세그먼트로 열기 (없으면 000001.log 생성)
```

- 복구 시간은 전체 데이터 크기에 비례한다. Bitcask는 이를 "hint 파일"(keydir 스냅샷)로 줄이는데, **M1에서는 만들지 않는다**. 대신 1 GiB 데이터 기준 재시작 시간을 측정해 기록해 둔다. 필요하다고 느껴지면 그때 추가한다.
- truncate는 `RecoveryMode.TOLERATE_TAIL`(M0 §3.2)일 때만. `STRICT`면 예외.

## 8. Merge (compaction)

### 8.1 언제

- 수동: `LogEngine.merge(): MergeResult`
- 자동은 M1 범위 밖. 원래 `deadBytesRatio`를 상시 집계하려 했으나, 배치 프레임 하나를 여러 키가 공유해서 키 단위로 죽은 바이트를 정확히 세기 어렵다. 대신 `MergeResult(bytesBefore, bytesAfter, liveEntries)`로 merge 한 번이 얼마나 회수했는지 관찰한다.

### 8.2 무엇을

- 대상: 활성 세그먼트를 뺀 **모든** 불변 세그먼트 (id 1..n).
- 출력: keydir가 대상 세그먼트를 가리키는 키만(= 살아 있는 최신 값) 새 파일에 PUT으로 다시 쓴다.
- **tombstone은 버린다.** 대상이 활성 세그먼트를 제외한 전부이므로, 출력보다 오래된 세그먼트가 남지 않는다. 지워진 키의 옛 PUT도 함께 사라지므로 부활할 수 없다. (일부 세그먼트만 병합하면 이 논리가 깨진다 → M2 compaction에서 다시 만나는 문제)

### 8.3 절차와 commit point

```
1. n = 불변 세그먼트 중 최대 id
2. 000n.merge 에 살아 있는 엔트리를 PUT으로 기록, 새 Location을 임시 맵에 보관
3. 마지막에 MERGE_FOOTER(entryCount) 프레임 기록
4. merge 파일 sync + 디렉터리 sync                      ← commit point
5. id ≤ n 인 .log 파일 전부 삭제
6. 000n.merge → 000n.log 로 rename (ATOMIC_MOVE)
7. 디렉터리 sync
8. keydir 갱신: 임시 맵의 각 키에 대해, keydir가 아직 옛 세그먼트를 가리키면 새 Location으로 교체
9. 읽기용 세그먼트 핸들 교체
```

- 5~7단계는 `Merger.install`로 묶어 재시작 복구(`Merger.recover`)와 같은 코드를 쓴다.
- 2단계는 keydir 엔트리를 (세그먼트, offset) 순으로 정렬해 옛 세그먼트를 순차로 읽는다. 배치에 담겼던 값은 단일 PUT으로 풀어서 쓴다(이미 확정된 값이라 원자성이 필요 없다).
- 같은 프로세스에서 앞선 merge가 실패해 남긴 `.merge` 파일은 확정 전이므로 새 merge 시작 시 지운다.

- merged 파일이 id `n`을 재사용하는 이유: 활성 세그먼트 id는 n보다 크므로, 파일 이름 순서 = 시간 순서 불변식이 유지된다.
- 8단계의 "아직 옛 세그먼트를 가리키면": M1은 단일 스레드라 merge 중 쓰기가 끼어들지 않지만, 나중에 백그라운드 merge로 바꿀 때를 대비한 조건이다.

### 8.4 merge 도중 크래시

FOOTER가 commit point다. 그 전이면 merge는 없던 일, 그 후면 일어난 일이다. 시작 시 아래 규칙만 적용하면 모든 경우가 수렴한다(멱등).

| 시작 시 발견한 상태 | 처리 |
|---|---|
| `n.merge` 있음, FOOTER 없음 또는 손상 | merge 파일 삭제. 기존 `.log`는 그대로 → merge 전 상태 |
| `n.merge` 있음, FOOTER 정상 | id ≤ n 인 `.log` 삭제 → rename → 디렉터리 sync (= §8.3의 5~7 재실행) |
| `n.merge` 없음 | 할 일 없음 (rename까지 끝났거나 merge가 없었음) |

크래시 지점별 기대 결과 (테스트로 하나씩 검증):

| 크래시 지점 (FaultInjector) | 재시작 후 |
|---|---|
| 2단계 중간 | merge 전 상태, 데이터 동일 |
| 3단계 직후, 4단계 전 (FOOTER는 썼지만 sync 전) | 전원 차단이면 FOOTER가 없을 수 있음 → merge 전 상태. 프로세스 크래시면 FOOTER 있음 → merge 후 상태. 어느 쪽이든 데이터 동일 |
| 5단계 중간 (일부 .log만 삭제됨) | FOOTER 정상 → 남은 .log 삭제 후 rename. 데이터 동일 |
| 6단계 직후 | `.merge` 없음, 정상 |

## 9. 동시성

- M1의 `LogEngine`은 모든 public 메서드를 `synchronized`로 감싼다. 단일 쓰기 스레드라는 설계 결정(M7 전까지)을 코드로 강제하는 가장 단순한 방법이다.
- 읽기 병렬화(pread는 스레드 안전)는 M4에서 측정할 때 필요하면 `ReentrantReadWriteLock`으로 바꾼다.

## 10. 설정

```kotlin
data class LogEngineOptions(
    val dataDir: Path,
    val segmentSizeBytes: Long = 4L * 1024 * 1024,
    val syncPolicy: SyncPolicy = SyncPolicy.EVERY_WRITE,
    val recoveryMode: RecoveryMode = RecoveryMode.TOLERATE_TAIL,
    val faultInjector: FaultInjector = FaultInjector.NONE,   // 테스트 전용
)
```

## 11. 테스트 계획

### 11.1 엔진 공통 계약 테스트 (kv-api testFixtures)

M1에서 만들고 M2·M3 엔진이 그대로 상속한다. 엔진이 바뀌어도 의미가 같다는 것을 보장하는 장치다.

```kotlin
abstract class KvEngineContractTest {
    abstract fun openEngine(dir: Path): KvEngine
    // put/get/덮어쓰기/delete/없는 키 delete/빈 값/scan 경계([from,to) 반열림)
    // writeBatch 순서 의미, close 후 재오픈 시 데이터 유지
}
```

- `kv-api`에 `java-test-fixtures` 플러그인 적용 → 다른 모듈은 `testImplementation(testFixtures(project(":kv-api")))`.

### 11.2 모델 기반 무작위 테스트

- 기준 모델 = `TreeMap<ByteArray, ByteArray>(ByteKeys.COMPARATOR)`.
- 시드 고정 무작위로 put/delete/batch/scan/reopen/merge를 1만 회 섞어 실행, 매 단계 엔진과 모델의 결과 비교. 실패 시 시드를 출력해 재현.

### 11.3 크래시 테스트 (`kill -9`)

```
부모 테스트                                 자식 JVM (CrashWriterMain)
─────────────                               ─────────────────────────
ProcessBuilder로 자식 실행 ───────────────▶ EVERY_WRITE로 key-i 순차 put
                                            put 반환마다 stdout에 "ACK i"
stdout을 읽다 무작위 시점에
process.destroyForcibly()  (SIGKILL)
마지막으로 받은 ACK = k
엔진 재오픈 → key-0..key-k 전부 존재 확인
             key-(k+1)은 있어도 없어도 됨 (쓰는 중이었음)
```

- 자식 실행 classpath는 Gradle이 `crash.child.classpath` 시스템 속성으로 넘긴다. 테스트 워커의 `java.class.path`는 전체 classpath를 담는다는 보장이 없다.
- 검증 기준은 "ACK한 키가 모두 있다"보다 강하게 잡는다. 남은 키는 **빈틈없는 앞부분 0..m (m ≥ 마지막 ACK)**이어야 하고, 배치 모드는 키 수가 배치 크기의 배수여야 한다(배치가 통째로 남거나 사라짐).
- 시나리오: EVERY_WRITE 단일 put 50회, 배치 20회, MANUAL 20회. 세그먼트 4 KiB로 rollover 도중 크래시도 포함한다. 로드맵 M1 완료 조건이 이 테스트다.

#### 실행해서 관찰한 것 (macOS, APFS, 2026-10-14)

| 관찰 | 의미 |
|---|---|
| 90라운드 모두 STRICT 복구로도 열림 = 잘린 프레임 0회 | 커널은 진행 중인 `write()`를 끝낸 뒤 SIGKILL을 처리한다. 프로세스 크래시는 프레임을 자르지 않는다. 4 MiB 값으로 30회 실험해도 같았다 |
| ACK보다 프레임이 더 많이 남은 라운드가 여러 번 | `write()`가 끝나고 ACK를 출력하기 전에 죽은 경우. "빈틈없는 앞부분" 기준이 필요한 이유 |
| MANUAL도 전부 통과 | fsync를 안 해도 OS 페이지 캐시에 넘어간 데이터는 프로세스가 죽어도 남는다. 차이는 전원 차단에서만 난다 |
| EVERY_WRITE 50라운드 약 40초, MANUAL 20라운드 약 2초 | 라운드당 쓰기 수가 같은 분포인데 차이가 크다. 측정 결과 macOS JDK의 `force()`가 `F_FULLFSYNC`라 쓰기당 약 3.9 ms가 든다 ([측정 결과](../measurements/m1-log-engine.md) §1) |

잘린 꼬리는 전원 차단이나 OS 크래시에서 생긴다(Linux는 큰 쓰기 도중 치명적 시그널에서 부분 쓰기를 남길 수 있다고 알려져 있으나 여기서 검증하지 않았다). 그래서 복구 경로는 `LogEngineTest`에서 바이트를 직접 잘라 검증한다.

### 11.4 손상·merge 크래시 테스트

#### merge 테스트에서 관찰한 것 (2026-10-14)

| 관찰 | 대응 |
|---|---|
| 무작위 시점 `kill -9`는 merge 구간(약 1ms)에 60회 중 0회 걸림 | 자식이 merge 직전 `MERGING`을 출력하고 부모가 받는 즉시 죽인다 → 30회 중 16회가 merge 도중 사망, 전부 통과 |
| `Merger.recover`를 빼면 FOOTER_WRITTEN · COMMITTED · OLD_LOG_DELETED · 깨진 footer 4개 테스트가 실패 | 크래시 테스트가 복구 경로를 실제로 검증한다는 확인 (돌연변이 테스트) |
| 무작위 모델 테스트가 EVERY_WRITE로 44초 | 의미 검증이 목적이라 MANUAL로 바꿔 13초 |


- 마지막 세그먼트 끝에 임의 바이트를 붙이거나 잘라낸 뒤 재오픈 → 이전 데이터 유지, `truncatedBytes > 0`.
- 중간 세그먼트를 손상 → `CorruptionException`.
- §8.4 표의 각 FaultInjector 지점에서 예외를 던져 크래시를 흉내 낸 뒤 재오픈 → 모델과 데이터 동일.

## 12. 측정해서 기록할 것 (M4 준비)

결과: [docs/measurements/m1-log-engine.md](../measurements/m1-log-engine.md). EVERY_WRITE는 macOS에서 건당 약 3.9 ms라 100만 건 대신 1만 건으로 쟀다.

| 항목 | 방법 |
|---|---|
| put 처리량: EVERY_WRITE vs MANUAL | 100만 건, 값 100 B |
| 쓰기 증폭 (merge 전/후) | `EngineStats.writeAmplification` |
| 재시작 시간 vs 데이터 크기 | 100 MiB, 1 GiB |
| scan 지연 vs 전체 키 수 (범위 크기 고정 100건) | 키 1만 / 10만 / 100만 |
| 키 100만 개일 때 힙 사용량 | GC 후 `Runtime.totalMemory - freeMemory` |

## 13. 구현 순서

1. ✅ `storage-common`: FrameCodec → LogRecordCodec → AppendOnlyFile (M0 테스트까지)
2. ✅ `kv-api` testFixtures: `KvEngineContractTest`
3. ✅ `engine-log`: Keydir → 단일 세그먼트 put/get/delete → 복구 → 계약 테스트 통과
4. ✅ rollover → 다중 세그먼트 복구 (`segmentSizeBytes = 1`로 계약 테스트를 한 번 더 돌려 검증)
5. ✅ writeBatch
6. ✅ 크래시 테스트 (`kill -9`)
7. ✅ merge → merge 크래시 테스트 (FaultInjector 5지점 + `kill -9` merge 모드) + 모델 기반 무작위 테스트
8. ✅ 측정 (§12) → [결과](../measurements/m1-log-engine.md)
