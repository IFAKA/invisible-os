package com.faka.invisible

/**
 * Pure: how much an app is used, which decides where it sits in the tree ([AppTree]).
 * weight = prior + month launches + 2 * week launches + 5 * own picks.
 * Launches, not minutes: minutes are inflated by anything that keeps the screen or a foreground service alive
 * (this app, sync tools, a terminal), and one long video says little about how often you reach for an app.
 * The cost on the ear is per open, so opens are what we count. The week counts again (recent use wins) and a pick
 * in this OS counts most (it is exactly the action the tree serves). Notifications never count.
 */
object AppWeights {
    const val SELF = "com.faka.invisible"
    const val PRIOR = 1.0           // every app, so an unused one still has a stable place
    const val PRIOR_BUILTIN = 3.0   // OS programs (hub:*): no launch history, but they are what the OS is for
    const val PICK = 5.0

    fun prior(pkg: String) = if (pkg.startsWith("hub:")) PRIOR_BUILTIN else PRIOR

    fun weight(pkg: String, monthLaunches: Int, weekLaunches: Int, picks: Int): Double =
        prior(pkg) + monthLaunches + 2 * weekLaunches + PICK * picks
}
