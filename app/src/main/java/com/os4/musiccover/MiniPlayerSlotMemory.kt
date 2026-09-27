package com.os4.musiccover

/** Physical homes belong to island identities, not to the current primary/extra view roles. */
internal class MiniPlayerSlotMemory {
    enum class Slot { CENTRE, LEFT, RIGHT }

    private val homes = linkedMapOf<String, Slot>()

    fun home(key: String?): Slot? = homes[key]

    fun reconcile(keys: Collection<String>, centre: String?, preferLeft: Boolean) {
        homes.keys.retainAll(keys.toSet())
        if (centre != null && centre in keys && centre !in homes && Slot.CENTRE !in homes.values) {
            homes[centre] = Slot.CENTRE
        }
        val sides = if (preferLeft) listOf(Slot.LEFT, Slot.RIGHT) else listOf(Slot.RIGHT, Slot.LEFT)
        for (key in keys) {
            if (key == centre || key in homes) continue
            val free = sides.firstOrNull { it !in homes.values } ?: break
            homes[key] = free
        }
    }

    /** Only an explicit page swipe may replace a physical slot's owner. */
    fun page(centre: String, visible: List<String>, preferLeft: Boolean) {
        val previousCentre = homes.entries.firstOrNull { it.value == Slot.CENTRE }?.key
        val previousSide = homes.remove(centre)
        if (previousCentre != null && previousCentre != centre) {
            homes.remove(previousCentre)
            if (previousSide == Slot.LEFT || previousSide == Slot.RIGHT) homes[previousCentre] = previousSide
        }
        homes[centre] = Slot.CENTRE
        reconcile(visible, centre, preferLeft)
    }

    fun clear() = homes.clear()
}
