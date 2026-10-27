package com.faka.invisible.logickit

import android.view.KeyEvent
import com.faka.invisible.logickit.MediaReplay.Action
import com.faka.invisible.logickit.MediaReplay.Player
import org.junit.Assert.*
import org.junit.Test

class MediaReplayTest {
    private val ours = Player("com.faka.invisible", 3, true)   // our silent session always "plays"
    private val music = Player("app.morphe.music", 3, false)
    private val podcast = Player("pod.app", 2, false)
    private val video = Player("video.app", 3, false)

    @Test fun clicksMapToHeadsetActions() {
        assertEquals(Action.PLAY_PAUSE, MediaReplay.forClicks(1))
        assertEquals(Action.NEXT, MediaReplay.forClicks(2))
        assertEquals(Action.PREVIOUS, MediaReplay.forClicks(3))
        assertNull(MediaReplay.forClicks(4))
    }

    @Test fun keyCodesAreAndroids() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaReplay.KEY_PLAY_PAUSE)
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaReplay.KEY_NEXT)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaReplay.KEY_PREVIOUS)
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, MediaReplay.KEY_PAUSE)
    }

    @Test fun neverOurOwnSession() {
        assertNull(MediaReplay.choose(listOf(ours)))
        assertEquals(music, MediaReplay.choose(listOf(ours, music)))
    }

    @Test fun playingBeatsRecent() {
        assertEquals(music, MediaReplay.choose(listOf(podcast, music)))
        assertEquals(music, MediaReplay.choose(listOf(music, video)))   // both play: the newer one
    }

    @Test fun noneplayingTakesMostRecent() {
        assertEquals(podcast, MediaReplay.choose(listOf(ours, podcast, Player("old", 2, false))))
        assertNull(MediaReplay.choose(emptyList()))
    }

    @Test fun pauseAllPlayingButOurs() {
        assertEquals(listOf(music, video), MediaReplay.playing(listOf(ours, music, podcast, video)))
        assertTrue(MediaReplay.isPlaying(6))   // buffering counts
        assertFalse(MediaReplay.isPlaying(2))
    }

    @Test fun playPauseFollowsState() {
        assertEquals("pause", MediaReplay.command(Action.PLAY_PAUSE, music))
        assertEquals("play", MediaReplay.command(Action.PLAY_PAUSE, podcast))
        assertEquals("next", MediaReplay.command(Action.NEXT, podcast))
        assertEquals("previous", MediaReplay.command(Action.PREVIOUS, music))
    }
}
