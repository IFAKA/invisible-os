package com.faka.invisible.now

/** Pure rules of Now and Quick switches. */
object NowCore {
    enum class Fact { TIME, BATTERY, EVENT, WEATHER }

    /** The facts Now can say: time and battery always; the next event only with the Calendar permission; the weather only with a location permission. */
    fun facts(calendar: Boolean, location: Boolean): List<Fact> =
        listOf(Fact.TIME, Fact.BATTERY) + (if (calendar) listOf(Fact.EVENT) else emptyList()) + (if (location) listOf(Fact.WEATHER) else emptyList())

    /** The fact after [index]; null = it was the last (Now leaves). */
    fun next(index: Int, size: Int): Int? = if (index + 1 < size) index + 1 else null

    /** The fact before [index]; null = it was the first (Now leaves). */
    fun back(index: Int): Int? = if (index > 0) index - 1 else null
}

object SwitchesCore {
    enum class Item { VOLUME_UP, VOLUME_DOWN, DND, TORCH, RING, WIFI, BLUETOOTH }

    val ITEMS = Item.values().toList()

    /** Volume as a whole percent of [max]. */
    fun percent(level: Int, max: Int): Int = if (max <= 0) 0 else (level.coerceIn(0, max) * 100 + max / 2) / max

    /** One volume step of the stream; the level stays between 0 and [max]. */
    fun stepped(level: Int, max: Int, up: Boolean): Int = (level + if (up) 1 else -1).coerceIn(0, max)

    /** Do not disturb flips: on (alarms only) unless it is already on. [filterAll] = the phone now lets everything through. */
    fun dndNowOn(filterAll: Boolean): Boolean = filterAll
}
