package dev.mydb.storage

enum class RecoveryMode {
    /** 마지막 파일의 첫 손상 프레임부터 끝까지 잘라낸다. 마지막 쓰기 중단으로 보고 버린다. */
    TOLERATE_TAIL,

    /** 손상 프레임을 만나면 CorruptionException. 디스크 결함을 조용히 넘기지 않는다. */
    STRICT,
}
