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
}
