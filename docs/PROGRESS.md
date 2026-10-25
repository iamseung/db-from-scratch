# 진행 현황

| 마일스톤 | 상태 |
|---|---|
| M0 storage-common | ✅ |
| M1 engine-log | ✅ 구현·검증·측정 |
| M2 engine-lsm | ✅ 구현·검증·측정 (예상 6개 중 5개 적중) |
| M3 engine-btree | ✅ 구현·검증(돌연변이 4종)·측정 (예상 7개 중 5개 적중) |
| M4 비교 실험 | 설계·측정 코드 작성, 실행 대기 |
| M5 document | 설계 완료, 구현 중 |
| M6 ~ M8 | 대기 |

테스트 198개 통과 (`./gradlew test` + `./gradlew slowTest`).
