package com.faka.invisible.claude

import com.faka.invisible.claude.Approval.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalTest {
    private fun kinds(vararg labels: String) = Approval.options(labels.toList()).map { it.kind }

    @Test fun hook_labels_map_by_meaning_in_the_fixed_order() {
        assertEquals(listOf(Kind.NO, Kind.YES, Kind.ALWAYS), kinds("Allow", "Deny", "Always allow"))
        assertEquals(listOf(Kind.NO, Kind.YES, Kind.ALWAYS), kinds("Always allow", "Yes", "No"))
        assertEquals(listOf(Kind.NO, Kind.YES, Kind.ALWAYS), kinds("Yes", "Yes, and don't ask again for this command", "No, and tell Claude what to do differently"))
    }

    @Test fun a_kind_the_hook_did_not_offer_is_never_offered() {
        assertEquals(listOf(Kind.NO, Kind.YES), kinds("Allow", "Deny"))
        assertEquals(listOf(Kind.NO), kinds("Deny"))
        assertEquals(emptyList<Kind>(), kinds("Maybe", "Later"))
    }

    @Test fun the_label_sent_back_is_the_hooks_own() {
        assertEquals(listOf("Deny", "Allow", "Always allow"), Approval.options(listOf("Allow", "Deny", "Always allow")).map { it.label })
    }

    @Test fun deny_wins_over_always_and_allow() {
        assertEquals(Kind.NO, Approval.classify("Always deny"))
        assertEquals(Kind.ALWAYS, Approval.classify("Always allow"))
        assertEquals(Kind.YES, Approval.classify("Allow once"))
    }
}
