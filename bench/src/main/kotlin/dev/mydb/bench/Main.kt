package dev.mydb.bench

/**
 * ./gradlew :bench:run --args=m1   M1 LogEngine 단독 측정
 * ./gradlew :bench:run --args=m2   M1 LogEngine vs M2 LsmEngine 비교
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "m1" -> runM1Bench()
        "m2" -> runCompareBench()
        else -> error("측정을 고르세요: --args=m1 | m2")
    }
}
