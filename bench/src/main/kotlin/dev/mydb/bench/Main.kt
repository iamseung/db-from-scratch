package dev.mydb.bench

/**
 * ./gradlew :bench:run --args=m1   M1 LogEngine 단독 측정
 * ./gradlew :bench:run --args=compare   M1 Log · M2 LSM · M3 B+tree 비교
 * ./gradlew :bench:run --args=m4        M4 비교 실험 (무작위 순서, 공간 시계열, 그룹 커밋, 읽기 증폭), "m4 e2"처럼 하나만
 * ./gradlew :bench:run --args=m5        M5 문서 계층: 인덱스 수별 삽입 비용, IXSCAN vs COLLSCAN
 * ./gradlew :bench:run --args=m7        M7 트랜잭션: 크기별 커밋 비용, 버전 누적과 GC
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "m1" -> runM1Bench()
        "compare" -> runCompareBench()
        "m4" -> runEngineExperiments(args.getOrNull(1))
        "m5" -> runDocumentBench()
        "m7" -> runTxnBench()
        else -> error("측정을 고르세요: --args=m1 | compare | m4 [e1|e2|e3|e4] | m5 | m7")
    }
}
