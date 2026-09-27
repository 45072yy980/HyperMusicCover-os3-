package com.os4.musiccover

internal object MiniPlayerReturnPolicy {
    /** The latest returned media/focus island takes the centre; the notification stack stays beside it. */
    fun returnsToPill(rowEmpty: Boolean, isNotificationStack: Boolean): Boolean =
        rowEmpty || !isNotificationStack

    /** Ordinary notification aggregation is a fallback centre, below media/focus candidates. */
    fun chooseCentre(keys: Collection<String>, notificationStack: String,
                     takesPlace: (String, String) -> Boolean): String? {
        val important = keys.filter { it != notificationStack }
        return (important.ifEmpty { keys.toList() }).fold<String, String?>(null) { best, key ->
            if (best == null || takesPlace(best, key)) key else best
        }
    }
}
