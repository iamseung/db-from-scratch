package dev.mydb.sql

import dev.mydb.kv.KvEngine
import dev.mydb.txn.Isolation
import dev.mydb.txn.Txn
import dev.mydb.txn.TxnKvView
import dev.mydb.txn.TxnStore
import dev.mydb.txn.WriteConflictException

/**
 * SQL 데이터베이스. 두 모드가 있고 데이터 파일 형식이 다르다(서로 열 수 없다).
 *
 * - 비트랜잭션 모드 (기본): 문장을 저장 엔진에 바로 실행한다. 원자성의 단위는 문장이다 (M6).
 * - 트랜잭션 모드 (`transactional = true`): 저장 엔진 위에 M7 MVCC를 얹는다. `BEGIN … COMMIT`으로 여러 문장을
 *   원자적으로 묶고, 트랜잭션 밖의 문장은 문장마다 자동 커밋한다.
 *   격리 수준은 [isolation](기본: 스냅샷 격리)이고, `BEGIN ISOLATION LEVEL SERIALIZABLE | SNAPSHOT`으로 트랜잭션마다 바꿀 수 있다.
 *
 * 세션(연결) 하나가 트랜잭션 하나를 가진다. [execute]는 기본 세션을 쓰고, 동시 접속은 [session]으로 세션을 따로 만든다.
 */
class Database(private val engine: KvEngine, transactional: Boolean = false, isolation: Isolation = Isolation.SNAPSHOT) {
    private val store: TxnStore? = if (transactional) TxnStore(engine, isolation) else null
    private val defaultSession = session()

    val isTransactional: Boolean
        get() = store != null

    fun session(): Session = Session(this)

    fun execute(sql: String): Result = defaultSession.execute(sql)

    /** 편의: SELECT 결과 행만. */
    fun query(sql: String): List<List<Value>> = (execute(sql) as Result.Rows).rows

    internal fun begin(isolation: Isolation?): Txn {
        val store = store ?: throw SqlException("트랜잭션은 트랜잭션 모드 Database(transactional = true)에서만 쓸 수 있습니다")
        return store.begin(isolation ?: store.defaultIsolation)
    }

    /**
     * 트랜잭션 밖의 문장. 트랜잭션 모드면 문장마다 트랜잭션을 열어 커밋하고, 쓰기 충돌이면 새 스냅샷으로 문장을 다시 실행한다.
     * 비트랜잭션 모드는 단일 쓰기 스레드 결정(M6)대로 Database 단위로 직렬화한다.
     */
    internal fun autocommit(statement: Statement): Result {
        val store = store ?: return synchronized(this) { Executor(engine).execute(statement) }
        return store.run { txn -> Executor(TxnKvView(txn)).execute(statement) }
    }
}

class Session internal constructor(private val db: Database) {
    private var txn: Txn? = null

    val inTransaction: Boolean
        get() = txn != null

    /**
     * 트랜잭션 안에서 문장이 실패하면(제약 위반 등) 그 문장만 무효이고 트랜잭션은 열린 채로 남는다.
     * 문장은 KV 변경을 모았다가 마지막에 한 번에 쓰므로, 실패한 문장의 일부만 트랜잭션에 남는 일은 없다.
     * COMMIT이 쓰기 충돌로 실패하면 트랜잭션 전체가 버려진다.
     */
    @Synchronized
    fun execute(sql: String): Result = when (val statement = Parser.parse(sql)) {
        is Statement.Begin -> {
            if (txn != null) throw SqlException("이미 트랜잭션 안입니다 (중첩 트랜잭션은 지원하지 않습니다)")
            txn = db.begin(statement.isolation)
            Result.Ok
        }
        Statement.Commit -> {
            val current = txn ?: throw SqlException("진행 중인 트랜잭션이 없습니다")
            txn = null
            try {
                current.commit()
            } catch (e: WriteConflictException) {
                // SERIALIZABLE의 위험 구조 검출(SerializationFailureException)도 여기로 온다
                throw SqlException("직렬화 실패: 동시에 실행된 트랜잭션과 충돌했습니다. 트랜잭션을 처음부터 다시 실행하세요 (${e.message})")
            }
            Result.Ok
        }
        Statement.Rollback -> {
            val current = txn ?: throw SqlException("진행 중인 트랜잭션이 없습니다")
            txn = null
            current.rollback()
            Result.Ok
        }
        else -> txn?.let { Executor(TxnKvView(it)).execute(statement) } ?: db.autocommit(statement)
    }
}
