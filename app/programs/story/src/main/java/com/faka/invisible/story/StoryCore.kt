package com.faka.invisible.story

import com.faka.invisible.logickit.words

/** One choice: the words heard, and the scene it leads to. */
data class Choice(val label: String, val to: String)

/** One scene: 1-2 short sentences. No choices means an ending. */
data class Scene(val id: String, val lines: List<String>, val choices: List<Choice>) {
    val isEnding get() = choices.isEmpty()
    val text get() = lines.joinToString(" ")
}

data class Story(val id: String, val title: String, val start: String, val scenes: List<Scene>) {
    private val byId = scenes.associateBy { it.id }
    fun scene(id: String): Scene? = byId[id]
}

/** The bundled stories (English) and the checks that keep them playable. Pure, no Android. */
object StoryCore {
    /** s("id", "Sentence one.|Sentence two.", "Choice" to "scene", ...). No choices = an ending. */
    private fun s(id: String, text: String, vararg c: Pair<String, String>) =
        Scene(id, text.split("|"), c.map { Choice(it.first, it.second) })

    val stories: List<Story> = listOf(
        Story("key", "The Lost Key", "start", listOf(
            s("start", "You wake in a dark house.|A cold wind blows.", "Go to the kitchen" to "kitchen", "Go to the cellar" to "cellar", "Try the front door" to "door"),
            s("kitchen", "A candle burns on the table.|Under it lies a note.", "Read the note" to "note", "Take the candle" to "candle"),
            s("cellar", "Stairs go down into the dark.", "Go down" to "down", "Go back up" to "start"),
            s("door", "The door is locked.|You need a key.", "Search the hall" to "hall", "Kick the door" to "kick"),
            s("note", "The note says: look under the clock.", "Find the clock" to "hall", "Go to the cellar" to "cellar"),
            s("candle", "Now you can see well.", "Check the cellar" to "down", "Check the hall" to "hall"),
            s("down", "A small chest sits in the dust.", "Open the chest" to "key", "Leave it" to "cellar"),
            s("hall", "A big clock ticks loudly.|Something shines beneath it.", "Take it" to "key", "Ignore it" to "kick"),
            s("kick", "The door shakes but holds.|Your foot hurts.", "Try again" to "tired", "Look for a key" to "hall"),
            s("key", "You hold a small brass key.", "Unlock the front door" to "free", "Explore upstairs" to "attic"),
            s("attic", "Old toys lie everywhere.|A music box starts playing.", "Wind it up" to "music", "Run outside" to "free"),
            s("free", "The door opens.|Morning light! You are free."),
            s("tired", "You sit down, worn out.|Morning will come soon."),
            s("music", "The tune is soft and kind.|You smile and fall asleep.")
        )),
        Story("mail", "Space Mail", "start", listOf(
            s("start", "You deliver mail in space.|One box is ticking.", "Open the box" to "open", "Throw it away" to "throw", "Call the base" to "call"),
            s("open", "Inside is a tiny glowing egg.", "Keep it warm" to "warm", "Close the box" to "call"),
            s("throw", "You push it out the airlock.|Boom! The ship shakes.", "Check the damage" to "damage", "Wait and see" to "signal"),
            s("call", "The base says: stay calm.|Bring the box to the Moon.", "Fly to the Moon" to "moon", "Open the box" to "open"),
            s("warm", "The egg cracks open.|A baby star-cat purrs.", "Feed it" to "feed", "Take it to the Moon" to "moon"),
            s("damage", "One engine is broken.", "Fix it" to "fix", "Send a signal" to "signal"),
            s("fix", "Sparks fly.|The engine hums again.", "Fly to the Moon" to "moon", "Go home" to "home"),
            s("signal", "A friendly ship answers.", "Accept a tow" to "home", "Fly on alone" to "moon"),
            s("feed", "The star-cat glows with joy.", "Fly to the Moon" to "moon", "Keep it forever" to "keep"),
            s("moon", "Moon Station shines ahead.|A crowd waits to cheer.", "Land gently" to "hero", "Land fast" to "oops"),
            s("keep", "You and the star-cat travel together.|Space is not lonely now."),
            s("hero", "Everyone cheers your name.|Best mail carrier ever!"),
            s("oops", "Bump! Mail flies everywhere.|The crowd laughs and helps."),
            s("home", "You are home at last.|The mail can wait.")
        )),
        Story("dragon", "The Sleepy Dragon", "start", listOf(
            s("start", "A dragon sleeps by the road.|You must pass it.", "Tiptoe past" to "tiptoe", "Wake it up" to "wake", "Take another road" to "forest"),
            s("tiptoe", "Your boot crunches on gravel.", "Freeze" to "freeze", "Run" to "run"),
            s("freeze", "The dragon snores again.|A gold coin lies near.", "Grab the coin" to "coin", "Keep walking" to "village"),
            s("run", "The dragon opens one eye.", "Say hello" to "wake", "Keep running" to "forest"),
            s("wake", "The dragon yawns.|It smells your lunch.", "Share your bread" to "bread", "Hide the bread" to "hide"),
            s("bread", "The dragon smiles and says thanks.", "Ask for a ride" to "ride", "Say goodbye" to "village"),
            s("hide", "The dragon sniffs.|It finds the bread.", "Say sorry" to "bread", "Run away" to "forest"),
            s("forest", "Tall trees block the sun.|An owl hoots softly.", "Follow the owl" to "owl", "Climb a tree" to "tree"),
            s("owl", "The owl leads you to a cave.", "Go inside" to "treasure", "Wait outside" to "village"),
            s("tree", "From the top you see home.", "Climb down and walk" to "village", "Stay up high" to "owl"),
            s("coin", "The coin is hot and sticky.|The dragon wakes up angry!", "Drop it" to "wake", "Throw it far" to "chase"),
            s("ride", "You soar over green hills.|The land looks so tiny."),
            s("treasure", "Gold and gems fill the cave.|You take one and smile."),
            s("village", "You reach the village safely.|Everyone cheers."),
            s("chase", "The dragon runs after the coin.|You slip away.")
        ))
    )

    /** Longest allowed sentence or choice, in words. */
    const val MAX_WORDS = 7

    /** Everything wrong with a story: empty list = playable. */
    fun problems(st: Story): List<String> {
        val out = ArrayList<String>()
        fun words(t: String) = t.words().size
        if (st.scenes.map { it.id }.toSet().size != st.scenes.size) out += "${st.id}: duplicate scene id"
        if (st.scene(st.start) == null) { out += "${st.id}: start scene missing"; return out }
        for (sc in st.scenes) {
            val at = "${st.id}/${sc.id}"
            if (sc.lines.size !in 1..2) out += "$at: needs 1-2 sentences"
            for (l in sc.lines) if (words(l) > MAX_WORDS) out += "$at: sentence too long: $l"
            if (!sc.isEnding && sc.choices.size !in 2..3) out += "$at: needs 2-3 choices"
            for (c in sc.choices) {
                if (st.scene(c.to) == null) out += "$at: choice '${c.label}' leads to missing scene ${c.to}"
                if (words(c.label) > MAX_WORDS) out += "$at: choice too long: ${c.label}"
            }
        }
        // from the start every scene is reachable, and every reachable scene can reach an ending (no loops with no way out)
        val reach = HashSet<String>(); val todo = ArrayDeque(listOf(st.start))
        while (todo.isNotEmpty()) { val id = todo.removeFirst(); if (reach.add(id)) st.scene(id)?.choices?.forEach { todo.add(it.to) } }
        for (sc in st.scenes) if (sc.id !in reach) out += "${st.id}/${sc.id}: unreachable"
        val canEnd = st.scenes.filter { it.isEnding }.map { it.id }.toMutableSet()
        var grew = true
        while (grew) { grew = false; for (sc in st.scenes) if (sc.id !in canEnd && sc.choices.any { it.to in canEnd }) { canEnd += sc.id; grew = true } }
        for (sc in st.scenes) if (sc.id !in canEnd) out += "${st.id}/${sc.id}: cannot reach an ending"
        if (st.scenes.none { it.isEnding }) out += "${st.id}: no ending"
        return out
    }

    fun byId(id: String) = stories.firstOrNull { it.id == id }

    /** Saved place "storyId:sceneId" -> the scene to resume, or null when it is gone or already an ending. */
    fun resume(saved: String?): Pair<Story, Scene>? {
        val p = saved?.split(":") ?: return null
        if (p.size != 2) return null
        val st = byId(p[0]) ?: return null
        val sc = st.scene(p[1]) ?: return null
        return if (sc.isEnding) null else st to sc
    }
}
