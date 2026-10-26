# M5 · document 상세 설계 (문서 계층, NoSQL 쪽)

> `KvEngine` 위에 스키마 없는 JSON 문서 컬렉션을 얹는다. 저장 엔진이 무엇이든(M1 Log · M2 LSM · M3 B+tree) 같은 코드로 동작한다.
> 참고: MongoDB 데이터 모델·인덱스 문서, CockroachDB "Mapping Table Data to Key-Value Storage", DDIA 2장.

## 1. 배우려는 것

| 질문 | 어디서 답이 나오는가 |
|---|---|
| 스키마가 없으면 무엇이 쉬워지고 무엇을 앱이 떠안는가 | §6 필터 의미 (타입이 다른 값, 없는 필드) |
| 문서를 KV로 어떻게 바꾸는가 | §3 키 레이아웃 |
| 보조 인덱스는 왜 쓰기를 비싸게 만드는가 | §5 인덱스 유지, §8 측정 |
| 인덱스와 문서가 어긋나지 않게 하려면 | §5 — 문서·인덱스 변경을 `writeBatch` 하나로 |

로드맵 완료 조건: **필드 구조가 서로 다른 문서들을 한 컬렉션에서 필터 조회**.

## 2. 범위

| 한다 | 하지 않는다 (이유) |
|---|---|
| JSON 파서·직렬화 (외부 의존성 없이) | JSON 스키마 검증 |
| 컬렉션 생성·조회, 문서 insert / replace / delete / get | 부분 갱신 연산자(`$set` 등) — replace로 대신 |
| 경로 필터 (`addr.city`) — 같음, 범위, AND | OR, 배열 원소 매칭(`$elemMatch`), 정규식 |
| 단일 필드 보조 인덱스, 인덱스 선택(최대 1개) + 나머지 조건 후처리 | 복합 인덱스, 배열 다중 키 인덱스 |
| `explain()`: 어떤 경로로 찾는지 | 비용 기반 선택 |
| 문서 인코딩: JSON 텍스트 | 바이너리 인코딩 (M8에서 크기·파싱 비용 비교) |

트랜잭션은 문서 하나 단위로만 원자적이다(문서 + 그 문서의 인덱스 항목을 한 배치로). 여러 문서에 걸친 원자성은 M7.

## 3. 키 레이아웃

모든 키는 바이트 사전순으로 정렬되므로, 접두사로 컬렉션·인덱스 단위 범위 스캔이 된다.

```
메타데이터
  m/next                         → 다음 id (u32)                컬렉션·인덱스 id 발급
  m/c/<컬렉션 이름>               → 컬렉션 id (u32)
  m/i/<컬렉션 id>/<필드 경로>      → 인덱스 id (u32)

문서
  d/<컬렉션 id u32>/<문서 id UTF-8> → JSON 텍스트

인덱스 항목
  x/<인덱스 id u32>/<값 인코딩>/<문서 id UTF-8> → (빈 값)
```

- 숫자 id는 빅엔디언 u32라 바이트 순서 = 숫자 순서다.
- 인덱스 항목의 값을 **키 안에** 넣는다. 그래야 "city가 Seoul인 문서"가 키 범위 스캔 한 번이 된다. 같은 값을 가진 문서가 여러 개여도 문서 id가 뒤에 붙어 키가 겹치지 않는다.

### 3.1 정렬 보존 값 인코딩

인덱스 범위 조건(`age >= 20`)이 바이트 범위 스캔이 되려면, 인코딩한 바이트의 순서가 값의 순서와 같아야 한다.

```
타입 태그 1 B: null 0x01 < false 0x02 < true 0x03 < 숫자 0x04 < 문자열 0x05
숫자: IEEE 754 double 8 B. 양수는 부호 비트만 뒤집고, 음수는 모든 비트를 뒤집는다
문자열: UTF-8, 바이트 0x00은 0x00 0xFF로 이스케이프, 끝에 0x00 0x01
```

- 문자열 종결자 `0x00 0x01`: `"ab"`의 인코딩이 `"abc"`보다 앞에 오고, 뒤에 붙는 문서 id와 섞이지 않는다.
- 숫자를 모두 double로 다루므로 2^53보다 큰 정수는 정밀도를 잃는다(JSON 숫자의 일반적 한계).
- 배열·객체 값은 인덱스에 넣지 않는다(§2). 그 문서는 그 인덱스에 항목이 없다.

## 4. API

```kotlin
class DocumentStore(engine: KvEngine) {
    fun createCollection(name: String): Collection
    fun collection(name: String): Collection?
}

class Collection {
    fun insert(id: String, doc: JsonObject)        // 이미 있으면 IllegalStateException
    fun replace(id: String, doc: JsonObject)       // 없으면 삽입
    fun get(id: String): JsonObject?
    fun delete(id: String): Boolean
    fun find(filter: Filter): List<Document>       // Document(id, body), 문서 id 순
    fun createIndex(path: String)                  // 기존 문서로 인덱스를 채운다
    fun explain(filter: Filter): String            // "IXSCAN addr.city [=\"Seoul\"]" / "COLLSCAN"
}

sealed interface Filter {
    data class Eq(val path: String, val value: JsonValue)
    data class Range(val path: String, val lower: Bound?, val upper: Bound?)   // Bound(value, inclusive)
    data class And(val filters: List<Filter>)
    data class Exists(val path: String)
}
```

## 5. 쓰기와 인덱스 유지

```
replace(id, doc):
  old = get(id)
  ops = [Put(d/…/id, json(doc))]
  for 인덱스 in 이 컬렉션의 인덱스:
     oldKey = old의 그 필드 값으로 만든 인덱스 키 (있으면)
     newKey = doc의 그 필드 값으로 만든 인덱스 키 (있으면)
     oldKey != newKey 이면 ops += Delete(oldKey), Put(newKey)
  engine.writeBatch(ops)    ← 문서와 인덱스가 함께 바뀌거나 함께 안 바뀐다
```

- 인덱스 k개면 쓰기 하나가 KV 연산 최대 1 + 2k개가 되고, 바꾸기 전에 **옛 문서를 읽어야** 한다(읽기 후 쓰기). 이것이 "보조 인덱스는 쓰기를 비싸게 만든다"의 실체다.
- 읽기 후 쓰기 사이에 다른 쓰기가 끼면 인덱스가 어긋난다. `DocumentStore`의 쓰기 메서드를 `@Synchronized`로 묶는다(단일 쓰기 스레드 결정, M7 전까지).

## 6. 필터 의미 (스키마가 없어서 정해야 하는 것)

| 상황 | 결정 |
|---|---|
| 필드가 없는 문서 | `Eq`·`Range` 모두 false, `Exists`도 false |
| 필드가 `null` | `Eq(path, null)`만 true |
| 타입이 다름 (`age: "20"` vs `Range(age, ≥ 20)`) | false. 숫자와 문자열을 비교하지 않는다 |
| 경로 중간이 객체가 아님 (`addr`가 문자열) | 필드 없음과 같다 |
| 배열 필드 | `Eq`는 배열 전체가 같을 때만. 원소 매칭 없음 |

관계형(M6)은 이 표가 필요 없다. 스키마가 타입과 필드 존재를 보장하기 때문이다. 문서 모델에서는 **이 결정을 매 질의마다 데이터가 떠안는다.**

## 7. 질의 실행

```
1. 필터를 AND의 평평한 목록으로 펼친다
2. 인덱스가 있는 경로의 Eq를 우선, 없으면 인덱스가 있는 경로의 Range를 하나 고른다
3. 골랐으면 IXSCAN: 그 조건의 값 범위를 인덱스 키 범위로 바꿔 scan → 문서 id 목록 → 문서 get → 나머지 조건으로 후처리
   못 골랐으면 COLLSCAN: d/<컬렉션 id>/ 접두사 전체 scan → 모든 조건으로 필터
4. 결과는 문서 id 순으로 정렬해 돌려준다
```

## 8. 테스트와 측정

- `JsonTest`: 파싱·직렬화 왕복, 이스케이프, 숫자, 중첩, 잘못된 입력 거부.
- `IndexKeyTest`: 무작위 값 쌍에서 "인코딩 바이트 순서 = 값 순서", 문자열 접두사·0x00 포함 경우.
- `DocumentStoreContractTest`(추상): M1·M2·M3 엔진 각각으로 실행.
  - 서로 다른 구조의 문서를 한 컬렉션에 넣고 필터 조회 (완료 조건)
  - §6 표의 각 행
  - 재오픈 후 컬렉션·인덱스·문서 유지
- **인덱스 동등성 무작위 테스트**: 무작위 문서 삽입·교체·삭제를 섞은 뒤, 같은 필터를 인덱스 있는 컬렉션과 없는 컬렉션에 던져 결과가 같은지 + 인덱스 항목 수가 "그 필드가 스칼라인 문서 수"와 같은지(고아 항목 없음).
- 돌연변이 검증: replace에서 옛 인덱스 항목 삭제를 빼면 무엇이 잡는가.
- 측정: 인덱스 0 / 1 / 3개일 때 insert 처리량과 쓰기 증폭, IXSCAN vs COLLSCAN 질의 시간 (문서 10만).

## 8.1 구현·측정하면서 찾은 것 (2026-10-25)

- **조용히 건너뛴 테스트**: 계약 테스트 하나가 `= withStore { … assertFailsWith { … } }` 식 본문이라 반환 타입이 `Unit`이 아니었고, JUnit이 테스트로 인식하지 않아 **경고 없이 실행되지 않았다**. `withStore`가 `Unit` 블록만 받게 고쳤다. 같은 문제가 다른 모듈에 없는지 "선언된 `@Test` 이름 vs 실행된 테스트 이름"을 프로젝트 전체에서 대조했다(137종 모두 실행됨).
- **M1 위 성능 버그 두 가지** ([측정](../measurements/m5-document.md) 첫 절): 쓰기마다 인덱스 정의를 범위 scan → 컬렉션별 캐시로. 큰 배치 프레임을 키마다 재디코딩 → `LogEngine`에 배치 프레임 캐시.
- **돌연변이 검증**: replace에서 옛 인덱스 항목 삭제를 빼면 "교체와 삭제가 인덱스 항목을 함께 바꾼다", "무작위 변경 후에도 인덱스 질의와 전체 스캔이 같다"가 세 엔진 모두에서 잡는다.

## 9. 구현 순서

1. ✅ `Json` (값 모델, 파서, 직렬화) + 테스트
2. ✅ `IndexKey` 정렬 보존 인코딩 + 테스트
3. ✅ `DocumentStore`/`Collection`: 메타데이터, 문서 CRUD, COLLSCAN
4. ✅ 인덱스 생성·유지, IXSCAN, explain
5. ✅ 엔진 3종 계약 테스트, 무작위 동등성 테스트, 돌연변이 검증
6. ✅ 측정

## 10. 트랜잭션 연결 (2026-10-26)

M7 MVCC를 문서 계층에 연결했다. SQL([m6-sql.md §10](m6-sql.md))과 같은 구조다.

```kotlin
val store = DocumentStore(engine, transactional = true)

store.transaction { tx ->                       // 충돌이면 블록 전체를 새 스냅샷으로 재실행
    val orders = tx.collection("orders")!!
    orders.find(Filter.Eq("customer.id", id)).forEach { orders.replace(it.id, renamed(it)) }
}

store.begin().use { tx -> …; tx.commit() }       // 직접 제어. commit()이 충돌이면 WriteConflictException
store.collection("orders")!!.insert(…)           // 트랜잭션 밖: 연산마다 자동 커밋 (충돌 시 재시도)
```

### 10.1 구조

- 로직은 `DocOps`(주어진 KV 뷰에서 동작)로 옮기고, `Collection`은 "어디서 실행할지"를 정하는 runner를 든 핸들이 됐다. 저장소에서 얻은 컬렉션은 자동 커밋으로, 트랜잭션에서 얻은 컬렉션은 그 트랜잭션 뷰(`TxnKvView`, txn 모듈로 옮겨 SQL과 공유)로 실행된다.
- **인덱스 정의 캐시(§8.1)는 트랜잭션 모드에서 끈다.** 커밋 전 트랜잭션이 만든 인덱스나, 오래된 스냅샷이 읽은 "인덱스 없음"이 캐시에 들어가면 다른 트랜잭션이 인덱스를 유지하지 않게 된다. 대가: 쓰기마다 인덱스 정의를 범위 scan으로 읽는다 (M1 Log 위에서는 느림).

### 10.2 스냅샷 격리만으로는 생기는 이상 현상과 대응

인덱스를 만드는 트랜잭션 T1과 문서를 쓰는 트랜잭션 T2는 서로 다른 키를 쓰므로 스냅샷 격리에서 둘 다 커밋된다. 그러면:
- T2가 먼저 커밋: T1은 자기 스냅샷의 문서로 인덱스를 채웠으니 T2의 문서가 빠진다.
- T1이 먼저 커밋: T2는 인덱스가 없는 스냅샷을 보고 인덱스 항목을 쓰지 않았다.

M7 §6의 write skew와 같은 종류다(읽은 것에 의존한 쓰기). 대응으로 txn에 **읽기 검증**(`Txn.watch(key)`, `Txn.watchRange(from, to)`)을 추가했다 — 커밋 때 지켜본 키·범위에 시작 이후 버전이 생겼으면 충돌. 등록한 읽기만 검증하므로 SSI 전체는 아니다.
- 문서 쓰기·삭제: 컬렉션의 인덱스 버전 키 `m/v/<컬렉션 id>`를 지켜본다. `createIndex`는 이 키를 쓴다.
- `createIndex`: 컬렉션 문서 범위 `d/<컬렉션 id>/`를 지켜본다(커밋 때 범위 크기에 비례하는 검사).

SQL에도 같은 문제가 있어 같이 고쳤다: 행을 쓰는 문장은 테이블 정의 키를, `CREATE INDEX`·`ALTER TABLE`은 테이블 행 범위를 지켜본다.

### 10.3 검증

- 문서 계약 테스트 7종을 트랜잭션 모드에서 엔진 3종으로 (자동 커밋 경로)
- 트랜잭션 테스트 8종 × 엔진 3: 다중 문서 원자 커밋·비가시성, ROLLBACK(컬렉션·인덱스 생성 포함), 스냅샷, 충돌과 `transaction` 재시도, **M8 D2 복제된 고객 이름 변경 중 읽는 쪽이 섞인 이름을 보지 않음**(쓰는 스레드 1 + 읽는 스레드 2), 동시 수정 후 인덱스 일치, **인덱스 생성 경쟁 양방향**, 비트랜잭션 모드에서 begin 거부
- 돌연변이:

| 넣은 버그 | 잡은 테스트 |
|---|---|
| 문서 쓰기가 인덱스 버전을 지켜보지 않음 | 인덱스 생성 경쟁 × 3 |
| 인덱스 생성이 문서 범위를 지켜보지 않음 | 인덱스 생성 경쟁 × 3 |
| SQL 행 쓰기가 테이블 정의를 지켜보지 않음 | SQL 인덱스 생성 경쟁 × 3 |
| SQL 스키마 변경이 행 범위를 지켜보지 않음 | SQL 인덱스 생성 경쟁 × 3 |
| 커밋이 지켜본 범위를 검증하지 않음 | 문서·SQL 경쟁 × 3 = 6 |
| 트랜잭션에서 얻은 컬렉션의 runner가 동작하지 않음 (예외) | 18 |
