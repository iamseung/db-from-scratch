# M6 · sql 상세 설계 (관계형 계층)

> M5와 같은 `KvEngine` 위에 스키마·SQL·인덱스·JOIN을 얹는다. 같은 KV 위인데 스키마가 생기면 무엇이 가능해지는지가 이 단계의 질문이다.
> 참고: CMU 15-445 (Query Execution, Query Planning), SQLite 아키텍처, CockroachDB 행 → KV 인코딩.

## 1. 배우려는 것

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 스키마가 있으면 무엇이 쉬워지는가 | §3 행 인코딩(필드 이름을 저장하지 않음), §6 타입 검사를 쓰기 시점에 |
| SQL 문장이 어떻게 KV 연산이 되는가 | §4 파서 → §5 계획 → §6 실행 |
| 인덱스가 있을 때와 없을 때 플랜은 어떻게 달라지는가 | §5 접근 경로 선택, `EXPLAIN` |
| JOIN은 실제로 무엇을 하는가 | §5.3 nested loop / index nested loop |
| UPDATE가 자기가 바꾼 행을 다시 만나면 | §6.3 Halloween 문제 |

로드맵 완료 조건: `CREATE TABLE`, `INSERT`, `SELECT … WHERE … JOIN … ORDER BY`, `CREATE INDEX` 동작 + `EXPLAIN`.

## 2. 범위

| 한다 | 하지 않는다 (이유) |
|---|---|
| 타입 `INT`(64비트), `TEXT`, `BOOL`, `NULL` | 실수·날짜, 타입 변환 규칙 |
| `ALTER TABLE ADD COLUMN` (M8에서 추가, 모든 행 재작성) | `DROP`, 열 삭제·변경 |
| 단일 열 기본 키(INT 또는 TEXT), `NOT NULL` | 복합 기본 키, 외래 키, CHECK |
| `CREATE TABLE`, `CREATE INDEX`(단일 열), `INSERT`(여러 행), `SELECT`, `UPDATE`, `DELETE`, `EXPLAIN` | |
| `WHERE`: 비교 6종, `AND`/`OR`/`NOT`, `IS [NOT] NULL`, 산술 `+ - *` | 서브쿼리, `IN`, `LIKE`, 집계 함수, `GROUP BY` |
| 두 테이블 `JOIN … ON a = b` (내부 조인) | 3개 이상 조인 순서 최적화, 외부 조인 |
| `ORDER BY` 한 열, `LIMIT` | |
| 규칙 기반 플래너 | 비용 기반 옵티마이저, 통계 |
| 문장 단위 원자성 (문장 하나 = `writeBatch` 하나) | |
| `BEGIN` / `COMMIT` / `ROLLBACK` (M7 이후 추가, §10) | 세이브포인트, 격리 수준 선택 |

## 3. KV 레이아웃

```
s/next                          → 다음 id (u32)
s/t/<테이블 이름>                → 테이블 정의 (아래)
r/<테이블 id u32>/<기본 키 인코딩> → 행
i/<인덱스 id u32>/<값 인코딩>/<기본 키 인코딩> → (빈 값)
```

**테이블 정의**: `tableId u32 | 열 수 u16 | (이름 길이 u16, 이름, 타입 u8, notNull u8) × n | pk 열 번호 u16 | 인덱스 수 u16 | (indexId u32, 열 번호 u16, 이름 길이 u16, 이름) × m`

**행 인코딩**: `| null 비트맵 ceil(n/8) B | 열 값들 (null이 아닌 것만, 정의 순서) |` — INT 8 B, BOOL 1 B, TEXT `u32 길이 + UTF-8`.

- 문서 모델(M5)은 문서마다 필드 이름을 텍스트로 저장했다. 관계형은 스키마가 이름·순서·타입을 알고 있어 **값만** 저장한다. M8에서 같은 데이터의 크기를 비교한다.
- **정렬 보존 키 인코딩**(기본 키, 인덱스 값): `NULL 0x01 < 값 0x02` 태그 + INT는 부호 비트를 뒤집은 빅엔디언 8 B, TEXT는 M5와 같은 0x00 이스케이프 + `0x00 0x01` 종결, BOOL 1 B. 그래서 `WHERE id BETWEEN` 같은 조건이 키 범위 스캔이 된다.

## 4. 파서

```
문장   := create_table | create_index | insert | select | update | delete | EXPLAIN select
select := SELECT (* | expr [, expr]*) FROM 테이블 [별칭] [JOIN 테이블 [별칭] ON expr]
          [WHERE expr] [ORDER BY expr [ASC|DESC]] [LIMIT 정수]
expr   := or ;  or := and (OR and)* ;  and := not (AND not)* ;  not := NOT not | cmp
cmp    := sum [(= | != | <> | < | <= | > | >=) sum | IS [NOT] NULL]
sum    := term ((+|-) term)* ;  term := unary (* unary)* ;  unary := - unary | primary
primary:= 정수 | '문자열' | TRUE | FALSE | NULL | 이름[.이름] | ( expr )
```

- 손으로 쓴 렉서 + 재귀 하강 파서. 우선순위가 문법 규칙의 층으로 표현된다(M5 JSON 파서와 같은 방식).
- 키워드는 대소문자 무시, 식별자는 소문자로 정규화.
- 오류는 위치와 기대한 토큰을 담은 `SqlException`.

## 5. 플래너 (규칙 기반)

### 5.1 단일 테이블 접근 경로

WHERE를 AND로 평평하게 펴고, 아래 순서로 첫 번째 맞는 것을 고른다. 고르지 않은 조건은 그대로 `Filter`로 남긴다.

| 순서 | 조건 | 접근 경로 | KV 연산 |
|---|---|---|---|
| 1 | `pk = 상수` | `PkLookup` | get 1회 |
| 2 | `pk` 범위 (`<`, `<=`, `>`, `>=`) | `PkRangeScan` | scan(범위) |
| 3 | 인덱스 열 `= 상수` | `IndexScan` | scan(값 접두사) → 행마다 get |
| 4 | 인덱스 열 범위 | `IndexScan` | scan(범위) → 행마다 get |
| 5 | 그 외 | `SeqScan` | scan(`r/<테이블>/` 전체) |

### 5.2 연산자 (Volcano 반복자)

`Iterator<Row>`를 감싸는 트리. 각 연산자는 아래 연산자에서 행을 하나씩 당겨 온다.

`SeqScan`, `PkLookup`, `PkRangeScan`, `IndexScan`, `Filter`, `NestedLoopJoin`, `IndexNestedLoopJoin`, `Sort`, `Limit`, `Project`

### 5.3 JOIN

```
SELECT … FROM a JOIN b ON a.x = b.y WHERE …
```

1. WHERE 중 한쪽 테이블만 참조하는 조건은 그 테이블의 스캔으로 내린다(predicate pushdown).
2. 안쪽(b)의 `y`가 기본 키거나 인덱스 열이면 **IndexNestedLoopJoin**: a의 행마다 b를 `y = a.x`로 찾는다.
3. 아니면 **NestedLoopJoin**: a의 행마다 b 전체(필터 후)를 훑는다. b는 한 번 읽어 메모리에 둔다.
4. 바깥·안쪽 순서는 쓴 순서 그대로다(조인 순서 최적화 없음). 단, 안쪽에만 인덱스가 없고 바깥쪽 열에 인덱스가 있으면 둘을 바꾼다 — 이 한 규칙만 둔다.

### 5.4 EXPLAIN

플랜 트리를 들여쓰기 문자열로 돌려준다. 예:

```
Project [u.name, o.amount]
  IndexNestedLoopJoin o.user_id = u.id
    Filter o.amount > 100
      SeqScan orders o
    PkLookup users u
```

## 6. 실행

### 6.1 타입과 NULL

- 비교·산술에 NULL이 끼면 결과는 NULL(3값 논리). `WHERE`는 TRUE인 행만 남긴다. `NULL = NULL`은 NULL이라 걸러진다. `IS NULL`만 NULL을 찾는다.
- INT와 TEXT를 비교하면 오류(`SqlException`). 문서 모델(M5)은 같은 상황을 "false"로 조용히 넘겼다. 스키마가 있으니 **쓰기·질의 시점에 틀렸다고 말할 수 있다**.
- `INSERT`: 열 수·타입·`NOT NULL`·기본 키 중복을 검사한다. 하나라도 어기면 그 문장 전체가 아무것도 쓰지 않는다.

### 6.2 쓰기와 원자성

문장 하나가 만드는 모든 KV 변경(행 + 인덱스 항목)을 모아 `writeBatch` **한 번**으로 쓴다. 세 엔진 모두 배치가 원자적이므로 문장이 원자적이다. 여러 문장에 걸친 원자성은 M7.

### 6.3 Halloween 문제

`UPDATE t SET age = age + 1 WHERE age > 10`을 `age` 인덱스로 실행하면서 바꾼 행을 바로 인덱스에 쓰면, 스캔이 앞으로 나아가다 **방금 옮긴 행을 다시 만나** 또 갱신할 수 있다(1976년 IBM System R에서 발견). 대응: 갱신 대상의 기본 키를 **먼저 모두 모은 뒤** 변경을 계산해 한 배치로 쓴다. 우리 엔진의 scan은 생성 시점 스냅샷(LSM·Log)이나 복사(B+tree)라 이 문제가 드러나지 않지만, 규칙으로 고정해 둔다. 테스트로 각 행이 한 번만 바뀌는지 확인한다.

### 6.4 기본 키 변경

`UPDATE`로 기본 키 열을 바꾸는 것은 "옛 키 삭제 + 새 키 삽입"이다. 이번 범위에서는 허용하지 않고 `SqlException`.

## 7. API

```kotlin
class Database(engine: KvEngine) {
    fun execute(sql: String): Result
}

sealed interface Result {
    data class Rows(val columns: List<String>, val rows: List<List<Value>>) : Result
    data class Affected(val count: Int) : Result
    data class Plan(val text: String) : Result
    data object Ok : Result
}
```

`sql` 모듈에 REPL(`./gradlew :sql:run`, 엔진 선택 `--engine=log|lsm|btree`)도 둔다.

## 8. 테스트

- `LexerTest`, `ParserTest`: 우선순위(`a OR b AND c`), 별칭, 오류 위치.
- `SqlContractTest`(추상) — 세 엔진 각각:
  - 완료 조건 문장들, `EXPLAIN`의 접근 경로 선택(§5.1 표의 각 행)
  - NULL 3값 논리, 타입 오류, 제약 위반 시 문장 전체 무효(여러 행 INSERT 중 중복 하나)
  - JOIN: 인덱스 있을 때·없을 때 결과 동일, 플랜은 다름
  - Halloween: 인덱스 열을 증가시키는 UPDATE 후 모든 행이 정확히 +1
  - 재오픈 후 스키마·데이터·인덱스 유지
- **차분 무작위 테스트**: 무작위 테이블 2개에 무작위 INSERT/UPDATE/DELETE를 하고, 무작위 SELECT(WHERE·JOIN·ORDER BY·LIMIT)의 결과를 Kotlin 컬렉션으로 계산한 기대값과 비교. 같은 질의를 인덱스 있는 스키마와 없는 스키마에 던져 결과가 같은지도 본다.
- 돌연변이 검증 (2026-10-25 실행, 세 엔진 각각):

| 넣은 버그 | 잡은 테스트 | 해석 |
|---|---|---|
| (a) UPDATE에서 옛 인덱스 항목 삭제 누락 | 무작위 차분 (엔진마다 1개) | Halloween 테스트는 **못 잡았다**: 옛 항목으로 찾은 행을 마지막 재검사 필터가 걸러 냈다. 무작위 테스트는 같은 행이 두 번 나오는 것으로 잡았다 |
| (b) `NULL = NULL`을 참으로 | 4종 × 3엔진 = 12개 | |
| (c1) 인덱스 범위 경계 실수 (초과 → 이상), 재검사 필터 있음 | **0개 — 의도대로** | 플랜 최상단에서 WHERE 전체를 다시 검사하는 안전망이 경계 실수를 덮는다 |
| (c2) 같은 실수 + 재검사 필터 제거 | 3종 × 3엔진 = 9개 | 안전망을 걷으면 바로 드러난다 |
| (d) 인덱스 조인에서 안쪽 조건 무시 | **0개** | (c1)과 같은 안전망이 덮는다 |

  **대가**: 재검사 필터 덕분에 접근 경로·pushdown의 실수가 **틀린 결과**로 이어지지 않지만, 같은 이유로 그 실수를 결과 비교 테스트로는 찾을 수 없다(성능만 나빠진다). pushdown이 실제로 일하는지는 `EXPLAIN` 테스트와 측정으로만 확인된다.

## 9. 구현 순서

1. ✅ `Value`·타입, 행 인코딩, 정렬 보존 키 인코딩 + 테스트
2. ✅ 렉서·파서·AST + 테스트
3. ✅ 카탈로그 (`CREATE TABLE`/`CREATE INDEX`)
4. ✅ 쓰기: `INSERT`/`UPDATE`/`DELETE` (+ 인덱스 유지, 원자성)
5. ✅ 플래너·연산자: 단일 테이블 접근 경로, Filter, Project, Sort, Limit, `EXPLAIN`
6. ✅ JOIN
7. ✅ 계약 테스트 3엔진, 차분 무작위 테스트, 돌연변이 검증
8. ✅ REPL (`./gradlew :sql:run --args="--engine=lsm" -q --console=plain`)

## 10. 트랜잭션 연결 — BEGIN · COMMIT · ROLLBACK (M7 이후 추가, 2026-10-26)

M7의 MVCC(`TxnStore`)를 SQL에 연결했다.

### 10.1 구조

```
Database(engine, transactional)
 ├─ Session            ← 연결 하나. 트랜잭션 상태(열린 Txn)를 가진다
 │    BEGIN    → txn = store.begin()
 │    문장     → Executor(TxnKvView(txn)).execute(문장)     (트랜잭션 안)
 │              store.run { Executor(TxnKvView(it)).execute(문장) }   (트랜잭션 밖: 자동 커밋, 충돌 시 재시도)
 │    COMMIT   → txn.commit()  (쓰기 충돌이면 SqlException "직렬화 실패", 트랜잭션 버림)
 │    ROLLBACK → txn.rollback()
 └─ Executor           ← 기존 실행 코드 그대로. KvEngine만 본다
```

- **기존 실행 코드를 고치지 않았다.** `Executor`는 `KvEngine`에만 의존하므로, 트랜잭션을 `KvEngine`처럼 보이게 하는 `TxnKvView`를 넘기면 된다. 읽기는 트랜잭션 스냅샷 + 자기 쓰기, 쓰기는 커밋까지 버퍼링된다. 카탈로그도 같은 뷰로 읽고 쓰므로 **DDL도 트랜잭션 안에서 되돌려진다**.
- **두 모드**: `transactional = false`(기본, M6 그대로)와 `true`(MVCC). MVCC는 키를 버전 형식으로 저장하므로 두 모드의 데이터 파일은 서로 열 수 없다. M6·M8 측정은 비트랜잭션 모드 값이다. REPL은 트랜잭션 모드가 기본이다(`--mode=raw`로 바꿈).

### 10.2 의미

| 상황 | 동작 |
|---|---|
| 트랜잭션 안의 문장이 실패 (제약 위반 등) | 그 문장만 무효, 트랜잭션은 열린 채. 문장이 KV 변경을 모아 마지막에 한 번에 쓰므로 실패한 문장의 일부가 남지 않는다 (PostgreSQL은 트랜잭션 전체를 실패 상태로 만든다 — 여기서는 MySQL 쪽 동작) |
| 두 트랜잭션이 같은 행을 바꿈 | 나중 COMMIT이 `직렬화 실패`. 그 트랜잭션의 모든 변경이 버려지고, 애플리케이션이 처음부터 다시 실행한다 |
| 트랜잭션 밖의 문장 | 문장마다 자동 커밋. 충돌이면 새 스냅샷으로 **문장을 자동 재실행**한다 (문장 하나는 다시 실행해도 안전) |
| 격리 수준 | 기본 스냅샷 격리(write skew 허용). `BEGIN ISOLATION LEVEL SERIALIZABLE`이면 SSI로 write skew까지 막는다 ([m7 §11](m7-transactions.md)). `Database(…, isolation = …)`로 기본값 변경. `SELECT … FOR UPDATE` 없음 |
| 중첩 BEGIN, BEGIN 없는 COMMIT | 오류 |

### 10.3 검증

- 기존 SQL 계약 테스트 11종을 트랜잭션 모드에서 세 엔진으로 다시 돌림 (자동 커밋 경로)
- 새 테스트 7종 × 엔진 3: 커밋 전 비가시성, ROLLBACK(DDL 포함), 트랜잭션 스냅샷, 같은 행 동시 갱신 → 나중 COMMIT 실패, **M8 D5의 "항목 없는 주문"을 트랜잭션으로 막기**, 제어 오류, 세션 8개 SQL 동시 이체(충돌 후 재시도 760 ~ 830회 발생, 합계 보존)
- 돌연변이: (a) 트랜잭션 안 문장을 자동 커밋으로 실행 → 18개 실패, (b) 트랜잭션 뷰가 배치의 첫 연산만 반영 → 51개 실패

### 10.4 CREATE INDEX · ALTER TABLE과 행 쓰기의 경쟁 (문서 계층 연결 때 발견)

`CREATE INDEX`를 하는 트랜잭션과 행을 쓰는 트랜잭션은 서로 다른 키를 쓸 수 있어 스냅샷 격리에서 둘 다 커밋되고, 그 행이 인덱스에서 빠진다(자세한 설명은 [m5-document.md §10.2](m5-document.md)). 대응: 행을 쓰는 문장(INSERT · UPDATE · DELETE)은 테이블 정의 키를 지켜보고, `CREATE INDEX` · `ALTER TABLE`은 테이블 행 범위를 지켜본다(`Txn.watch` / `watchRange`). 테스트 "CREATE INDEX와 동시에 쓴 행이 인덱스에서 빠지지 않는다 - 양방향".
