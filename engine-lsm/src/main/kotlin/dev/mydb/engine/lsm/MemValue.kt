package dev.mydb.engine.lsm

/** memtable·SSTable·병합 반복자가 다루는 값. LSM에서 삭제는 "삭제했다는 값"을 쓰는 것이다. */
sealed interface MemValue {
    class Put(val value: ByteArray) : MemValue {
        override fun equals(other: Any?) = other is Put && value.contentEquals(other.value)

        override fun hashCode() = value.contentHashCode()

        override fun toString() = "Put(${value.size}B)"
    }

    data object Tombstone : MemValue
}

class Entry(val key: ByteArray, val value: MemValue) {
    override fun equals(other: Any?) = other is Entry && key.contentEquals(other.key) && value == other.value

    override fun hashCode() = 31 * key.contentHashCode() + value.hashCode()

    override fun toString() = "Entry(${String(key)}, $value)"
}
