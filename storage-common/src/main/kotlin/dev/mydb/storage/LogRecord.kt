package dev.mydb.storage

import dev.mydb.storage.LogRecordCodec.MAX_KEY_SIZE
import dev.mydb.storage.LogRecordCodec.MAX_VALUE_SIZE

/** 프레임 payload에 담기는 레코드. 디스크 포맷이라 kv-api의 WriteOp와 따로 둔다. */
sealed interface LogRecord {
    class Put(override val key: ByteArray, val value: ByteArray) : BatchableRecord {
        init {
            require(key.size <= MAX_KEY_SIZE) { "키는 최대 $MAX_KEY_SIZE 바이트입니다: ${key.size}" }
            require(value.size <= MAX_VALUE_SIZE) { "값은 최대 $MAX_VALUE_SIZE 바이트입니다: ${value.size}" }
        }

        override fun equals(other: Any?) =
            other is Put && key.contentEquals(other.key) && value.contentEquals(other.value)

        override fun hashCode() = 31 * key.contentHashCode() + value.contentHashCode()

        override fun toString() = "Put(key=${key.size}B, value=${value.size}B)"
    }

    /** tombstone. 이전 세그먼트의 PUT이 재시작 때 되살아나지 않게 한다. */
    class Delete(override val key: ByteArray) : BatchableRecord {
        init {
            require(key.size <= MAX_KEY_SIZE) { "키는 최대 $MAX_KEY_SIZE 바이트입니다: ${key.size}" }
        }

        override fun equals(other: Any?) = other is Delete && key.contentEquals(other.key)

        override fun hashCode() = key.contentHashCode()

        override fun toString() = "Delete(key=${key.size}B)"
    }

    /** 프레임 하나에 담기므로 전부 적용되거나 전부 무시된다. */
    data class Batch(val ops: List<BatchableRecord>) : LogRecord {
        init {
            require(ops.isNotEmpty()) { "빈 배치는 기록하지 않습니다" }
        }
    }

    /** merge 파일의 완료 표식. 이 프레임이 온전하면 merge가 확정된 것이다. */
    data class MergeFooter(val entryCount: Long) : LogRecord {
        init {
            require(entryCount >= 0) { "entryCount는 0 이상이어야 합니다: $entryCount" }
        }
    }
}

/** 배치 안에 들어갈 수 있는 레코드. 배치 중첩을 타입으로 막는다. */
sealed interface BatchableRecord : LogRecord {
    val key: ByteArray
}
