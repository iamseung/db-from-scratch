package dev.mydb.kv

sealed interface WriteOp {
    val key: ByteArray

    class Put(override val key: ByteArray, val value: ByteArray) : WriteOp

    class Delete(override val key: ByteArray) : WriteOp
}
