package com.faka.invisible

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent

/** The phone side of the tree: which apps exist, how often each was opened, and starting one. No audio, nothing is listened to. */
object DeviceApps {
    private const val DAY = 24L * 60 * 60 * 1000
    private const val STALE_MS = 30_000L

    /** The last answer of a slow phone query (about 300 ms for both). A wake reads it at once and a background thread renews it, so a new app or launch shows one wake later. */
    private class Cached<T>(private val load: () -> T) {
        @Volatile private var value: T? = null
        @Volatile private var at = 0L
        @Volatile private var busy = false
        fun get(): T {
            val v = value
            if (v == null) return renew()
            if (System.currentTimeMillis() - at > STALE_MS && !busy) { busy = true; Thread { try { renew() } finally { busy = false } }.apply { isDaemon = true; name = "deviceapps" }.start() }
            return v
        }
        @Synchronized fun renew(): T = load().also { value = it; at = System.currentTimeMillis() }
    }
    private var installedCache: Cached<List<InstalledApp>>? = null
    private var useCache: Cached<Map<String, AppUse>>? = null

    /** Fill both caches off the main thread (service start), so even the first wake finds them ready. */
    fun warm(c: Context) { Thread { installedCached(c).get(); useCached(c).get() }.apply { isDaemon = true; name = "deviceapps-warm" }.start() }
    @Synchronized private fun installedCached(c: Context) = installedCache ?: Cached { installed(c) }.also { installedCache = it }
    @Synchronized private fun useCached(c: Context) = useCache ?: Cached { use(c) }.also { useCache = it }
    fun installedNow(c: Context): List<InstalledApp> = installedCached(c).get()
    fun useNow(c: Context): Map<String, AppUse> = useCached(c).get()

    fun installed(c: Context): List<InstalledApp> {
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != c.packageName }
            .map { (p, l) -> InstalledApp(p, l, try { pm.getApplicationInfo(p, 0).category } catch (e: Exception) { -1 }) }
    }

    /** Foreground moves per app over 30 and 7 days (needs the usage access grant; without it every app counts 0 and picks alone order the tree). */
    fun use(c: Context, now: Long = System.currentTimeMillis()): Map<String, AppUse> {
        val ev = c.getSystemService(UsageStatsManager::class.java)?.queryEvents(now - 30 * DAY, now) ?: return emptyMap()
        val month = HashMap<String, Int>(); val week = HashMap<String, Int>(); val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            month.merge(e.packageName, 1, Int::plus)
            if (e.timeStamp >= now - 7 * DAY) week.merge(e.packageName, 1, Int::plus)
        }
        return month.mapValues { (p, m) -> AppUse(m, week[p] ?: 0) }
    }

    /** Starts the app from the service context. Not verified with the screen off. */
    fun open(c: Context, pkg: String): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        return try { c.startActivity(i); true } catch (e: Exception) { false }
    }
}
