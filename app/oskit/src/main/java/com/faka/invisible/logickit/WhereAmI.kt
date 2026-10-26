package com.faka.invisible.logickit

/** What a geocoder found. Every part may be null or blank. */
data class Place(val street: String?, val number: String?, val area: String?, val city: String?, val feature: String? = null)

/** Pure wording of "Where am I": the next turn first (if navigating), then street and number, area, city. */
object WhereAmI {
    /** Sentences to say, in order; empty = nothing known (the caller says "Location not found"). Parts that repeat are said once. */
    fun lines(place: Place?, turn: String?): List<String> {
        val out = ArrayList<String>()
        turn?.squash()?.takeIf { it.isNotEmpty() }?.let { out += it }
        if (place == null) return out
        fun clean(s: String?) = s?.squash().orEmpty()
        val street = clean(place.street); val number = clean(place.number)
        val area = clean(place.area); val city = clean(place.city); val feature = clean(place.feature)
        val where = when {
            street.isNotEmpty() && number.isNotEmpty() -> "$street $number"
            street.isNotEmpty() -> street
            feature.isNotEmpty() && !feature.equals(city, true) -> "Near $feature"
            else -> ""
        }
        if (where.isNotEmpty()) out += where
        if (area.isNotEmpty() && !area.equals(city, true) && !area.equals(street, true)) out += area
        if (city.isNotEmpty()) out += city
        return out
    }

    /** Whether the lines hold a place (a turn alone is not a place). */
    fun hasPlace(place: Place?): Boolean = lines(place, null).isNotEmpty()
}
