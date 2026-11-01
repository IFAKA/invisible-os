package com.faka.invisible.claude

import java.io.IOException

/** Is the laptop there? One quick GET /rooms with the token, and the plain-words reason when it is not. Pure except [probe]. */
object LaptopLink {
    /** The result of one check: [ok] with the number of windows, or the [why] (English; the screen translates it). */
    class Probe(val ok: Boolean, val windows: Int, val why: String)

    const val WRONG_TOKEN = "The token does not match. Add this laptop again (run: sh bridge/setup.sh)."
    const val NO_ANSWER = "No answer. Is the laptop on, on the same Wi-Fi (or Tailscale), with the bridge running? On it run: sh bridge/setup.sh"

    /** Why a call failed, in plain words. HTTP 401/403 = the laptop answered but refused the token. */
    fun why(e: Exception): String {
        val m = e.message.orEmpty()
        return if (m.contains("HTTP 401") || m.contains("HTTP 403")) WRONG_TOKEN else NO_ANSWER
    }

    /** Blocking: call it off the main thread. Never throws. */
    fun probe(c: Computer): Probe =
        if (c.url.isBlank() || c.token.isBlank()) Probe(false, 0, NO_ANSWER)
        else try { Probe(true, BridgeClient(c.url, c.token).rooms().size, "") }
        catch (e: IOException) { Probe(false, 0, why(e)) } catch (e: RuntimeException) { Probe(false, 0, why(e)) }

    enum class Tone { NONE, CHECKING, OK, DOWN }

    /** The line under the status card of the home screen: [count] laptops paired, [ok] = the last check of the active one (null = not checked yet). */
    fun tone(count: Int, ok: Boolean?) = when { count == 0 -> Tone.NONE; ok == null -> Tone.CHECKING; ok -> Tone.OK; else -> Tone.DOWN }
    fun headline(t: Tone) = when (t) { Tone.NONE -> "No laptop yet"; Tone.CHECKING -> "Laptop: checking..."; Tone.OK -> "Laptop: connected"; Tone.DOWN -> "Laptop: not reachable" }

    /** The name of the page and the home row: "Laptop", or "Laptops" when several are paired. */
    fun title(count: Int) = if (count > 1) "Laptops" else "Laptop"
}
