package com.os4.musiccover

/** A destination may be revealed only after its contents belong to the incoming island. */
internal class MiniPlayerContentOwnership<T : Any> {
    private val bound = java.util.WeakHashMap<T, String>()
    fun bind(view: T, key: String) { bound[view] = key }
    fun clear(view: T) { bound.remove(view) }
    fun ready(view: T?, key: String?): Boolean = view != null && key != null && bound[view] == key
}
