package com.os4.musiccover

/** Tracks what was actually drawn, independently of primary/extra controller roles. */
internal class MiniPlayerSideContinuity<T : Any> {
    private val drawn = java.util.WeakHashMap<T, String>()

    fun record(view: T?, key: String?, visible: Boolean) {
        if (view == null) return
        if (visible && key != null) drawn[view] = key else drawn.remove(view)
    }

    fun keeps(view: T?, key: String?): Boolean = view != null && key != null && drawn[view] == key

    companion object {
        fun <T : Any> owns(owner: T?, ownerKey: String?, current: T?, currentKey: String?): Boolean =
            owner != null && owner === current && ownerKey != null && ownerKey == currentKey
    }
}
