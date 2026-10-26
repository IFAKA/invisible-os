package com.faka.invisible

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every OS program must have a tag line in AppCategories, so the tree places it by its tag and no screen list has to hold it. */
class AppCategoriesTagsTest {
    @Test fun everyProgramHasATag() {
        val missing = LauncherSettings.HUB_ITEMS.keys.filter { "hub:$it" !in AppCategories.EXACT }
        assertTrue("programs without a tag line: $missing", missing.isEmpty())
    }

    @Test fun everyTagLandsInARealGroup() {
        val bad = AppCategories.EXACT.filter { AppCategories.main(it.value) !in TreeLeaves.GROUPS }
        assertTrue("tags with an unknown group: $bad", bad.isEmpty())
    }

    @Test fun noPlaceIsLongerThanAListSoNothingNeedsMore() {
        val byPlace = AppCategories.EXACT.entries.groupBy({ it.value }, { it.key })
        val crowded = byPlace.filter { it.value.size > AppTree.MAX_LIST }.mapValues { it.value.size }
        assertTrue("places with more than ${AppTree.MAX_LIST} entries (split them with finer tags): $crowded", crowded.isEmpty())
        val groups = TreeLeaves.GROUPS.associateWith { g -> byPlace.keys.count { AppCategories.main(it) == g && AppCategories.sub(it) != null } + (byPlace[g]?.size ?: 0) }
        val wide = groups.filter { it.value > AppTree.MAX_LIST }
        assertTrue("groups whose list (sub-groups + direct apps) is longer than ${AppTree.MAX_LIST}: $wide", wide.isEmpty())
    }
}
