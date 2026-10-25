# M8 · 데이터 모델 비교 실험 상세 설계

> 같은 도메인(고객 · 주문 · 주문 항목)을 같은 저장 엔진(M2 LSM) 위의 **관계형 계층(M6)**과 **문서 계층(M5)**에 각각 저장하고, 무엇이 쉬워지고 무엇을 내주는지 KV 연산 수와 시간으로 잰다.
> 참고: DDIA 2장 "Relational Model Versus Document Model", MongoDB 데이터 모델링(임베딩 vs 참조).

## 1. 질문 (로드맵 M8 + 첫 설계의 실험표)

| # | 질문 | 관계형 | 문서형 (임베딩) |
|---|---|---|---|
| D1 | 주문 하나와 그 항목을 읽는 비용 | JOIN: 주문 1 + 항목 인덱스 scan + 항목 행 get | 문서 get 1회 |
| D2 | 고객 이름을 바꾸는 비용 | 고객 행 1개 | 그 고객의 주문 문서 전부 (고객 정보를 복제해 두었으므로) |
| D3 | 열(필드)을 추가하는 비용 | `ALTER TABLE ADD COLUMN`: 모든 행을 다시 쓴다 | 0 — 새 문서만 필드를 가지고, 읽는 쪽이 "없음"을 처리 |
| D4 | 같은 데이터의 저장 크기 | 값만 (스키마가 이름·타입을 앎) | 필드 이름까지 매 문서에 |
| D5 | 일관성 경계 | 주문 + 항목 삽입은 문장 2개 → M7 트랜잭션 없이는 원자적이지 않다 | 문서 하나라 원자적 |

## 2. 데이터

- 고객 2,000명, 주문 20,000건, 주문당 항목 1 ~ 5개(평균 3, 결정적 생성)
- 관계형:
  ```sql
  CREATE TABLE customers (id INT PRIMARY KEY, name TEXT NOT NULL, city TEXT)
  CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL, total INT)
  CREATE TABLE items (id INT PRIMARY KEY, order_id INT NOT NULL, product TEXT, qty INT, price INT)
  CREATE INDEX idx_items_order ON items (order_id)
  CREATE INDEX idx_orders_customer ON orders (customer_id)
  ```
- 문서형 (`orders` 컬렉션, 인덱스 `customer.id`):
  ```json
  {"customer":{"id":7,"name":"kim","city":"Seoul"},"total":1230,
   "items":[{"product":"p-3","qty":2,"price":100}, …]}
  ```

## 3. 측정 도구: `CountingKvEngine`

`KvEngine`을 감싸 get · scan · scan으로 읽은 항목 · 배치 · 쓴 KV 연산 · 쓴 바이트를 센다. 두 모델이 **저장 엔진에 무엇을 요청하는지**를 같은 단위로 비교하려는 것이다. 시간은 참고로 함께 잰다.

## 4. `ALTER TABLE ADD COLUMN` (M6 확장)

```sql
ALTER TABLE t ADD [COLUMN] c 타입      -- NULL 허용 열만 (기존 행은 NULL)
```

- 행 인코딩이 "스키마의 열 순서대로 값만" 저장하므로(M6 §3), 열이 늘면 기존 행을 새 스키마로 읽을 수 없다. **모든 행을 새 형식으로 다시 쓴다**(카탈로그 변경과 한 배치 — 원자적).
- 실제 DB는 행에 스키마 버전을 두고 읽을 때 변환하는 방식(lazy)을 쓰기도 한다. 이번에는 비용이 드러나도록 eager 방식으로 한다.

## 5. 완료 조건

D1 ~ D4를 같은 데이터로 재서 표로 남기고, D5를 테스트로 보인다(관계형에서 두 문장 사이에 실패하면 주문만 남는다 / 문서는 남지 않는다). 각 결과를 모델의 구조로 설명한다.

## 6. 구현 순서

1. ✅ M6에 `ALTER TABLE ADD COLUMN` + 테스트
2. ✅ `CountingKvEngine`
3. ✅ 측정 `./gradlew :bench:run --args=m8`
4. ✅ D5 테스트
5. ✅ 결과 문서

## 7. 결과 (2026-10-25)

[docs/measurements/m8-data-models.md](../measurements/m8-data-models.md). D1 · D2 · D3 · D5는 예상대로.
- **D4는 반만 맞았다**: 값 바이트는 관계형이 작지만(2.9 vs 3.8 MiB), 정규화로 늘어난 행 키(6만 항목)와 JOIN용 보조 인덱스 2종 때문에 전체는 관계형이 더 크다(5.9 vs 4.4 MiB). 키 접두사별로 나눠 다시 재서 확인했다.
