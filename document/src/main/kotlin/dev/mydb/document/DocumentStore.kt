package dev.mydb.document

import dev.mydb.kv.ByteKeys
import dev.mydb.kv.KvEngine
import dev.mydb.kv.WriteOp
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
 * 문서와 그 인덱스 항목은 writeBatch 하나로 바뀐다. 쓰기는 "옛 문서 읽기 → 배치 쓰기"라 @Synchronized로 묶는다.
 */
class DocumentStore(private val engine: KvEngine) {

    @Synchronized
    fun createCollection(name: String): Collection {
        require(name.isNotEmpty() && '/' !in name) { "컬렉션 이름은 비어 있지 않고 '/'를 포함하지 않아야 합니다: $name" }
        check(engine.get(collectionMetaKey(name)) == null) { "이미 있는 컬렉션입니다: $name" }
        val id = nextId()
        engine.writeBatch(listOf(WriteOp.Put(ID_COUNTER, u32(id + 1)), WriteOp.Put(collectionMetaKey(name), u32(id))))
        return Collection(name, id)
    }

    @Synchronized
    fun collection(name: String): Collection? = engine.get(collectionMetaKey(name))?.let { Collection(name, readU32(it)) }

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

    /**
     * 컬렉션별 인덱스 정의 캐시 (경로 → 인덱스 id). 쓰기마다 메타데이터를 범위 scan하면,
     * scan이 전체 키에 비례하는 M1 Log에서 삽입 하나가 O(N)이 됐다 (M5 측정에서 발견). createIndex 때만 바뀐다.
     */
    private val indexCache = HashMap<Int, List<Pair<String, Int>>>()

    inner class Collection internal constructor(val name: String, val id: Int) {
        private val docPrefix = prefix("d/", id)

        fun insert(docId: String, doc: JsonObject) = synchronized(this@DocumentStore) {
            check(engine.get(docKey(docId)) == null) { "이미 있는 문서입니다: $name/$docId" }
            write(docId, doc)
        }

        /** 없으면 삽입, 있으면 통째로 교체한다. */
        fun replace(docId: String, doc: JsonObject) = synchronized(this@DocumentStore) { write(docId, doc) }

        fun get(docId: String): JsonObject? = synchronized(this@DocumentStore) {
            engine.get(docKey(docId))?.let { Json.parseObject(String(it, Charsets.UTF_8)) }
        }

        fun delete(docId: String): Boolean = synchronized(this@DocumentStore) {
            val old = get(docId) ?: return@synchronized false
            val ops = mutableListOf<WriteOp>(WriteOp.Delete(docKey(docId)))
            for ((path, indexId) in indexes()) {
                indexEntry(indexId, old, path, docId)?.let { ops += WriteOp.Delete(it) }
            }
            engine.writeBatch(ops)
            true
        }

        /** 기존 문서로 인덱스를 채운다. 인덱스 정의와 항목을 한 배치로 쓴다. */
        fun createIndex(path: String) = synchronized(this@DocumentStore) {
            require(path.isNotEmpty()) { "필드 경로가 비어 있습니다" }
            check(engine.get(indexMetaKey(path)) == null) { "이미 있는 인덱스입니다: $name.$path" }
            val indexId = nextId()
            val ops = mutableListOf<WriteOp>(WriteOp.Put(ID_COUNTER, u32(indexId + 1)), WriteOp.Put(indexMetaKey(path), u32(indexId)))
            for (doc in scanAll()) indexEntry(indexId, doc.body, path, doc.id)?.let { ops += WriteOp.Put(it, ByteArray(0)) }
            engine.writeBatch(ops)
            indexCache.remove(id)
        }

        /** 필터에 맞는 문서를 문서 id 순으로. 인덱스를 쓸 수 있으면 IXSCAN, 아니면 COLLSCAN. */
        fun find(filter: Filter): List<Document> = synchronized(this@DocumentStore) {
            val plan = plan(filter)
            val candidates = if (plan.indexId == null) {
                scanAll()
            } else {
                indexScan(plan).mapNotNull { docId -> get(docId)?.let { Document(docId, it) } }
            }
            candidates.filter { matches(it.body, filter) }.sortedBy { it.id }
        }

        fun explain(filter: Filter): String = synchronized(this@DocumentStore) { plan(filter).description }

        /** 테스트용: 이 경로의 인덱스 항목 수. 고아 항목이 없으면 "그 필드가 스칼라인 문서 수"와 같다. */
        fun indexEntryCount(path: String): Int = synchronized(this@DocumentStore) {
            val indexId = engine.get(indexMetaKey(path))?.let(::readU32) ?: return@synchronized 0
            val p = prefix("x/", indexId)
            engine.scan(p, prefixEnd(p)).use { it.asSequence().count() }
        }

        private fun write(docId: String, doc: JsonObject) {
            require(docId.isNotEmpty()) { "문서 id가 비어 있습니다" }
            val old = engine.get(docKey(docId))?.let { Json.parseObject(String(it, Charsets.UTF_8)) }
            val ops = mutableListOf<WriteOp>(WriteOp.Put(docKey(docId), Json.stringify(doc).toByteArray(Charsets.UTF_8)))
            for ((path, indexId) in indexes()) {
                val oldEntry = old?.let { indexEntry(indexId, it, path, docId) }
                val newEntry = indexEntry(indexId, doc, path, docId)
                if (oldEntry != null && newEntry != null && oldEntry.contentEquals(newEntry)) continue
                oldEntry?.let { ops += WriteOp.Delete(it) }
                newEntry?.let { ops += WriteOp.Put(it, ByteArray(0)) }
            }
            engine.writeBatch(ops)
        }

        private fun scanAll(): List<Document> =
            engine.scan(docPrefix, prefixEnd(docPrefix)).use { iterator ->
                iterator.asSequence().map { e ->
                    Document(String(e.key, docPrefix.size, e.key.size - docPrefix.size, Charsets.UTF_8), Json.parseObject(String(e.value, Charsets.UTF_8)))
                }.toList()
            }

        private fun indexes(): List<Pair<String, Int>> = indexCache.getOrPut(id) {
            val p = indexMetaPrefix()
            engine.scan(p, prefixEnd(p)).use { iterator ->
                iterator.asSequence().map { e -> String(e.key, p.size, e.key.size - p.size, Charsets.UTF_8) to readU32(e.value) }.toList()
            }
        }

        private inner class Plan(val indexId: Int?, val from: ByteArray, val to: ByteArray, val description: String)

        /** 인덱스가 있는 경로의 Eq를 우선, 없으면 Range 하나. 나머지 조건은 find가 후처리한다. */
        private fun plan(filter: Filter): Plan {
            val conditions = flatten(filter)
            val indexed = indexes().toMap()
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
            return engine.scan(plan.from, plan.to).use { iterator ->
                iterator.asSequence().map { e -> docIdFromIndexKey(e.key) }.toList()
            }
        }

        private fun docKey(docId: String) = docPrefix + docId.toByteArray(Charsets.UTF_8)

        private fun indexMetaPrefix() = "m/i/".toByteArray() + u32(id) + "/".toByteArray()

        private fun indexMetaKey(path: String) = indexMetaPrefix() + path.toByteArray(Charsets.UTF_8)

        private fun indexEntry(indexId: Int, doc: JsonObject, path: String, docId: String): ByteArray? {
            val encoded = doc.at(path)?.let(IndexKey::encode) ?: return null
            return prefix("x/", indexId) + encoded + docId.toByteArray(Charsets.UTF_8)
        }
    }

    private fun nextId(): Int = engine.get(ID_COUNTER)?.let(::readU32) ?: 1

    private fun collectionMetaKey(name: String) = "m/c/".toByteArray() + name.toByteArray(Charsets.UTF_8)

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
