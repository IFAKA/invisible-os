package com.faka.invisible.claude

/** The profile of a computer: "work" or "personal". It decides which tools a window offers (bridge/tools.py). Pure. */
object Profiles {
    const val WORK = "work"
    const val PERSONAL = "personal"

    /** "work" or "personal"; anything else (not chosen yet) is "". */
    fun normalize(p: String): String = when (p.trim().lowercase()) { WORK -> WORK; PERSONAL -> PERSONAL; else -> "" }
    /** The profile to send to the bridge: not chosen counts as personal. */
    fun effective(c: Computer): String = normalize(c.profile).ifEmpty { PERSONAL }
    /** The name a chip fills in. */
    fun defaultName(p: String): String = if (normalize(p) == WORK) "Work laptop" else "Laptop"

    /** The form of the Add / Rename screen: a name and a profile. */
    data class Draft(val name: String, val profile: String)
    /** A tap on a chip: the name is filled in and the profile is set. */
    fun chip(profile: String): Draft = Draft(defaultName(profile), normalize(profile))
    /** Typing a name changes only the name: the chosen profile stays. */
    fun typed(d: Draft, name: String): Draft = d.copy(name = name)
    /** The bridge's suggestion (GET /info) arrived: it fills the draft only if the owner chose nothing yet and did not type a new name. */
    fun suggested(d: Draft, original: String, suggest: String): Draft =
        if (d.profile.isNotEmpty() || d.name != original || normalize(suggest).isEmpty()) d else chip(suggest)
}
