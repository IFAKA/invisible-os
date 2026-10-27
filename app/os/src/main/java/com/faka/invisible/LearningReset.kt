package com.faka.invisible

import android.content.SharedPreferences

/**
 * Pure rules for "Reset learning": which picks to forget, and the launch baseline. Android owns the launch counts and cannot clear them,
 * so a reset saves the counts of that moment per app and the tree uses only the launches made after it.
 * [group] = one list of the home tree (Talk, Plan ...); null = everything.
 */
object LearningReset {
    private const val KEY = "learn_base_"

    private fun groupOf(category: String) = category.substringBefore('/')

    /** Is this pick (`app:<pkg>` or `hub:<key>`) in [group]? Other picks (menu options, senders) go only with a full reset. */
    fun matches(group: String?, intent: String, categoryOf: (String) -> String): Boolean = when {
        group == null -> true
        intent.startsWith("app:") -> groupOf(categoryOf(intent.removePrefix("app:"))) == group
        intent.startsWith("hub:") -> groupOf(categoryOf(intent)) == group
        else -> false
    }

    fun saveBaseline(p: SharedPreferences, use: Map<String, AppUse>, pkgs: Collection<String>) {
        val e = p.edit()
        pkgs.forEach { pkg -> use[pkg]?.let { e.putString(KEY + pkg, "${it.month},${it.week}") } }
        e.apply()
    }

    /** Launches counted after the reset; never below 0. */
    fun net(p: SharedPreferences?, pkg: String, u: AppUse): AppUse {
        val b = p?.getString(KEY + pkg, null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return u
        if (b.size != 2) return u
        return AppUse(maxOf(0, u.month - b[0]), maxOf(0, u.week - b[1]))
    }
}
