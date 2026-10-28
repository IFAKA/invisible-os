package com.faka.invisible

/**
 * Pure: which apps the owner does not use, so they leave the spoken tree (Find and Notifications still have them).
 * opens = RAW month launches + 2 * week launches + own picks (a learning reset does not count here, so it never empties the tree).
 * Stored: plumbing always; else (when usage is known) an app with 0 opens, or one that does the same job as a much-used app
 * (opens * 5 < the top opens of that job). Never stored: always-needed apps, pinned, installed under 14 days ago.
 */
object Storeroom {
    const val NEW_DAYS = 14L
    const val JOB_RATIO = 5
    private val ALWAYS = setOf("com.android.camera", "com.android.deskclock", "com.google.android.deskclock", "com.google.android.dialer", "com.android.dialer",
        "com.google.android.contacts", "com.android.settings", "com.android.vending")

    fun always(pkg: String) = pkg in ALWAYS || ".camera" in pkg

    fun opens(pkg: String, use: Map<String, AppUse>, picks: (String) -> Int): Int { val u = use[pkg]; return (u?.month ?: 0) + 2 * (u?.week ?: 0) + picks("app:$pkg") }

    /** The package without its first two parts (type and maker): `app.morphe.android.apps.youtube.music` and `com.google.android.apps.youtube.music` both give `android.apps.youtube.music`. */
    private fun core(pkg: String): String? = pkg.split('.').drop(2).takeIf { it.size >= 2 }?.joinToString(".")

    /** pkg -> job. The table first; else the job of a copy of the same app (a patched or cloned app has another maker in front, same core); else the shared core as its own job. */
    fun jobs(installed: List<InstalledApp>): Map<String, String> {
        val family = installed.map { it.pkg }.filter { it !in AppWeights.PLUMBING }.groupBy { core(it) ?: it }.filter { it.value.size > 1 }
        val out = HashMap<String, String>()
        for ((core, g) in family) { val job = g.firstNotNullOfOrNull { AppCategories.JOBS[it] } ?: "app:$core"; g.forEach { out[it] = job } }
        for (a in installed) AppCategories.JOBS[a.pkg]?.let { out[a.pkg] = it }
        return out
    }

    /** pkg -> why it is stored, for the apps that are. */
    fun decide(installed: List<InstalledApp>, use: Map<String, AppUse>, picks: (String) -> Int, pinned: Set<String>, now: Long): Map<String, String> {
        val opens = installed.associate { it.pkg to opens(it.pkg, use, picks) }
        val jobs = jobs(installed)
        val top = installed.filter { it.pkg !in AppWeights.PLUMBING && it.pkg in jobs }.groupBy { jobs.getValue(it.pkg) }.mapValues { (_, g) -> g.maxOf { opens.getValue(it.pkg) } }
        val out = LinkedHashMap<String, String>()
        for (a in installed) {
            val p = a.pkg; val n = opens.getValue(p)
            if (p in AppWeights.PLUMBING) { out[p] = "plumbing"; continue }
            if (use.isEmpty()) continue
            if (always(p) || p in pinned || (a.installedAt > 0 && now - a.installedAt < NEW_DAYS * 86_400_000L)) continue
            if (n == 0) { out[p] = "0 opens"; continue }
            val job = jobs[p] ?: continue
            val t = top.getValue(job)
            if (n * JOB_RATIO < t) out[p] = "same job ($job): $n opens, top $t"
        }
        return out
    }
}
