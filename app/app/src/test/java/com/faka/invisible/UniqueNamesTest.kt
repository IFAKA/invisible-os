package com.faka.invisible

import com.faka.invisible.logickit.UniqueNames
import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueNamesTest {
    private fun leaf(pkg: String, label: String, w: Double = 1.0) = AppLeaf(pkg, label, "Tools/Dev", 0, w)
    private fun names(l: List<AppLeaf>, reserved: Set<String> = emptySet()) = UniqueNames.apply(l, reserved).associate { it.pkg to it.label }

    @Test fun theProgramKeepsItsNameAndTheAppGetsItsMaker() {
        val n = names(listOf(leaf("hub:compass", "Compass", 3.0), leaf("com.miui.compass", "Compass"), leaf("hub:calculator", "Calculator"), leaf("com.miui.calculator", "Calculator")))
        assertEquals("Compass", n["hub:compass"]); assertEquals("Xiaomi Compass", n["com.miui.compass"])
        assertEquals("Calculator", n["hub:calculator"]); assertEquals("Xiaomi Calculator", n["com.miui.calculator"])
    }
    @Test fun threeAuthenticatorsAreToldApartByMaker() {
        val n = names(listOf(leaf("com.azure.authenticator", "Authenticator"), leaf("com.google.android.apps.authenticator2", "Authenticator"), leaf("com.salesforce.authenticator", "Authenticator")))
        assertEquals(setOf("Microsoft Authenticator", "Google Authenticator", "Salesforce Authenticator"), n.values.toSet())
    }
    @Test fun sameMakerOrUnknownMakerGetsANumber() {
        val n = names(listOf(leaf("com.google.android.apps.docs", "Docs", 5.0), leaf("com.google.android.apps.docs.editors.docs", "Docs", 2.0), leaf("com.x.a", "Notes", 3.0), leaf("com.y.b", "Notes", 1.0)))
        assertEquals("Google Docs", n["com.google.android.apps.docs"]); assertEquals("Google Docs 2", n["com.google.android.apps.docs.editors.docs"])
        assertEquals("Notes", n["com.x.a"]); assertEquals("Notes 2", n["com.y.b"])
    }
    @Test fun anAppNamedLikeAGroupIsRenamedButAProgramIsNot() {
        val n = names(listOf(leaf("com.google.android.apps.messaging", "Messages"), leaf("hub:messages", "Messages")), reserved = setOf("Messages"))
        assertEquals("Google Messages", n["com.google.android.apps.messaging"]); assertEquals("Messages", n["hub:messages"])
    }
    @Test fun everyNameIsUniqueAndNothingChangesWhenAlreadyUnique() {
        val u = listOf(leaf("a.b.c", "One"), leaf("d.e.f", "Two"))
        assertEquals(mapOf("a.b.c" to "One", "d.e.f" to "Two"), names(u))
        val dup = names(listOf(leaf("a.b", "X", 3.0), leaf("c.d", "X", 2.0), leaf("e.f", "X", 1.0)))
        assertEquals(3, dup.values.toSet().size)
    }
}
