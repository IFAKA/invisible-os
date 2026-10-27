package com.faka.invisible

import com.faka.invisible.logickit.*

import com.faka.invisible.voicekit.*

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import java.net.HttpURLConnection
import java.net.URL

/** Pure: Open-Meteo answer (JSON text) to ONE spoken sentence. No JSON library: the answer is flat and small. */
object Weather {
    fun url(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(java.util.Locale.US, lat, lon) +
        "&current=temperature_2m,weather_code&hourly=precipitation_probability&forecast_hours=12&timezone=auto"

    private fun after(json: String, key: String): String? = json.indexOf("\"$key\":{").takeIf { it >= 0 }?.let { json.substring(it + key.length + 4) }
    private fun number(block: String, key: String): Double? = Regex("\"$key\":\\s*(-?[0-9.]+)").find(block)?.groupValues?.get(1)?.toDoubleOrNull()
    private fun numbers(block: String, key: String): List<Double>? =
        Regex("\"$key\":\\s*\\[([^\\]]*)]").find(block)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }

    /** WMO codes of rain, showers, thunderstorm. */
    private fun rainCode(c: Int) = c in 51..67 || c in 80..82 || c in 95..99

    /** First hour (0 = now) with a rain chance of 50 percent or more; null when none in the data. */
    fun rainIn(hourly: List<Double>) = hourly.indexOfFirst { it >= 50 }.takeIf { it >= 0 }

    /** The sentence, or null if the JSON has no temperature. */
    fun sentence(json: String): Phrase? {
        val cur = after(json, "current") ?: return null
        val temp = number(cur, "temperature_2m") ?: return null
        val code = number(cur, "weather_code")?.toInt() ?: -1
        val rain = after(json, "hourly")?.let { h -> numbers(h, "precipitation_probability") }?.let { rainIn(it) }
        return Voice.weather(Math.round(temp).toInt(), if (rainCode(code)) 0 else rain)
    }
}

/** Pure: a Google Maps navigation notification (title, text, subText strings) as ONE spoken sentence. */
object Navigation {
    const val PACKAGE = "com.google.android.apps.maps"
    /** Title and text are the instruction and the distance (Maps orders them either way); the sub text (arrival time) is used only if both are empty. Null if nothing to say. */
    fun sentence(title: String?, text: String?, subText: String?): String? {
        fun clean(s: String?) = s?.squash()?.trimEnd('.', ',', ' ').orEmpty()
        val main = listOf(clean(title), clean(text)).filter { it.isNotEmpty() }.distinct()
        val parts = main.ifEmpty { listOf(clean(subText)).filter { it.isNotEmpty() } }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ").let { if (it.length > 160) it.take(160).trimEnd() else it } + "."
    }
}

/** Pure: the next calendar event as ONE spoken sentence. */
object Agenda {
    /** [offsetMs] = the time zone offset of the phone, to read the clock time of [beginMs]. */
    fun sentence(title: String?, beginMs: Long, nowMs: Long, offsetMs: Long): Phrase {
        val name = title?.squash().orEmpty().ifEmpty { Voice.EVENT_UNNAMED }.let { if (it.length > 60) it.take(60).trimEnd() else it }
        val mins = ((beginMs - nowMs) / 60_000).toInt()
        if (mins <= 0) return Voice.eventNow(name)
        val dayMin = Math.floorMod((beginMs + offsetMs) / 60_000, 1440L).toInt()
        return Voice.eventLater(name, dayMin / 60, dayMin % 60, mins)
    }
}

/** Android side of the two lookups. Both run off the main thread and call [done] on that same worker thread. */
object PhoneInfo {
    fun weather(ctx: Context, done: (Phrase) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { Needs.record(ctx, "location"); done(Voice.NEED_LOCATION); return }
        Thread {
            done(try {
                val lm = ctx.getSystemService(LocationManager::class.java)
                val loc = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: SecurityException) { null } }.maxByOrNull { it.time }
                if (loc == null) Voice.WEATHER_FAILED else {
                    val c = URL(Weather.url(loc.latitude, loc.longitude)).openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 4000
                    try { Weather.sentence(c.inputStream.bufferedReader().readText()) ?: Voice.WEATHER_FAILED } finally { c.disconnect() }
                }
            } catch (e: Exception) { Voice.WEATHER_FAILED })
        }.start()
    }

    fun nextEvent(ctx: Context, done: (Phrase) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) { Needs.record(ctx, "calendar"); done(Voice.NEED_CALENDAR); return }
        Thread {
            done(try {
                val now = System.currentTimeMillis()
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, now); ContentUris.appendId(it, now + 24 * 3600_000L) }.build()
                val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
                var best: Pair<String?, Long>? = null
                ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                    while (best == null && c.moveToNext()) if (c.getInt(2) == 0) best = c.getString(0) to c.getLong(1)
                }
                best?.let { Agenda.sentence(it.first, it.second, now, java.util.TimeZone.getDefault().getOffset(it.second).toLong()) } ?: Voice.NO_EVENTS
            } catch (e: Exception) { Voice.CALENDAR_FAILED })
        }.start()
    }
}
