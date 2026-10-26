package com.faka.invisible.logickit

import com.faka.invisible.AppLeaf

/**
 * The phone has no screen, so two entries with the same spoken name cannot be told apart. This makes every leaf name unique:
 * an OS program ("hub:...") keeps its name; an app that repeats a name (or a group or sub-group name in [reserved]) gets its maker in front
 * ("Xiaomi Calculator"); two of the same maker, or a maker we do not know, get a number ("Docs 2"). Pure and stable: the same leaves always give the same names.
 */
object UniqueNames {
    private val MAKERS = listOf("miui" to "Xiaomi", "xiaomi" to "Xiaomi", "google" to "Google", "microsoft" to "Microsoft", "azure" to "Microsoft", "salesforce" to "Salesforce",
        "samsung" to "Samsung", "android" to "Android", "amazon" to "Amazon", "orange" to "Orange", "morphe" to "Morphe", "revanced" to "ReVanced", "mi" to "Xiaomi")

    fun maker(pkg: String): String? = pkg.lowercase().split('.').drop(1).firstNotNullOfOrNull { seg -> MAKERS.firstOrNull { it.first == seg }?.second }

    private fun key(s: String) = s.squash().lowercase()

    fun apply(leaves: List<AppLeaf>, reserved: Set<String> = emptySet()): List<AppLeaf> {
        val taken = HashMap<String, Int>()   // name -> how many entries use it
        for (l in leaves) taken.merge(key(l.label), 1, Int::plus)
        val res = reserved.map(::key).toSet()
        val renamed = HashMap<String, String>()
        // 1. the maker, for apps whose name is used twice or is a group name
        for (l in leaves) {
            val clash = (taken[key(l.label)] ?: 0) > 1 || key(l.label) in res
            if (l.pkg.startsWith("hub:") || !clash) continue
            val m = maker(l.pkg)
            if (m != null && !key(l.label).startsWith(key(m))) renamed[l.pkg] = "$m ${l.label}"
        }
        // 2. what is still repeated gets a number, in a fixed order; the first keeps the plain name
        val names = leaves.associate { it.pkg to (renamed[it.pkg] ?: it.label) }
        val count = HashMap<String, Int>(); for (n in names.values) count.merge(key(n), 1, Int::plus)
        val seen = HashMap<String, Int>()
        val out = HashMap<String, String>()
        for (l in leaves.sortedWith(compareBy<AppLeaf> { !it.pkg.startsWith("hub:") }.thenByDescending { it.weight }.thenBy { it.pkg })) {
            val n = names.getValue(l.pkg); val k = key(n)
            val nth = seen.merge(k, 1, Int::plus)!!
            val clash = (count[k] ?: 0) > 1 || (k in res && !l.pkg.startsWith("hub:"))
            out[l.pkg] = if (!clash || (nth == 1 && !(k in res && !l.pkg.startsWith("hub:")))) n else "$n ${nth + if (k in res && !l.pkg.startsWith("hub:")) 1 else 0}"
        }
        return leaves.map { it.copy(label = out.getValue(it.pkg)) }
    }
}
