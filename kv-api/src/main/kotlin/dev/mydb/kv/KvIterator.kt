package dev.mydb.kv

interface KvIterator : Iterator<KvEntry>, AutoCloseable

class KvEntry(val key: ByteArray, val value: ByteArray)
