package dev.mydb.txn

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 무작위 스케줄 직렬화 가능성 검사. 트랜잭션 3 ~ 5개(읽기 전용 포함), 키 3 ~ 4개의 읽기·쓰기를 섞은 스케줄을 실행하고,
 * 커밋된 트랜잭션들의 기록이 어떤 직렬 순서로 설명되는지 본다 ([SsiScheduleExplorer]).
 *
 * 민감도 (2026-10-26, 15만 스케줄): 규칙 (A)를 끄면 위반 2,906개, (C)를 끄면 66개를 찾는다. (B)를 끄면 0개 — §11.8.
 */
class SsiRandomScheduleTest {
    @Test
    fun `SERIALIZABLE에서 커밋된 기록은 모두 직렬화 가능하다`() {
        val random = Random(System.nanoTime().also { println("seed=$it") })
        repeat(30_000) { i ->
            val schedule = SsiScheduleExplorer.randomSchedule(random, txns = 3 + i % 3, keys = if (i % 2 == 0) 3 else 4)
            val outcome = SsiScheduleExplorer.run(schedule)
            assertTrue(outcome.isSerializable(), "직렬화 위반: $schedule\n커밋=${outcome.committed} 읽기=${outcome.reads} 최종=${outcome.finalState}")
        }
    }

    /** 기록 상한을 아주 작게(2) 해서 요약이 쉴 새 없이 일어나도 직렬화 가능해야 한다. */
    @Test
    fun `기록 상한이 아주 작아 요약이 계속 일어나도 직렬화 가능하다`() {
        val random = Random(System.nanoTime().also { println("seed=$it") })
        repeat(30_000) { i ->
            val schedule = SsiScheduleExplorer.randomSchedule(random, txns = 3 + i % 3, keys = 4)
            val outcome = SsiScheduleExplorer.run(schedule, maxRecords = 2)
            assertTrue(outcome.isSerializable(), "직렬화 위반 (상한 2): $schedule\n커밋=${outcome.committed} 읽기=${outcome.reads}")
        }
    }

    /** 검사기 자체가 위반을 찾을 수 있다는 확인: 같은 생성기로 SNAPSHOT은 위반이 나와야 한다. */
    @Test
    fun `검사기는 SNAPSHOT의 위반을 찾아낸다`() {
        val random = Random(1)
        val violations = (0 until 2_000).count { i ->
            !SsiScheduleExplorer.run(SsiScheduleExplorer.randomSchedule(random, 3 + i % 3, keys = 3), Isolation.SNAPSHOT).isSerializable()
        }
        assertTrue(violations > 100, "SNAPSHOT 위반 $violations 개")
    }

    @Test
    fun `직렬화 판정기 - 손으로 만든 write skew는 위반, 직렬 실행은 통과`() {
        val skew = listOf(
            SsiScheduleExplorer.Op(0, SsiScheduleExplorer.Kind.BEGIN), SsiScheduleExplorer.Op(1, SsiScheduleExplorer.Kind.BEGIN),
            SsiScheduleExplorer.Op(0, SsiScheduleExplorer.Kind.READ, 0), SsiScheduleExplorer.Op(0, SsiScheduleExplorer.Kind.READ, 1),
            SsiScheduleExplorer.Op(1, SsiScheduleExplorer.Kind.READ, 0), SsiScheduleExplorer.Op(1, SsiScheduleExplorer.Kind.READ, 1),
            SsiScheduleExplorer.Op(0, SsiScheduleExplorer.Kind.WRITE, 0), SsiScheduleExplorer.Op(1, SsiScheduleExplorer.Kind.WRITE, 1),
            SsiScheduleExplorer.Op(0, SsiScheduleExplorer.Kind.COMMIT), SsiScheduleExplorer.Op(1, SsiScheduleExplorer.Kind.COMMIT),
        )
        assertEquals(false, SsiScheduleExplorer.run(skew, Isolation.SNAPSHOT).isSerializable())
        assertEquals(true, SsiScheduleExplorer.run(skew, Isolation.SERIALIZABLE).isSerializable())
        assertEquals(1, SsiScheduleExplorer.run(skew, Isolation.SERIALIZABLE).committed.size)
    }
}
