# db-from-scratch

RDBMS와 NoSQL의 차이를 직접 만들어 보며 이해하기 위한 학습용 데이터베이스입니다.

공통 KV 엔진 하나 위에 **관계형(SQL) 계층**과 **문서(NoSQL) 계층**을 각각 올리고,
그 아래 저장 엔진은 **B+tree**와 **LSM-tree**를 바꿔 끼울 수 있게 만듭니다.
이렇게 해서 "관계형이라서 생기는 차이"와 "저장 구조 때문에 생기는 차이"를 따로 측정합니다.

- 설계 문서: [`docs/design.html`](docs/design.html)
- 상세 설계: [M0 storage-common](docs/detailed/m0-storage-common.md) · [M1 engine-log](docs/detailed/m1-engine-log.md) · [M2 engine-lsm](docs/detailed/m2-engine-lsm.md)
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
- [ ] M1 추가 전용 로그 KV (Bitcask형)
  - [x] 엔진 공통 계약 테스트 (`kv-api` testFixtures)
  - [x] put · get · delete · scan · writeBatch, 복구, rollover
  - [x] `kill -9` 크래시 테스트
  - [x] merge + merge 크래시 테스트, 모델 기반 무작위 테스트
  - [x] 측정 → [결과](docs/measurements/m1-log-engine.md)
- [ ] M2 LSM-tree
  - [x] 상세 설계
- [ ] M3 B+tree
- [ ] M4 비교 실험 1: 저장 엔진 (쓰기·읽기·공간 증폭)
- [ ] M5 문서 계층
- [ ] M6 관계형 계층 (SQL 파서, planner, executor, JOIN)
- [ ] M7 트랜잭션 (MVCC 스냅샷 격리)
- [ ] M8 비교 실험 2: 데이터 모델

## 빌드

```bash
./gradlew test
```

## 측정

```bash
./gradlew :bench:run
```
