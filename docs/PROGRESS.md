# 진행 현황 (2026-10-14 15:57Z 기준)

## M2 engine-lsm — 구현 중 (브랜치 `feature/m2-engine-lsm`)

| 단계 (상세 설계 §16) | 상태 |
|---|---|
| 1. 공통 하네스 이동 | ✅ 완료, M1 96개 통과, 돌연변이 2종 검출 확인 |
| 2. MemTable + MergingIterator | 코드 작성, 컴파일됨, **테스트 미실행** |
| 3. Block/BloomFilter/Footer/SSTableWriter/Reader | 코드 작성, 컴파일됨, **테스트 미실행** |
| 4. ManifestEdit/Manifest/Version | 코드 작성, 컴파일됨, **테스트 미실행** |
| 5~7. LsmEngine (flush, get/scan, 복구, size-tiered compaction, Version 참조 카운트) | 코드 작성, 컴파일됨, **테스트 미실행** |
| 8. 하네스·FaultInjector·무작위·크래시 테스트 | 미작성 (작성 명령이 실행되지 않음) |
| 9. 측정 | 미착수 |

## 다음에 할 일
1. engine-lsm 테스트 작성: 계약(기본 + tiny), 무작위(`compactAll`), 크래시(`LsmCrashEngineFactory`), MergingIterator·Bloom 위양성률·SSTable·Picker 단위, FaultInjector 5지점, scan 중 compaction
2. 실패 수정 → 상세 설계 문서 반영
3. 측정 후 main 머지, M3로
