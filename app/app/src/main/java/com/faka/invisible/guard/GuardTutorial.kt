package com.faka.invisible.guard

import com.faka.invisible.voicekit.GuardVoice
import com.faka.invisible.voicekit.Phrase

/** One thing the tutorial asks the game to do. */
sealed class TStep {
    data class Say(val line: Phrase) : TStep()
    /** Play an enemy; the player is not judged. [ping] = a soft bright ping exactly on each strike beat. */
    data class Demo(val kind: Kind, val ping: Boolean, val goodMs: Double) : TStep()
    /** Play an enemy and judge the player with this window. Nothing is lost in the tutorial. */
    data class Try(val kind: Kind, val ping: Boolean, val goodMs: Double) : TStep()
    object Calibrate : TStep()
    object Finish : TStep()
    /** The player never clicked for a very long time: leave quietly. */
    object Quit : TStep()
}

enum class TryResult { SUCCESS, EARLY, LATE, NONE, PARTIAL, BAIT }

/**
 * The first two minutes (see GAME-GUARD.md). Teach by doing, one idea per step; a step moves on only when the player
 * SUCCEEDS; nothing is punished. Pure: the game asks [next], plays it, then reports with [done].
 */
class GuardTutorial {
    companion object {
        const val WIDE = 250.0
        val BEAT_WINDOWS = listOf(200.0, 170.0, 150.0)
        const val QUIT_AFTER_SILENT = 20
        const val CAP = 8

        /** Turn a settled try into a result. */
        fun classify(o: Outcome): TryResult = when {
            o.kind == Kind.DECOY -> if (o.hurt) TryResult.BAIT else TryResult.SUCCESS
            !o.hurt -> TryResult.SUCCESS
            o.clicks == 0 -> TryResult.NONE
            o.kind == Kind.KNIGHT && (o.perfects + o.goods) > 0 -> TryResult.PARTIAL
            o.early && !o.late -> TryResult.EARLY
            else -> TryResult.LATE
        }
    }

    private enum class Part { LEARN, BEAT, KNIGHT, DECOY, DONE }

    private val q = ArrayDeque<TStep>()
    private var part = Part.LEARN
    private var wins = 0
    private var streak = 0
    private var fails = 0
    private var tries = 0
    private var forced = false
    var silent = 0; private set
    private var last: TStep.Try? = null
    private val hinted = HashSet<String>()
    val finished get() = part == Part.DONE

    init {
        q.addAll(listOf(TStep.Say(GuardVoice.GATE), TStep.Say(GuardVoice.LISTEN), TStep.Demo(Kind.THIEF, true, WIDE),
            TStep.Say(GuardVoice.CLICK_FOURTH), TStep.Try(Kind.THIEF, true, WIDE)))
    }

    fun next(): TStep {
        val s = q.removeFirstOrNull() ?: TStep.Finish
        if (s is TStep.Try) last = s
        if (s is TStep.Finish) part = Part.DONE
        return s
    }

    /** The player clicked while we were talking or demonstrating: drop the talk and go to the practice. */
    fun skipToTry() {
        silent = 0
        while (q.isNotEmpty() && q.first() !is TStep.Try && q.first() !is TStep.Calibrate && q.first() !is TStep.Finish && q.first() !is TStep.Quit) q.removeFirst()
    }

    private fun hint(key: String, line: Phrase) { if (hinted.add(key)) q.addLast(TStep.Say(line)) }
    private fun again() { q.addLast(last ?: TStep.Try(Kind.THIEF, true, WIDE)) }

    fun done(r: TryResult) {
        val t = last ?: return
        if (r == TryResult.NONE) { silence(t); return }
        silent = 0
        // never stuck: after CAP tries that are clicks but not right, the step is passed anyway
        var ok = r == TryResult.SUCCESS
        if (!ok && ++tries >= CAP) { ok = true; forced = true }
        val before = part
        when (part) {
            Part.LEARN -> learn(ok, r)
            Part.BEAT -> beat(ok, r, t)
            Part.KNIGHT -> knight(ok, r)
            Part.DECOY -> decoy(ok)
            Part.DONE -> {}
        }
        if (part != before) { tries = 0; forced = false }
    }

    private fun silence(t: TStep.Try) {
        silent++
        if (silent >= QUIT_AFTER_SILENT) { q.addLast(TStep.Quit); return }
        val line = when {
            silent % 3 == 0 -> GuardVoice.PRESS
            t.kind == Kind.DECOY -> GuardVoice.BELL
            t.ping -> GuardVoice.CLICK_PING
            else -> GuardVoice.CLICK_FOURTH
        }
        q.addLast(TStep.Say(line))
        q.addLast(TStep.Demo(t.kind, t.ping || part == Part.LEARN, maxOf(t.goodMs, WIDE)))
        again()
    }

    private fun hintFor(r: TryResult) {
        when (r) {
            TryResult.EARLY -> hint("early", GuardVoice.TOO_EARLY)
            TryResult.LATE -> hint("late", GuardVoice.TOO_LATE)
            else -> {}
        }
    }

    private fun learn(ok: Boolean, r: TryResult) {
        if (!ok) { hintFor(r); again(); return }
        wins++
        if (wins == 1 && !forced) { q.addLast(TStep.Say(GuardVoice.GOOD_AGAIN)); q.addLast(TStep.Try(Kind.THIEF, true, 230.0)); return }
        part = Part.BEAT; streak = 0; fails = 0
        q.addAll(listOf(TStep.Say(GuardVoice.LEAVE), TStep.Say(GuardVoice.CLICK_BEAT), TStep.Calibrate,
            TStep.Say(GuardVoice.NO_PING), TStep.Try(Kind.THIEF, false, BEAT_WINDOWS[0])))
    }

    private fun beat(ok: Boolean, r: TryResult, t: TStep.Try) {
        if (ok) {
            if (t.ping && !forced) { q.addLast(TStep.Say(GuardVoice.NO_PING)); q.addLast(TStep.Try(Kind.THIEF, false, BEAT_WINDOWS[0])); return }
            streak++; fails = 0
            if (streak >= BEAT_WINDOWS.size || forced) {
                part = Part.KNIGHT; fails = 0
                q.addAll(listOf(TStep.Say(GuardVoice.GOOD), TStep.Say(GuardVoice.KNIGHT), TStep.Demo(Kind.KNIGHT, true, 220.0), TStep.Try(Kind.KNIGHT, true, 220.0)))
            } else q.addLast(TStep.Try(Kind.THIEF, false, BEAT_WINDOWS[streak]))
            return
        }
        streak = 0; fails++; hintFor(r)
        if (fails >= 3) {   // help: bring the ping back for one try
            fails = 0
            q.addAll(listOf(TStep.Say(GuardVoice.CLICK_PING), TStep.Demo(Kind.THIEF, true, WIDE), TStep.Try(Kind.THIEF, true, WIDE)))
        } else q.addLast(TStep.Try(Kind.THIEF, false, BEAT_WINDOWS[0]))
    }

    private fun knight(ok: Boolean, r: TryResult) {
        if (ok) {
            part = Part.DECOY; fails = 0
            q.addAll(listOf(TStep.Say(GuardVoice.GOOD), TStep.Say(GuardVoice.BELL), TStep.Demo(Kind.DECOY, false, 0.0), TStep.Try(Kind.DECOY, false, 0.0)))
            return
        }
        fails++; hintFor(r)
        hint("fourfive", GuardVoice.CLICK_FOUR_FIVE)
        if (fails % 2 == 0) q.addLast(TStep.Demo(Kind.KNIGHT, true, 220.0))
        again()
    }

    private fun decoy(ok: Boolean) {
        if (ok) {
            part = Part.DONE
            q.addAll(listOf(TStep.Say(GuardVoice.RIGHT_WAIT), TStep.Say(GuardVoice.READY), TStep.Finish))
        } else {
            q.addAll(listOf(TStep.Say(GuardVoice.NO_WAIT), TStep.Demo(Kind.DECOY, false, 0.0), TStep.Try(Kind.DECOY, false, 0.0)))
        }
    }
}
