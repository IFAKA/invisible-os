package com.faka.invisible

import org.junit.Assert.*
import org.junit.Test

class StoreroomTest {
    private val now = 100L * 86_400_000L
    private fun app(p: String, at: Long = 0) = InstalledApp(p, p, installedAt = at)
    private fun run(apps: List<InstalledApp>, use: Map<String, AppUse>, pinned: Set<String> = emptySet(), picks: Map<String, Int> = emptyMap()) =
        Storeroom.decide(apps, use, { picks[it] ?: 0 }, pinned, now)
    private val busy = "com.busy.app" to AppUse(10, 2)

    @Test fun zeroOpensIsStored() = assertTrue("com.idle" in run(listOf(app("com.idle"), app(busy.first)), mapOf(busy)))
    @Test fun jobLoserStoredCloseSecondKept() {
        val use = mapOf("com.spotify.music" to AppUse(100, 0), "com.miui.player" to AppUse(3, 0), "org.oxycblt.auxio" to AppUse(30, 0))
        val r = run(listOf(app("com.spotify.music"), app("com.miui.player"), app("org.oxycblt.auxio")), use)
        assertTrue("com.miui.player" in r); assertFalse("org.oxycblt.auxio" in r); assertFalse("com.spotify.music" in r)
    }
    @Test fun patchedCopyJoinsTheJobOfTheOriginal() {
        // the copy has no table line: it takes the job of the original (music), so Spotify's heavy use stores it
        val use = mapOf("com.spotify.music" to AppUse(100, 0), "com.google.android.apps.youtube.music" to AppUse(0, 0), "app.morphe.android.apps.youtube.music" to AppUse(4, 0))
        val r = run(listOf(app("com.spotify.music"), app("com.google.android.apps.youtube.music"), app("app.morphe.android.apps.youtube.music")), use)
        assertTrue("app.morphe.android.apps.youtube.music" in r)
    }
    @Test fun copiesOfAnUnlistedAppShareAJob() {
        val use = mapOf("com.a.android.tool.x" to AppUse(100, 0), "app.b.android.tool.x" to AppUse(3, 0))
        assertTrue("app.b.android.tool.x" in run(listOf(app("com.a.android.tool.x"), app("app.b.android.tool.x")), use))
    }
    @Test fun unknownMusicAppTakesTheMusicSlot() {
        // no table line, no copy: the name says music, so it competes with Spotify for the one music slot
        val use = mapOf("com.spotify.music" to AppUse(100, 0), "org.new.musicplayer" to AppUse(3, 0))
        assertTrue("org.new.musicplayer" in run(listOf(app("com.spotify.music"), app("org.new.musicplayer")), use))
    }
    @Test fun chatsAreNotASlot() {
        val use = mapOf("com.whatsapp" to AppUse(100, 0), "org.telegram.messenger" to AppUse(3, 0))
        assertTrue(run(listOf(app("com.whatsapp"), app("org.telegram.messenger")), use).isEmpty())
    }
    @Test fun alwaysKept() = assertTrue(run(listOf(app("com.android.camera"), app("com.x.camera2"), app("com.android.settings"), app(busy.first)), mapOf(busy)).isEmpty())
    @Test fun newInstallKept() {
        val r = run(listOf(app("com.new", now - 3 * 86_400_000L), app("com.old", now - 30 * 86_400_000L), app(busy.first)), mapOf(busy))
        assertFalse("com.new" in r); assertTrue("com.old" in r)
    }
    @Test fun pinnedKept() = assertFalse("com.idle" in run(listOf(app("com.idle"), app(busy.first)), mapOf(busy), pinned = setOf("com.idle")))
    @Test fun plumbingStored() = assertTrue("com.miui.home" in run(listOf(app("com.miui.home"), app(busy.first)), mapOf(busy, "com.miui.home" to AppUse(50, 9))))
    @Test fun emptyUsageStoresOnlyPlumbing() = assertEquals(setOf("com.miui.home"), run(listOf(app("com.miui.home"), app("com.idle")), emptyMap()).keys)
    @Test fun picksCount() = assertFalse("com.picked" in run(listOf(app("com.picked"), app(busy.first)), mapOf(busy), picks = mapOf("app:com.picked" to 1)))
    @Test fun rawNotNetUse() {
        // a learning reset subtracts launches in TreeLeaves.apps; the storeroom looks at the raw numbers, so this app stays
        assertFalse("com.reset" in run(listOf(app("com.reset"), app(busy.first)), mapOf(busy, "com.reset" to AppUse(8, 1))))
    }
    @Test fun leafCarriesFlag() {
        val l = TreeLeaves.apps(listOf(app("com.idle"), app(busy.first)), mapOf(busy), { false }, emptyMap(), { 0 }, emptySet(), now)
        assertTrue(l.first { it.pkg == "com.idle" }.stored); assertFalse(l.first { it.pkg == busy.first }.stored)
    }
}
