package com.faka.invisible

/**
 * Pure: how much an app is used, which decides where it sits in the tree ([AppTree]).
 * weight = prior + 3 * ln(1 + month launches + 2 * week launches) + 5 * own picks.
 * Launches, not minutes: minutes are inflated by anything that keeps the screen or a foreground service alive
 * (this app, sync tools, a terminal). The log damps thin, noisy history so one app cannot dominate; own picks stay linear and strong,
 * they are the real long-term signal (exactly the action the tree serves). Notifications never count.
 * Launcher and system plumbing is not a deliberate open, so its launches count 0 ([PLUMBING]).
 */
object AppWeights {
    const val SELF = "com.faka.invisible"
    const val PRIOR = 1.0           // every app, so an unused one still has a stable place
    const val PRIOR_BUILTIN = 3.0   // OS programs (hub:*): no launch history, but they are what the OS is for
    const val PICK = 5.0
    const val LAUNCH = 3.0

    val PLUMBING = setOf("com.microsoft.launcher", "com.miui.home", "com.google.android.permissioncontroller", "com.android.intentresolver",
        "com.miui.securitycenter", "com.miui.screenshot", "com.google.android.packageinstaller")

    fun prior(pkg: String) = if (pkg.startsWith("hub:")) PRIOR_BUILTIN else PRIOR

    fun weight(pkg: String, monthLaunches: Int, weekLaunches: Int, picks: Int): Double {
        val opens = if (pkg in PLUMBING) 0 else monthLaunches + 2 * weekLaunches
        return prior(pkg) + LAUNCH * Math.log(1.0 + opens) + PICK * picks
    }
}
