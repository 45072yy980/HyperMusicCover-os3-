package com.os4.musiccover

/** Immutable values prevent reused OEM argument arrays from changing the previous recipe. */
internal object MiniPlayerMaterialState {
    fun snapshot(value: Any?): Any? = when (value) {
        is IntArray -> value.toList()
        is FloatArray -> value.toList()
        is LongArray -> value.toList()
        is DoubleArray -> value.toList()
        is BooleanArray -> value.toList()
        is ByteArray -> value.toList()
        is ShortArray -> value.toList()
        is CharArray -> value.toList()
        is Array<*> -> value.map(::snapshot)
        is List<*> -> value.map(::snapshot)
        else -> value
    }

    fun replacesLayer(previous: String?, next: String): Boolean =
        previous != null && previous.substringBefore('#') != next.substringBefore('#')
}
