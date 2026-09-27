package com.os4.musiccover

internal object MiniPlayerReturnPolicy {
    /** The latest returned media/focus island takes the centre; the notification stack stays beside it. */
    fun returnsToPill(rowEmpty: Boolean, isNotificationStack: Boolean): Boolean =
        rowEmpty || !isNotificationStack
}
