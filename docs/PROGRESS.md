# 진행 현황

## M2 engine-lsm (브랜치 `feature/m2-engine-lsm`)

| 단계 (상세 설계 §16) | 상태 |
|---|---|
| 1. 공통 하네스 이동 | ✅ |
| 2~7. memtable · SSTable · bloom · MANIFEST · Version · size-tiered compaction · 복구 | ✅ 구현 + 테스트 통과 |
| 8. 하네스(계약 2종·무작위·크래시) + FaultInjector + 돌연변이 검증 | ✅ 돌연변이로 빈틈 1건 발견 → 테스트 추가 |
| 9. 측정 | 다음 단계 |

전체 테스트 153개 통과 (`./gradlew test` + `./gradlew slowTest`).
