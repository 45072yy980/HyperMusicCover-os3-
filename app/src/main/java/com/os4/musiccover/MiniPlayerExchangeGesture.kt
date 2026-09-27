package com.os4.musiccover

/** Same projected half-way threshold for a held exchange and a newly opened card. */
internal object MiniPlayerExchangeGesture {
    fun commit(progress: Float, velocity: Float, cancelled: Boolean): Boolean =
        !cancelled && progress + velocity * 0.18f > 0.5f
}
