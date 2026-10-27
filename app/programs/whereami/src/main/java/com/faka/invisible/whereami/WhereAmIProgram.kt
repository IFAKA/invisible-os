package com.faka.invisible.whereami

import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import com.faka.invisible.logickit.Place
import com.faka.invisible.logickit.WhereAmI
import com.faka.invisible.oskit.Program
import com.faka.invisible.oskit.ProgramEntry
import com.faka.invisible.oskit.ProgramHost
import com.faka.invisible.voicekit.Phrase
import java.util.concurrent.Executors

/** What Where am I needs from the OS besides the [ProgramHost]. */
interface WhereServices {
    /** The latest navigation instruction as one sentence, null = not navigating. */
    fun navigation(): String?
}

/**
 * Where am I. Starts by itself: says the next turn (if a navigation app is active), then street and number, area and city
 * from the last known location and the Geocoder. Any press stops it. No microphone; location only.
 */
class WhereAmIProgram(private val services: WhereServices) : Program {
    private lateinit var host: ProgramHost
    @Volatile private var running = false
    private val end = Runnable { finish() }

    override val active get() = running

    override fun start(host: ProgramHost) {
        if (running) return
        this.host = host; running = true
        val turn = services.navigation()
        val ctx = host.context
        val ok = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ok) { say(emptyList(), turn, WhereVoice.NEED_LOCATION); return }
        host.speak(WhereVoice.WAIT)
        val w = host.waiting()
        pool.execute {
            val place = try { lookup() } catch (e: Exception) { host.log("WHERE lookup failed: $e"); null }
            host.handler.post { w(); if (running) say(WhereAmI.lines(place, turn), turn, WhereVoice.NOT_FOUND) }
        }
    }

    private fun say(lines: List<String>, turn: String?, none: Phrase) {
        host.stopSpeech()
        val all = if (lines.isEmpty() && turn != null) listOf(turn) else lines
        if (all.isEmpty()) host.speak(none) else all.forEach { host.speak(Phrase.content(it)) }
        val words = all.sumOf { it.split(' ').size } + 4
        host.later(end, 2000L + words * 450L)
    }

    /** Off the main thread: best last known location, then the Geocoder (a network or offline lookup, can be slow). */
    private fun lookup(): Place? {
        val lm = host.context.getSystemService(LocationManager::class.java)
        val loc: Location = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }.maxByOrNull { it.time } ?: return null
        if (!Geocoder.isPresent()) return null
        @Suppress("DEPRECATION")
        val a: Address = Geocoder(host.context).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return null
        return Place(a.thoroughfare, a.subThoroughfare, a.subLocality ?: a.subAdminArea, a.locality ?: a.adminArea, a.featureName?.takeIf { it != a.subThoroughfare })
    }

    override fun stop() = finish()
    override fun down(time: Long) {}
    override fun up(time: Long) { finish() }

    private fun finish() {
        if (!running) return
        running = false
        host.cancel(end)
        host.stopSpeech()
        host.ended()
    }

    private companion object { val pool = Executors.newSingleThreadExecutor() }
}

object WhereAmIEntry {
    fun entry(services: WhereServices) = ProgramEntry("hub:whereami", WhereVoice.TITLE) { WhereAmIProgram(services) }
}
