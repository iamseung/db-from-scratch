package dev.mydb.engine.log

/**
 * merge의 각 단계에서 호출되는 훅. 테스트가 여기서 예외를 던져 그 지점의 크래시를 흉내 낸다.
 * 운영 경로에서는 [NONE]이라 아무 일도 하지 않는다.
 */
fun interface FaultInjector {
    fun reached(step: MergeStep)

    companion object {
        val NONE = FaultInjector { }
    }
}

enum class MergeStep {
    /** merge 파일에 엔트리 하나를 쓴 직후. 엔트리마다 호출된다. */
    ENTRY_WRITTEN,

    /** footer를 썼지만 아직 sync하지 않았다. */
    FOOTER_WRITTEN,

    /** merge 파일과 디렉터리를 sync했다. 이 지점부터 merge는 확정이다. */
    COMMITTED,

    /** 옛 세그먼트 하나를 지운 직후. 세그먼트마다 호출된다. */
    OLD_LOG_DELETED,

    /** merge 파일을 세그먼트 이름으로 바꾼 직후. */
    RENAMED,
}
