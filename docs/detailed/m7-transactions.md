# M7 · txn 상세 설계 (MVCC 스냅샷 격리)

> `KvEngine` 위에 여러 키에 걸친 트랜잭션을 얹는다. 키마다 커밋 시각이 붙은 여러 버전을 두고, 트랜잭션은 시작 시점의 스냅샷만 본다.
> 참고: DDIA 7장 "Snapshot Isolation and Repeatable Read", "Write Skew", Berenson et al. "A Critique of ANSI SQL Isolation Levels" (1995), CockroachDB·Percolator의 MVCC 키 인코딩.

## 1. 배우려는 것

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 여러 행을 원자적으로 바꾸는 비용은 무엇인가 | §4 쓰기 집합 버퍼 + 커밋 시 충돌 검사 + 배치 1회 |
| 왜 읽는 쪽이 쓰는 쪽을 막지 않는가 | §3 버전 키 — 읽기는 옛 버전을 본다 |
| lost update는 어떻게 막히는가 | §4.2 first-committer-wins |
| 스냅샷 격리가 막지 못하는 이상 현상은 | §6 write skew 재현 |
| 옛 버전은 언제 지울 수 있는가 | §5 GC |
| 문서 하나에 다 넣으면(임베딩) 왜 트랜잭션이 필요 없어지는가 | §7 (M8에서 측정) |

로드맵 완료 조건: **동시 이체 시나리오에서 잔액 합계 보존**, **write skew 사례를 재현하고 설명**.

## 2. 범위

| 한다 | 하지 않는다 (이유) |
|---|---|
| 스냅샷 격리(SI): 시작 시점 스냅샷 읽기, 쓰기-쓰기 충돌 시 나중 커밋 실패 | 직렬화 가능 격리(SSI) — write skew를 막는 읽기 집합 추적. 재현만 하고 막지 않는다 |
| 트랜잭션 API(get/put/delete/scan/commit/rollback) + 재시도 헬퍼 | SQL `BEGIN`/`COMMIT`, 문서 계층 다중 문서 트랜잭션 연결 (§8) |
| 버전 GC | 장기 실행 트랜잭션 제한 |
| 자동 커밋 어댑터 `MvccKvEngine : KvEngine` | 분산 트랜잭션, 2PC |

## 3. 버전 키 인코딩

```
사용자 키 K, 커밋 시각 ts(u64)

저장 키   = "v" | escape(K) | 0x00 0x01 | (~ts) u64 빅엔디언
저장 값   = 0x01 | 값     (PUT)
          | 0x02          (삭제 표시)
메타      = "t/last" → 마지막으로 커밋된 ts (u64)
```

- `escape`: K의 0x00을 0x00 0xFF로 바꾼다(M5·M6과 같은 규칙). 종결자 `0x00 0x01` 덕분에 K가 다른 키의 접두사여도 버전들이 섞이지 않는다.
- **`~ts`(비트 반전)**: 같은 K의 버전들이 **최신이 먼저** 오도록 정렬된다. 스냅샷 `readTs`에서 K를 읽으려면 `(K, readTs)` 위치에서 scan을 시작해 첫 항목 하나만 보면 된다(그 항목이 `ts ≤ readTs`인 가장 최신 버전).
- 사용자 키 순서도 보존된다: 저장 키의 앞부분이 escape(K)이므로 사용자 키 범위 scan이 저장 키 범위 scan이 된다.

## 4. 트랜잭션

### 4.1 시작과 읽기

```
begin():  readTs = lastCommittedTs (메모리), 활성 트랜잭션 목록에 등록
get(K):   쓰기 집합에 있으면 그 값 (자기 쓰기 읽기)
          아니면 저장 키 범위 [(K, readTs), (K 접두사 끝)) 의 첫 항목 → PUT이면 값, 삭제 표시면 없음
scan:     [from, to) 의 모든 버전을 scan하며 사용자 키마다 ts ≤ readTs 인 첫 버전만 취하고, 쓰기 집합과 병합
```

### 4.2 커밋 (first-committer-wins)

```
commit():   (커밋은 하나씩: TxnStore 수준 잠금)
  1. 쓰기 집합의 각 K에 대해: readTs보다 나중에 커밋된 버전이 있으면 → WriteConflictException (rollback)
     = 저장 키 범위 [(K, ∞), (K, readTs)) 에 항목이 있는가
  2. commitTs = lastCommittedTs + 1
  3. writeBatch( 각 K의 (K, commitTs) 버전 + "t/last" = commitTs )   ← 원자적
  4. lastCommittedTs = commitTs
```

- 3의 배치가 원자적(세 엔진 모두)이므로 트랜잭션의 쓰기는 전부 보이거나 전부 안 보인다. 크래시 후에는 `t/last`를 읽어 시계를 복원한다.
- 읽기 전용 트랜잭션은 1~3이 없다 — 충돌 검사도 쓰기도 없다. **읽기는 절대 기다리지 않고 실패하지도 않는다.**
- **lost update 방지**: T1, T2가 같은 잔액을 읽고 둘 다 고쳐 쓰면, 먼저 커밋한 쪽이 이기고 나중 쪽은 1에서 실패해 재시도한다.

### 4.3 재시도

```kotlin
fun <T> TxnStore.run(maxAttempts: Int = 10, block: (Txn) -> T): T
```

`WriteConflictException`이면 새 스냅샷으로 처음부터 다시 실행한다. block은 부수 효과 없이 다시 실행해도 되는 코드여야 한다.

## 5. GC

```
minReadTs = 활성 트랜잭션 readTs 중 최솟값 (없으면 lastCommittedTs)
사용자 키마다 버전을 최신부터 보며:
  ts > minReadTs 인 버전은 누군가 볼 수 있으니 남긴다
  ts ≤ minReadTs 인 첫 버전은 minReadTs 스냅샷이 보는 버전이라 남긴다 — 단 그것이 삭제 표시면 지운다(볼 사람에게도 "없음"과 같다)
  그보다 오래된 버전은 아무도 볼 수 없으니 지운다
```

GC는 버전 공간 전체를 scan한다(O(전체 버전)). 지울 키를 모아 배치 한 번으로 지운다.

## 6. write skew (재현만 한다)

당직 의사 예 (DDIA 7장): "당직은 최소 한 명". Alice와 Bob이 동시에 "다른 사람이 당직이니 나는 빠진다"를 실행한다.

```
T1: 당직 수 읽기 = 2 → alice.oncall = false
T2: 당직 수 읽기 = 2 → bob.oncall   = false
둘 다 커밋 성공 (서로 다른 키를 썼으므로 쓰기-쓰기 충돌 없음) → 당직 0명
```

스냅샷 격리는 **같은 키**에 대한 경쟁만 잡는다. 서로 다른 키를 쓰되 서로가 읽은 데이터에 의존하는 경우는 못 잡는다. 막으려면 읽기 집합까지 추적하는 직렬화 가능 격리(SSI, PostgreSQL SERIALIZABLE)나, 읽은 행을 명시적으로 잠그는(`SELECT … FOR UPDATE`) 방법이 필요하다. 테스트로 이 이상 현상이 실제로 일어나는 것을 보이고, "읽은 키도 쓰기 집합에 넣어(materialize the conflict) 충돌로 만드는" 우회로가 막는 것도 보인다.

## 7. 자동 커밋 어댑터

```kotlin
class MvccKvEngine(store: TxnStore) : KvEngine   // 연산 하나 = 트랜잭션 하나
```

이 어댑터로 M1에서 만든 계약·무작위·크래시 하네스를 MVCC 계층에 그대로 적용한다. 세 저장 엔진 각각 위에서.

## 8. SQL·문서 계층과의 연결

> **SQL과 문서 계층 모두 연결했다 (2026-10-26)**: SQL `BEGIN` / `COMMIT` / `ROLLBACK` — [m6-sql.md §10](m6-sql.md), 문서 `transaction { }` / `begin()` — [m5-document.md §10](m5-document.md).
> 이때 **읽기 검증**(`Txn.watch`, `Txn.watchRange`)을 추가했다. 스냅샷 격리가 놓치는 "인덱스 생성 ↔ 문서·행 쓰기" 경쟁을 막기 위해서다. 등록한 읽기만 커밋 때 검증하므로 SSI 전체는 아니다(§6 write skew 일반은 여전히 막지 않는다).

(처음 계획)

`Database`와 `DocumentStore`는 `KvEngine`에 의존한다. `Txn`이 KvEngine과 같은 연산을 제공하므로, 문장마다 Txn을 열고 닫게 바꾸면 SQL `BEGIN … COMMIT`이 된다. M8에서 필요하면 문서 계층의 다중 문서 원자성 비교용으로만 연결한다.

## 9. 테스트

- `VersionKeyTest`: 같은 K의 버전이 최신순, 다른 K와 섞이지 않음(접두사 키, 0x00 포함 키).
- `TxnTest`: 스냅샷 읽기(커밋 후 시작한 트랜잭션만 봄), 자기 쓰기 읽기, rollback, lost update → 충돌, 읽기 전용은 충돌 없음, 재시작 후 시계·데이터 복원.
- **동시 이체**: 스레드 8개 × 이체 200건, 계좌 20개, 재시도 헬퍼. 끝나고 잔액 합계 = 처음 합계, 모든 잔액 ≥ 0, 같은 스냅샷에서 합계를 읽는 읽기 트랜잭션들도 항상 처음 합계를 본다(일관된 스냅샷).
- **write skew 재현** + 충돌 materialize로 막기.
- GC: 활성 트랜잭션이 보는 버전은 남고, 아무도 못 보는 버전·삭제 표시는 지워진다. GC 후에도 모든 스냅샷의 읽기 결과가 같다.
- 하네스: `MvccKvEngine`으로 계약·무작위(유지 작업 = GC)·크래시를 세 엔진 위에서.
- 돌연변이: (a) 충돌 검사 생략 → 이체 합계 깨짐 / (b) 버전 선택에서 `ts ≤ readTs` 대신 최신 버전 → 스냅샷 테스트 / (c) GC가 활성 트랜잭션이 보는 버전까지 지움.

## 9.1 검증 결과 (2026-10-25)

- 테스트: 트랜잭션 의미 8종 × 엔진 3, MVCC 어댑터로 계약 13종 × 엔진 3, 무작위(GC 포함), kill -9 크래시 4종 — 모두 통과.
- 돌연변이:

| 넣은 버그 | 잡은 테스트 |
|---|---|
| (a) 커밋 시 충돌 검사 생략 | lost update, write skew materialize, **동시 이체(합계 깨짐)** — 엔진 3종 모두 (9개) |
| (b) 스냅샷 대신 항상 최신 버전 읽기 | 스냅샷, 재시작 시계, GC 결과 불변, 동시 이체(스냅샷 합계) — 12개 |
| (c) GC가 활성 트랜잭션의 스냅샷을 무시 | GC 테스트 — 3개 |

- 측정: [docs/measurements/m7-transactions.md](../measurements/m7-transactions.md). GC로 지운 버전이 저장 엔진에서 바로 사라지지 않는 것(LSM tombstone, B+tree 빈 리프)을 확인했다.

## 10. 구현 순서

1. ✅ 버전 키 인코딩 + 테스트
2. ✅ `TxnStore`/`Txn`: begin, get, put/delete(버퍼), scan, commit(충돌 검사), rollback, 시계 복원
3. ✅ 재시도 헬퍼, GC
4. ✅ `MvccKvEngine` + 하네스 3종 × 엔진 3종
5. ✅ 동시 이체, write skew, 돌연변이 검증
6. ✅ 측정: 트랜잭션 크기별 커밋 비용, 버전 누적에 따른 읽기 비용, GC 효과
