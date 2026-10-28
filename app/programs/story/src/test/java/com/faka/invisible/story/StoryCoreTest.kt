package com.faka.invisible.story

import org.junit.Assert.*
import org.junit.Test

class StoryCoreTest {
    @Test fun threeStoriesAboutTwelveScenes() {
        assertEquals(3, StoryCore.stories.size)
        for (s in StoryCore.stories) assertTrue(s.id, s.scenes.size in 10..16)
    }

    @Test fun everyStoryIsPlayable() {
        for (s in StoryCore.stories) assertEquals(emptyList<String>(), StoryCore.problems(s))
    }

    @Test fun everyStoryHasSeveralEndings() {
        for (s in StoryCore.stories) assertTrue(s.id, s.scenes.count { it.isEnding } >= 3)
    }

    @Test fun validatorCatchesBrokenStories() {
        val dead = Story("x", "X", "a", listOf(Scene("a", listOf("Hi."), listOf(Choice("one", "b"), Choice("two", "zzz"))), Scene("b", listOf("End."), emptyList())))
        assertTrue(StoryCore.problems(dead).any { "missing scene" in it })
        val loop = Story("y", "Y", "a", listOf(
            Scene("a", listOf("Hi."), listOf(Choice("one", "b"), Choice("two", "c"))),
            Scene("b", listOf("Hi."), listOf(Choice("one", "c"), Choice("two", "c"))),
            Scene("c", listOf("Hi."), listOf(Choice("one", "b"), Choice("two", "b")))))
        assertTrue(StoryCore.problems(loop).any { "cannot reach" in it })
    }

    @Test fun resumeOnlyInsideAStory() {
        assertNull(StoryCore.resume(null))
        assertNull(StoryCore.resume("key:free"))
        assertNull(StoryCore.resume("nope:start"))
        assertEquals("kitchen", StoryCore.resume("key:kitchen")!!.second.id)
    }
}
