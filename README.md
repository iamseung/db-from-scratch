# db-from-scratch

RDBMS와 NoSQL의 차이를 직접 만들어 보며 이해하기 위한 학습용 데이터베이스입니다.

공통 KV 엔진 하나 위에 **관계형(SQL) 계층**과 **문서(NoSQL) 계층**을 각각 올리고,
그 아래 저장 엔진은 **B+tree**와 **LSM-tree**를 바꿔 끼울 수 있게 만듭니다.
이렇게 해서 "관계형이라서 생기는 차이"와 "저장 구조 때문에 생기는 차이"를 따로 측정합니다.

- 설계 문서: [`docs/design.html`](docs/design.html)
- 상세 설계: [M0 storage-common](docs/detailed/m0-storage-common.md) · [M1 engine-log](docs/detailed/m1-engine-log.md) · [M2 engine-lsm](docs/detailed/m2-engine-lsm.md) · [M3 engine-btree](docs/detailed/m3-engine-btree.md) · [M4 비교 실험](docs/detailed/m4-engine-comparison.md) · [M5 document](docs/detailed/m5-document.md) · [M6 sql](docs/detailed/m6-sql.md) · [M7 txn](docs/detailed/m7-transactions.md) · [M8 데이터 모델](docs/detailed/m8-data-models.md)
- 언어: Kotlin (JVM 21), Spring 등 프레임워크 없이, 외부 의존성은 테스트용만

## 구조

```
INTERFACE    REPL / TCP
DATA MODEL   sql (관계형)            | document (문서)
TRANSACTION  txn (MVCC)
KV API       kv-api (KvEngine)        ← 핵심 경계
STORAGE      engine-btree             | engine-lsm
DURABILITY   storage-common (WAL, fsync)
```

## 로드맵

- [x] 프로젝트 골격, `KvEngine` 인터페이스, 정렬 보존 키 인코딩
- [x] M0 바이트와 파일: FrameCodec, LogRecordCodec, AppendOnlyFile, SyncPolicy, DirectoryLock
- [x] M1 추가 전용 로그 KV (Bitcask형)
  - [x] 엔진 공통 계약 테스트 (`kv-api` testFixtures)
  - [x] put · get · delete · scan · writeBatch, 복구, rollover
  - [x] `kill -9` 크래시 테스트
  - [x] merge + merge 크래시 테스트, 모델 기반 무작위 테스트
  - [x] 측정 → [결과](docs/measurements/m1-log-engine.md)
- [x] M2 LSM-tree
  - [x] 상세 설계
  - [x] 구현 (memtable, SSTable, bloom, MANIFEST, size-tiered compaction, Version 고정) + 하네스·돌연변이 검증
  - [x] 측정 → [M1 vs M2 결과](docs/measurements/m2-lsm-vs-log.md)
- [x] M3 B+tree
  - [x] 상세 설계
  - [x] 구현 (슬롯 페이지, no-steal 버퍼 풀, split, WAL + doublewrite 체크포인트) + 하네스·돌연변이 검증
  - [x] 측정 → [세 엔진 비교](docs/measurements/m3-three-engines.md)
- [x] M4 비교 실험 1: 저장 엔진 (쓰기·읽기·공간 증폭) → [RUM 정리](docs/measurements/m4-engine-comparison.md)
- [x] M5 문서 계층 (JSON 컬렉션, 경로 필터, 보조 인덱스, explain) → [측정](docs/measurements/m5-document.md)
- [x] M6 관계형 계층 (SQL 파서, 규칙 기반 planner, Volcano executor, JOIN, EXPLAIN, REPL)
- [x] M7 트랜잭션 (MVCC 스냅샷 격리, write skew 재현, GC) → [측정](docs/measurements/m7-transactions.md)
  - [x] 직렬화 가능 스냅샷 격리 (SSI) — write skew · phantom · 읽기 전용 이상 현상 차단 ([설계](docs/detailed/m7-transactions.md#11-직렬화-가능-스냅샷-격리-ssi--2026-10-26-추가), [비용 측정](docs/measurements/ssi-cost.md))
  - [x] 문서 계층 다중 문서 트랜잭션 (`transaction { }` / `begin()`) ([설계](docs/detailed/m5-document.md#10-트랜잭션-연결-2026-10-26))
  - [x] SQL `BEGIN` / `COMMIT` / `ROLLBACK` 연결 ([설계](docs/detailed/m6-sql.md#10-트랜잭션-연결--begin--commit--rollback-m7-이후-추가-2026-10-26))
- [x] M8 비교 실험 2: 데이터 모델 (관계형 vs 문서형) → [측정](docs/measurements/m8-data-models.md)

## 빌드

```bash
./gradlew test       # 빠른 테스트 (약 20초)
./gradlew slowTest   # kill -9 크래시·모델 기반 무작위 테스트 (약 4분)
```

## SQL REPL

```bash
./gradlew :sql:run --args="--engine=lsm" -q --console=plain
```

기본은 트랜잭션 모드라 `BEGIN; … COMMIT;` / `ROLLBACK;`을 쓸 수 있다 (트랜잭션 중에는 프롬프트가 `mydb*>`). 기본 격리 수준은 스냅샷 격리이고, `BEGIN ISOLATION LEVEL SERIALIZABLE;`이면 SSI로 write skew까지 막는다. `--engine=log|lsm|btree`, `--mode=raw`(M6 비트랜잭션 모드), `--dir=경로`.

## 측정

```bash
./gradlew :bench:run --args=m1   # M1 단독
./gradlew :bench:run --args=compare   # M1 · M2 · M3 비교
./gradlew :bench:run --args=m4        # 저장 엔진 비교 실험
./gradlew :bench:run --args=m5        # 문서 계층
./gradlew :bench:run --args=m7        # 트랜잭션
./gradlew :bench:run --args=m8        # 관계형 vs 문서형
./gradlew :bench:run --args=ssi       # SSI 성능 비용
```
