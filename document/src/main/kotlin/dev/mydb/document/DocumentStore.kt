package dev.mydb.document

import dev.mydb.kv.ByteKeys
import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp
import dev.mydb.txn.Txn
import dev.mydb.txn.TxnKvView
import dev.mydb.txn.TxnStore
import dev.mydb.txn.WriteConflictException
import java.nio.ByteBuffer

/**
 * KvEngine 위의 스키마 없는 JSON 문서 저장소. 저장 엔진이 무엇이든 같은 코드로 동작한다.
 *
 * 키 레이아웃 (설계 §3):
 * ```
 * m/next                        → 다음 id (u32)
 * m/c/<컬렉션 이름>              → 컬렉션 id
 * m/i/<컬렉션 id>/<필드 경로>     → 인덱스 id
 * d/<컬렉션 id>/<문서 id>         → JSON 텍스트
 * x/<인덱스 id>/<값 인코딩>/<문서 id> → (빈 값)
 * ```
 *
 * 문서와 그 인덱스 항목은 writeBatch 하나로 바뀌어 문서 하나 단위로 원자적이다.
 *
 * 두 모드가 있고 데이터 파일 형식이 다르다(서로 열 수 없다).
 * - 비트랜잭션 모드 (기본): 저장 엔진에 바로 쓴다. 쓰기는 "옛 문서 읽기 → 배치 쓰기"라 저장소 단위로 직렬화한다.
 * - 트랜잭션 모드 (`transactional = true`): M7 MVCC 위. [transaction]/[begin]으로 여러 문서를 원자적으로 바꾸고,
 *   그 밖의 연산은 연산마다 자동 커밋(충돌 시 재시도)한다. 격리 수준은 스냅샷 격리다.
 */
class DocumentStore(private val engine: KvEngine, transactional: Boolean = false) {
    private val txns: TxnStore? = if (transactional) TxnStore(engine) else null

    /**
     * 컬렉션별 인덱스 정의 캐시 (경로 → 인덱스 id). 쓰기마다 메타데이터를 범위 scan하면,
     * scan이 전체 키에 비례하는 M1 Log에서 삽입 하나가 O(N)이 됐다 (M5 측정에서 발견). createIndex 때만 바뀐다.
     *
     * **트랜잭션 모드에서는 쓰지 않는다.** 커밋 전 트랜잭션이 만든 인덱스나, 그보다 오래된 스냅샷이 읽은 "인덱스 없음"이
     * 캐시에 들어가면 다른 트랜잭션이 인덱스를 유지하지 않게 된다. 대신 매번 자기 스냅샷에서 인덱스 정의를 읽는다.
     */
    private val indexCache: HashMap<Int, List<Pair<String, Int>>>? = if (transactional) null else HashMap()

    val isTransactional: Boolean
        get() = txns != null

    /** 트랜잭션 밖의 실행 경로: 비트랜잭션 모드는 저장소 단위 직렬화, 트랜잭션 모드는 연산마다 자동 커밋. */
    private val direct = object : Runner() {
        override fun <T> run(block: (DocOps) -> T): T {
            val store = txns ?: return synchronized(this@DocumentStore) { block(DocOps(engine, indexCache)) }
            return store.run { txn -> block(DocOps(TxnKvView(txn), null)) }
        }
    }

    fun createCollection(name: String): Collection = Collection(name, direct.run { it.createCollection(name) }, direct)

    fun collection(name: String): Collection? = direct.run { it.collectionId(name) }?.let { Collection(name, it, direct) }

    /**
     * [block] 안의 모든 변경을 한 트랜잭션으로 커밋한다. 쓰기 충돌이면 새 스냅샷으로 [block]을 처음부터 다시 실행하므로,
     * [block]은 다시 실행해도 되는 코드여야 한다 (바깥 상태를 바꾸지 말 것).
     */
    fun <T> transaction(maxAttempts: Int = 50, block: (DocumentTransaction) -> T): T {
        var lastConflict: WriteConflictException? = null
        repeat(maxAttempts) {
            val tx = begin()
            try {
                val result = block(tx)
                tx.commit()
                return result
            } catch (e: WriteConflictException) {
                lastConflict = e
            } finally {
                tx.close()
            }
        }
        throw lastConflict!!
    }

    /** 직접 커밋·롤백하는 트랜잭션. commit()이 쓰기 충돌이면 WriteConflictException이고 변경은 모두 버려진다. */
    fun begin(): DocumentTransaction {
        val store = txns ?: throw IllegalStateException("트랜잭션은 트랜잭션 모드 DocumentStore(transactional = true)에서만 쓸 수 있습니다")
        return DocumentTransaction(store.begin())
    }

    data class Document(val id: String, val body: JsonObject)

    /** 질의 조건. 의미는 설계 §6 (필드 없음·타입 불일치는 false). */
    sealed interface Filter {
        data class Eq(val path: String, val value: JsonValue) : Filter

        data class Range(val path: String, val lower: Bound?, val upper: Bound?) : Filter {
            init {
                require(lower != null || upper != null) { "Range에는 하한이나 상한이 하나는 있어야 합니다" }
            }
        }

        data class And(val filters: List<Filter>) : Filter

        data class Exists(val path: String) : Filter
    }

    data class Bound(val value: JsonValue, val inclusive: Boolean)

    /** 연산을 어떤 KV 뷰에서 실행할지 정한다: 저장 엔진, 자동 커밋 트랜잭션, 또는 열린 트랜잭션. */
    abstract class Runner internal constructor() {
        internal abstract fun <T> run(block: (DocOps) -> T): T
    }

    /** 컬렉션 핸들. 같은 컬렉션이라도 어디서 얻었는지(저장소 / 트랜잭션)에 따라 실행 경로가 다르다. */
    class Collection internal constructor(val name: String, val id: Int, private val runner: Runner) {
        fun insert(docId: String, doc: JsonObject) = runner.run { it.insert(this, docId, doc) }

        /** 없으면 삽입, 있으면 통째로 교체한다. */
        fun replace(docId: String, doc: JsonObject) = runner.run { it.write(this, docId, doc) }

        fun get(docId: String): JsonObject? = runner.run { it.get(this, docId) }

        fun delete(docId: String): Boolean = runner.run { it.delete(this, docId) }

        /** 기존 문서로 인덱스를 채운다. 인덱스 정의와 항목을 한 배치로 쓴다. */
        fun createIndex(path: String) = runner.run { it.createIndex(this, path) }

        /** 필터에 맞는 문서를 문서 id 순으로. 인덱스를 쓸 수 있으면 IXSCAN, 아니면 COLLSCAN. */
        fun find(filter: Filter): List<Document> = runner.run { it.find(this, filter) }

        fun explain(filter: Filter): String = runner.run { it.plan(this, filter).description }

        /** 테스트용: 이 경로의 인덱스 항목 수. 고아 항목이 없으면 "그 필드가 스칼라인 문서 수"와 같다. */
        fun indexEntryCount(path: String): Int = runner.run { it.indexEntryCount(this, path) }
    }

    /** 열린 트랜잭션. 여기서 얻은 컬렉션의 모든 연산이 이 트랜잭션의 스냅샷을 읽고, 쓰기는 커밋까지 보이지 않는다. 한 스레드에서만 쓴다. */
    class DocumentTransaction internal constructor(private val txn: Txn) : AutoCloseable {
        private val runner = object : Runner() {
            override fun <T> run(block: (DocOps) -> T): T = block(DocOps(TxnKvView(txn), null))
        }

        fun createCollection(name: String): Collection = Collection(name, runner.run { it.createCollection(name) }, runner)

        fun collection(name: String): Collection? = runner.run { it.collectionId(name) }?.let { Collection(name, it, runner) }

        fun commit() = txn.commit()

        fun rollback() = txn.rollback()

        /** 커밋하지 않고 닫으면 롤백이다. */
        override fun close() = txn.close()
    }

    /** 실제 로직. 주어진 KV 뷰 위에서만 동작하고 상태는 인덱스 캐시(있으면)뿐이다. */
    internal class DocOps(private val kv: KvEngine, private val indexCache: HashMap<Int, List<Pair<String, Int>>>?) {
        fun createCollection(name: String): Int {
            require(name.isNotEmpty() && '/' !in name) { "컬렉션 이름은 비어 있지 않고 '/'를 포함하지 않아야 합니다: $name" }
            check(kv.get(collectionMetaKey(name)) == null) { "이미 있는 컬렉션입니다: $name" }
            val id = nextId()
            kv.writeBatch(listOf(WriteOp.Put(ID_COUNTER, u32(id + 1)), WriteOp.Put(collectionMetaKey(name), u32(id))))
            return id
        }

        fun collectionId(name: String): Int? = kv.get(collectionMetaKey(name))?.let(::readU32)

        fun insert(c: Collection, docId: String, doc: JsonObject) {
            check(kv.get(docKey(c, docId)) == null) { "이미 있는 문서입니다: ${c.name}/$docId" }
            write(c, docId, doc)
        }

        fun get(c: Collection, docId: String): JsonObject? = kv.get(docKey(c, docId))?.let { Json.parseObject(String(it, Charsets.UTF_8)) }

        fun delete(c: Collection, docId: String): Boolean {
            watchIndexDefinitions(c)
            val old = get(c, docId) ?: return false
            val ops = mutableListOf<WriteOp>(WriteOp.Delete(docKey(c, docId)))
            for ((path, indexId) in indexes(c)) {
                indexEntry(indexId, old, path, docId)?.let { ops += WriteOp.Delete(it) }
            }
            kv.writeBatch(ops)
            return true
        }

        fun createIndex(c: Collection, path: String) {
            require(path.isNotEmpty()) { "필드 경로가 비어 있습니다" }
            check(kv.get(indexMetaKey(c, path)) == null) { "이미 있는 인덱스입니다: ${c.name}.$path" }
            val indexId = nextId()
            val ops = mutableListOf<WriteOp>(
                WriteOp.Put(ID_COUNTER, u32(indexId + 1)),
                WriteOp.Put(indexMetaKey(c, path), u32(indexId)),
                // 이 컬렉션에 쓰는 트랜잭션들이 지켜보는 키. 바뀌면 그들은 커밋 때 충돌한다 (인덱스를 모른 채 쓴 문서 방지)
                WriteOp.Put(indexVersionKey(c), u32(indexId)),
            )
            // 반대 방향: 이 트랜잭션이 채우는 동안 다른 트랜잭션이 문서를 썼다면, 그 문서는 이 인덱스에 빠진다 → 충돌로 처리
            val docPrefix = prefix("d/", c.id)
            (kv as? TxnKvView)?.watchRange(docPrefix, prefixEnd(docPrefix))
            for (doc in scanAll(c)) indexEntry(indexId, doc.body, path, doc.id)?.let { ops += WriteOp.Put(it, ByteArray(0)) }
            kv.writeBatch(ops)
            indexCache?.remove(c.id)
        }

        fun find(c: Collection, filter: Filter): List<Document> {
            val plan = plan(c, filter)
            val candidates = if (plan.indexId == null) {
                scanAll(c)
            } else {
                indexScan(plan).mapNotNull { docId -> get(c, docId)?.let { Document(docId, it) } }
            }
            return candidates.filter { matches(it.body, filter) }.sortedBy { it.id }
        }

        fun indexEntryCount(c: Collection, path: String): Int {
            val indexId = kv.get(indexMetaKey(c, path))?.let(::readU32) ?: return 0
            val p = prefix("x/", indexId)
            return kv.scan(p, prefixEnd(p)).use { it.asSequence().count() }
        }

        fun write(c: Collection, docId: String, doc: JsonObject) {
            require(docId.isNotEmpty()) { "문서 id가 비어 있습니다" }
            watchIndexDefinitions(c)
            val old = get(c, docId)
            val ops = mutableListOf<WriteOp>(WriteOp.Put(docKey(c, docId), Json.stringify(doc).toByteArray(Charsets.UTF_8)))
            for ((path, indexId) in indexes(c)) {
                val oldEntry = old?.let { indexEntry(indexId, it, path, docId) }
                val newEntry = indexEntry(indexId, doc, path, docId)
                if (oldEntry != null && newEntry != null && oldEntry.contentEquals(newEntry)) continue
                oldEntry?.let { ops += WriteOp.Delete(it) }
                newEntry?.let { ops += WriteOp.Put(it, ByteArray(0)) }
            }
            kv.writeBatch(ops)
        }

        /**
         * 문서를 쓰는 트랜잭션은 인덱스 정의를 읽고 그에 맞춰 인덱스 항목을 쓴다. 그 사이 다른 트랜잭션이 인덱스를 만들면
         * 이 문서는 새 인덱스에서 빠진다(스냅샷 격리는 서로 다른 키를 쓴 두 트랜잭션을 충돌로 보지 않는다).
         * 그래서 컬렉션의 인덱스 버전 키를 지켜보고, 커밋 전에 바뀌었으면 충돌로 처리한다. 비트랜잭션 모드에서는 할 일이 없다.
         */
        private fun watchIndexDefinitions(c: Collection) {
            (kv as? TxnKvView)?.watch(indexVersionKey(c))
        }

        private fun scanAll(c: Collection): List<Document> {
            val docPrefix = prefix("d/", c.id)
            return kv.scan(docPrefix, prefixEnd(docPrefix)).use { iterator ->
                iterator.asSequence().map { e ->
                    Document(String(e.key, docPrefix.size, e.key.size - docPrefix.size, Charsets.UTF_8), Json.parseObject(String(e.value, Charsets.UTF_8)))
                }.toList()
            }
        }

        private fun indexes(c: Collection): List<Pair<String, Int>> {
            val load = {
                val p = indexMetaPrefix(c)
                kv.scan(p, prefixEnd(p)).use { iterator ->
                    iterator.asSequence().map { e -> String(e.key, p.size, e.key.size - p.size, Charsets.UTF_8) to readU32(e.value) }.toList()
                }
            }
            return indexCache?.getOrPut(c.id, load) ?: load()
        }

        class Plan(val indexId: Int?, val from: ByteArray, val to: ByteArray, val description: String)

        /** 인덱스가 있는 경로의 Eq를 우선, 없으면 Range 하나. 나머지 조건은 find가 후처리한다. */
        fun plan(c: Collection, filter: Filter): Plan {
            val conditions = flatten(filter)
            val indexed = indexes(c).toMap()
            val eq = conditions.filterIsInstance<Filter.Eq>().firstOrNull { it.path in indexed && IndexKey.encode(it.value) != null }
            if (eq != null) {
                val indexId = indexed.getValue(eq.path)
                val valuePrefix = prefix("x/", indexId) + IndexKey.encode(eq.value)!!
                return Plan(indexId, valuePrefix, prefixEnd(valuePrefix), "IXSCAN ${eq.path} [= ${Json.stringify(eq.value)}]")
            }
            val range = conditions.filterIsInstance<Filter.Range>().firstOrNull { r ->
                r.path in indexed && listOfNotNull(r.lower, r.upper).all { IndexKey.encode(it.value) != null }
            }
            if (range != null) {
                val indexId = indexed.getValue(range.path)
                val base = prefix("x/", indexId)
                // 하한 타입부터 상한 타입까지만 본다. 타입이 다른 값은 후처리에서 false가 된다
                val from = range.lower?.let { b -> IndexKey.encode(b.value)!!.let { if (b.inclusive) base + it else prefixEnd(base + it) } }
                    ?: (base + byteArrayOf(typeTag(range.upper!!.value)))
                val to = range.upper?.let { b -> IndexKey.encode(b.value)!!.let { if (b.inclusive) prefixEnd(base + it) else base + it } }
                    ?: prefixEnd(base + byteArrayOf(typeTag(range.lower!!.value)))
                return Plan(indexId, from, to, "IXSCAN ${range.path} [${describe(range)}]")
            }
            return Plan(null, ByteArray(0), ByteArray(0), "COLLSCAN")
        }

        private fun indexScan(plan: Plan): List<String> {
            if (ByteKeys.compare(plan.from, plan.to) >= 0) return emptyList()
            return kv.scan(plan.from, plan.to).use { iterator ->
                iterator.asSequence().map { e -> docIdFromIndexKey(e.key) }.toList()
            }
        }

        private fun docKey(c: Collection, docId: String) = prefix("d/", c.id) + docId.toByteArray(Charsets.UTF_8)

        private fun indexMetaPrefix(c: Collection) = "m/i/".toByteArray() + u32(c.id) + "/".toByteArray()

        private fun indexMetaKey(c: Collection, path: String) = indexMetaPrefix(c) + path.toByteArray(Charsets.UTF_8)

        /** m/v/<컬렉션 id>: 인덱스가 생길 때마다 바뀐다. */
        private fun indexVersionKey(c: Collection) = "m/v/".toByteArray() + u32(c.id)

        private fun indexEntry(indexId: Int, doc: JsonObject, path: String, docId: String): ByteArray? {
            val encoded = doc.at(path)?.let(IndexKey::encode) ?: return null
            return prefix("x/", indexId) + encoded + docId.toByteArray(Charsets.UTF_8)
        }

        private fun nextId(): Int = kv.get(ID_COUNTER)?.let(::readU32) ?: 1

        private fun collectionMetaKey(name: String) = "m/c/".toByteArray() + name.toByteArray(Charsets.UTF_8)
    }

    companion object {
        private val ID_COUNTER = "m/next".toByteArray()

        internal fun matches(doc: JsonObject, filter: Filter): Boolean = when (filter) {
            is Filter.Eq -> doc.at(filter.path) == filter.value
            is Filter.Exists -> doc.at(filter.path) != null
            is Filter.And -> filter.filters.all { matches(doc, it) }
            is Filter.Range -> {
                val actual = doc.at(filter.path)
                actual != null &&
                    (filter.lower == null || compareSameType(actual, filter.lower.value)?.let { if (filter.lower.inclusive) it >= 0 else it > 0 } == true) &&
                    (filter.upper == null || compareSameType(actual, filter.upper.value)?.let { if (filter.upper.inclusive) it <= 0 else it < 0 } == true)
            }
        }

        /** 같은 타입(숫자끼리, 문자열끼리)만 비교한다. 다르면 null → 조건은 false. */
        private fun compareSameType(a: JsonValue, b: JsonValue): Int? = when {
            a is JsonValue.Num && b is JsonValue.Num -> a.value.compareTo(b.value).let { if (a.value == b.value) 0 else it }
            a is JsonValue.Str && b is JsonValue.Str -> ByteKeys.compare(a.value.toByteArray(), b.value.toByteArray())
            else -> null
        }

        private fun flatten(filter: Filter): List<Filter> = if (filter is Filter.And) filter.filters.flatMap(::flatten) else listOf(filter)

        private fun describe(r: Filter.Range) = listOfNotNull(
            r.lower?.let { (if (it.inclusive) ">= " else "> ") + Json.stringify(it.value) },
            r.upper?.let { (if (it.inclusive) "<= " else "< ") + Json.stringify(it.value) },
        ).joinToString(", ")

        private fun typeTag(value: JsonValue): Byte = IndexKey.encode(value)!![0]

        /** 인덱스 키 = prefix(6 B) + 값 인코딩 + 문서 id. 값 인코딩의 끝을 찾아 문서 id를 떼어 낸다. */
        private fun docIdFromIndexKey(key: ByteArray): String {
            var pos = 6
            when (key[pos++]) {
                IndexKey.TAG_NUMBER -> pos += 8
                IndexKey.TAG_STRING -> {
                    while (!(key[pos] == 0.toByte() && key[pos + 1] == 1.toByte())) pos += if (key[pos] == 0.toByte()) 2 else 1
                    pos += 2
                }
                else -> Unit
            }
            return String(key, pos, key.size - pos, Charsets.UTF_8)
        }

        private fun prefix(tag: String, id: Int) = tag.toByteArray() + u32(id)

        /** [prefix]로 시작하는 모든 키보다 큰 가장 작은 키 (scan의 상한). */
        internal fun prefixEnd(prefix: ByteArray): ByteArray {
            val end = prefix.copyOf()
            for (i in end.indices.reversed()) {
                if (end[i] != 0xFF.toByte()) {
                    end[i] = (end[i] + 1).toByte()
                    return end.copyOf(i + 1)
                }
            }
            return ByteArray(0).also { error("모든 바이트가 0xFF인 접두사는 상한을 만들 수 없습니다") }
        }

        private fun u32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

        private fun readU32(bytes: ByteArray): Int = ByteBuffer.wrap(bytes).getInt()
    }
}
