package dev.mydb.bench

/**
 * ./gradlew :bench:run --args=m1   M1 LogEngine 단독 측정
 * ./gradlew :bench:run --args=compare   M1 Log · M2 LSM · M3 B+tree 비교
 * ./gradlew :bench:run --args=m4        M4 비교 실험 (무작위 순서, 공간 시계열, 그룹 커밋, 읽기 증폭)
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "m1" -> runM1Bench()
        "compare" -> runCompareBench()
        "m4" -> runEngineExperiments()
        else -> error("측정을 고르세요: --args=m1 | compare | m4")
    }
}
