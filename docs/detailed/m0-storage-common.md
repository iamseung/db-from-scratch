# M0 · storage-common 상세 설계

> 범위: 레코드 직렬화, 체크섬, 추가 전용 파일, fsync 정책.
> 이 모듈은 M1(로그 KV)과 M2(LSM의 WAL)가 그대로 재사용한다. B+tree(M3)는 WAL 부분만 재사용한다.

## 1. 목표와 비목표

| 목표 | 비목표 |
|---|---|
| 바이트 배열 ↔ 레코드 변환 규칙을 한 곳에 고정 | 압축 |
| 부분 쓰기(torn write)·비트 손상을 읽을 때 감지 | 손상된 데이터 복원 (감지만 한다) |
| "어디까지가 디스크에 확정됐는가"를 코드로 명시 | 비동기 I/O, io_uring |
| 엔진이 공유하는 I/O 카운터(`EngineStats`) 제공 | 멀티 프로세스 동시 접근 (LOCK 파일로 차단만) |

## 2. 패키지 구성

```
storage-common/src/main/kotlin/dev/mydb/storage/
├── FrameSource.kt      // 프레임을 읽어 올 바이트 저장소 (메모리 배열 / 파일)
├── FrameReadResult.kt  // Ok / EndOfFile / Corrupted + CorruptionReason
├── FrameCodec.kt       // 프레임 인코딩/디코딩, 손상 판정
├── LogRecord.kt        // 프레임 안의 payload: PUT / DELETE / BATCH / MERGE_FOOTER
├── LogRecordCodec.kt
├── AppendOnlyFile.kt   // 추가 쓰기 + 위치 지정 읽기 + sync
├── SyncPolicy.kt
├── IoCounters.kt       // 읽기/쓰기 바이트 누적 → EngineStats 재료
├── DirectoryLock.kt    // LOCK 파일 + FileChannel.tryLock
└── CorruptionException.kt
```

`kv-api` 의존성은 `IoCounters` → `EngineStats` 변환을 구현할 때 추가한다.

## 3. 프레임 포맷 (모든 로그 파일의 최소 단위)

프레임은 "이 바이트들이 온전히 쓰였는가"만 책임진다. 내용의 의미는 모른다.

```
offset  size  field
0       4     crc32c   (u32, big-endian)  ← length + payload 를 덮는다
4       4     length   (u32, big-endian)  ← payload 바이트 수
8       n     payload
```

- 헤더 8바이트 고정. 프레임 전체 크기 = `8 + length`.
- 체크섬은 JDK 내장 `java.util.zip.CRC32C` 사용 (하드웨어 가속, 외부 의존성 없음).
- CRC가 `length`까지 덮는 이유: length 필드 자체가 깨지면 엉뚱한 범위를 payload로 읽게 된다. length가 CRC 범위 밖이면 이 경우를 감지할 수 없다.
- `length` 상한: `MAX_PAYLOAD = 32 MiB`. 상한을 넘는 length는 손상으로 간주한다(깨진 length로 수 GB를 할당하려는 사고 방지).

### 3.1 디코딩 결과

```kotlin
sealed interface FrameReadResult {
    class Ok(val payload: ByteArray, val nextOffset: Long) : FrameReadResult
    data object EndOfFile : FrameReadResult                    // 정확히 파일 끝
    data class Corrupted(val offset: Long, val reason: CorruptionReason) : FrameReadResult
}
```

디코딩은 `FrameSource`(크기 + 위치 지정 읽기) 위에서 동작한다. 테스트는 `ByteArrayFrameSource`로, `AppendOnlyFile`은 `FileChannel`로 같은 `FrameCodec.read`를 쓴다.

`Corrupted` 판정 조건:

| 조건 | reason |
|---|---|
| 남은 바이트 < 8 (헤더조차 다 못 씀) | `TRUNCATED_HEADER` |
| `length > MAX_PAYLOAD` 또는 u32 상위 비트가 켜져 Int로 음수 | `LENGTH_OVERFLOW` |
| 남은 바이트 < `8 + length` | `TRUNCATED_PAYLOAD` |
| CRC 불일치 | `CHECKSUM_MISMATCH` |

### 3.2 손상 처리 정책 (결정 사항)

**결정: 복구 시 첫 번째 손상 프레임부터 파일 끝까지를 잘라낸다(truncate).**

- 근거: 추가 전용 파일에서 정상 동작 중 생길 수 있는 손상은 "마지막 쓰기가 중간에 끊김" 하나뿐이다. 이것은 아직 사용자에게 성공을 돌려주지 않은 쓰기라서 버려도 약속을 어기지 않는다.
- 대가: 파일 중간의 비트 손상(디스크 결함)도 똑같이 잘라내므로, 그 뒤의 정상 데이터까지 잃는다. LevelDB의 기본 동작도 비슷하다.
- 안전장치: 잘라낸 바이트 수를 `IoCounters.truncatedBytes`에 기록하고 경고 로그를 남긴다. 잘라낸 위치가 파일 끝에서 `MAX_FRAME`(= 8 + `MAX_PAYLOAD`)보다 멀면 마지막 쓰기 중단으로는 설명되지 않으므로 `CorruptionException`을 던지는 **strict 모드**를 옵션으로 둔다. (`RecoveryMode.TOLERATE_TAIL` 기본, `RecoveryMode.STRICT`)

## 4. 레코드 포맷 (프레임의 payload)

```
type u8 이후 타입별 본문

PUT (0x01)           | keyLen u32 | valLen u32 | key | value |
DELETE (0x02)        | keyLen u32 | key |
BATCH (0x03)         | count u32 | (PUT 또는 DELETE 본문, type 바이트 포함) × count |
MERGE_FOOTER (0x7F)  | entryCount u64 |        ← M1 merge 완료 표식
```

- 키 최대 64 KiB, 값 최대 16 MiB. 초과하면 `put` 시점에 `IllegalArgumentException`.
- **BATCH가 원자성을 주는 방식**: 여러 연산을 프레임 하나에 넣는다. 프레임은 CRC로 "전부 있거나 전부 없거나"가 판정되므로 배치도 자동으로 원자적이다. 별도의 BEGIN/COMMIT 레코드가 필요 없다.
  - 대가: 배치 크기가 `MAX_PAYLOAD`로 제한된다. 학습용으로 충분하다.
- 미지의 type 바이트는 `CorruptionException` (CRC는 맞는데 해석 불가 = 버전 불일치 또는 버그).

```kotlin
sealed interface LogRecord {
    class Put(override val key: ByteArray, val value: ByteArray) : BatchableRecord   // equals는 내용 비교
    class Delete(override val key: ByteArray) : BatchableRecord
    data class Batch(val ops: List<BatchableRecord>) : LogRecord                    // 빈 배치 금지
    data class MergeFooter(val entryCount: Long) : LogRecord
}

sealed interface BatchableRecord : LogRecord { val key: ByteArray }
```

- 배치 중첩은 런타임 검사가 아니라 **타입으로** 막는다. `Batch.ops`는 `BatchableRecord`(Put/Delete)만 받는다.
- 크기 상한은 생성자에서 검사(`IllegalArgumentException`)하고, 디코딩 중 상한 위반은 생성자에 도달하기 전에 `CorruptionException`으로 바꾼다. 같은 위반이라도 쓰는 쪽은 호출자 버그, 읽는 쪽은 디스크 손상이기 때문이다.
- 디코딩 후 payload에 남는 바이트가 있으면 `CorruptionException`. 배치 count는 "op 하나 최소 5바이트"로 남은 바이트와 대조해 터무니없는 값(수십억 개 할당)을 막는다.

`kv-api`의 `WriteOp`와 별도 타입으로 둔다. `WriteOp`는 엔진 바깥 계약, `LogRecord`는 디스크 포맷이라 바뀌는 이유가 다르다.

## 5. AppendOnlyFile

```kotlin
class AppendOnlyFile private constructor(
    val path: Path,
    private val channel: FileChannel,
    private val counters: IoCounters,
) : AutoCloseable {
    val size: Long                                   // 다음 append 위치

    fun append(payload: ByteArray): Long             // 프레임을 쓰고 프레임 시작 offset 반환
    fun readFrame(offset: Long): FrameReadResult     // 위치 지정 읽기 (channel.read(buf, pos))
    fun frames(from: Long = 0): Sequence<Pair<Long, FrameReadResult>>
    fun truncate(newSize: Long)                      // 복구용
    fun sync()                                       // channel.force(true)

    companion object {
        fun openForAppend(path: Path, counters: IoCounters): AppendOnlyFile
        fun openReadOnly(path: Path, counters: IoCounters): AppendOnlyFile
    }
}
```

설계 포인트:

- 쓰기는 `channel.write(buf, position)` 대신 **append 위치를 직접 관리**한다(`size` 필드). `APPEND` 모드는 OS가 위치를 정해 버려서 반환할 offset을 정확히 알 수 없다.
- 프레임 하나를 `ByteBuffer` 하나로 만들어 `write` 루프로 끝까지 쓴다(`write`는 일부만 쓸 수 있다).
- 읽기는 `channel.read(dst, position)` (pread). 채널의 공유 position을 건드리지 않으므로 나중에 읽기 동시성을 열 때 그대로 쓸 수 있다.

## 6. SyncPolicy와 "확정"의 정의

```kotlin
enum class SyncPolicy { EVERY_WRITE, MANUAL }
```

| 정책 | `put` 반환 시 보장 | 용도 |
|---|---|---|
| `EVERY_WRITE` (기본) | OS 크래시·전원 차단 후에도 남음* | 크래시 테스트, 정확성 기준 |
| `MANUAL` | 프로세스 크래시(`kill -9`)까지만 남음. 전원 차단은 마지막 `flush()` 이후 유실 가능 | 처리량 측정, 그룹 커밋 비교 |

\* **macOS 주의**: Java `force(true)`는 macOS에서 `fsync`로 매핑되는데, macOS의 `fsync`는 디스크 자체 캐시까지 비우지 않는다(`F_FULLFSYNC` 필요, Java에서 직접 호출 불가). 따라서 macOS에서 `EVERY_WRITE`의 실제 보장은 "OS 크래시까지"다. 전원 차단 실험은 Linux에서만 의미가 있다. 이 차이를 README와 측정 결과에 명시한다.

학습 질문으로 남길 것:
- `force(false)`(fdatasync)로 바꿔도 되는가? 추가 쓰기는 파일 크기(메타데이터)를 바꾸는데, Linux `fdatasync`는 데이터를 읽는 데 필요한 메타데이터(크기)는 함께 내린다. → M4에서 두 방식의 지연 차이를 측정.
- 새 파일을 만든 뒤 **디렉터리 엔트리**도 fsync해야 파일 존재 자체가 확정된다. Linux에서는 `FileChannel.open(dir, READ).force(true)`가 동작하고, 지원하지 않는 OS에서는 예외를 삼킨다 → `Fsync.directory(dir)` 헬퍼로 분리.

## 7. IoCounters

```kotlin
class IoCounters {
    val bytesWritten = AtomicLong()      // 프레임 헤더 포함, 디스크에 실제로 쓴 바이트
    val bytesRead = AtomicLong()
    val syncCount = AtomicLong()
    val truncatedBytes = AtomicLong()
}
```

엔진은 사용자 바이트(`key.size + value.size`)를 따로 세고, `EngineStats`를 만들 때 합친다. 쓰기 증폭 = 디스크 쓴 바이트 ÷ 사용자 바이트. M1만 해도 프레임 헤더 + 레코드 헤더(17바이트) 때문에 1보다 크고, merge가 돌면 더 커진다.

## 8. DirectoryLock

- 데이터 디렉터리에 `LOCK` 파일을 만들고 `FileChannel.tryLock()`.
- 실패 시 `IllegalStateException("다른 프로세스가 이 디렉터리를 사용 중입니다: $dir")`.
- 같은 JVM 안에서 두 번 열면 `OverlappingFileLockException`이 나므로 같은 메시지로 바꿔 던진다.

## 9. 테스트 계획

| 테스트 | 검증 내용 |
|---|---|
| `FrameCodecTest` | 왕복 변환, 빈 payload, 상한 길이 |
| `FrameCorruptionTest` | 파일을 1바이트씩 잘라가며(0 ~ 프레임 크기) 매번 `Corrupted` 또는 이전 프레임까지 `Ok` |
| `BitFlipTest` | payload의 임의 비트 하나를 뒤집으면 `checksum-mismatch` |
| `LogRecordCodecTest` | 타입별 바이트 레이아웃·왕복, 모든 길이로 잘라 손상 판정, 중첩·footer 금지, 미지 타입·남는 바이트 거부 |
| `AppendOnlyFileTest` | append가 반환한 offset으로 readFrame 하면 같은 payload, truncate 후 size 일치 |
| `DirectoryLockTest` | 같은 디렉터리 두 번 열기 실패 |

완료 조건 (로드맵 M0): **중간에 잘린 레코드를 체크섬으로 감지해 버린다.** → `FrameCorruptionTest`가 이것을 직접 증명한다.
