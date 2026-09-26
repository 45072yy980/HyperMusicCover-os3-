package com.os4.musiccover

internal object MiniPlayerReturnPolicy {
    fun returnsToPill(rowEmpty: Boolean, isMusic: Boolean, musicInPill: Boolean,
                      fromPill: Boolean, fromSmall: Boolean): Boolean = when {
        rowEmpty -> true
        // A notification temporarily filled the pill while the media card was expanded.
        // The returned music keeps that seat; the notification resumes the small button.
        !isMusic && musicInPill -> false
        else -> fromPill || isMusic && !fromSmall
    }
}
