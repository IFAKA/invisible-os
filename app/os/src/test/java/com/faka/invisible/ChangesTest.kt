package com.faka.invisible

import com.faka.invisible.claude.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The parsers of Show on phone (org.json needs Robolectric) and the pure helpers of the Changes screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangesTest {
    @Test fun parses_the_diff_reply() {
        val o = JSONObject("""{"files":[{"path":"a.txt","added":2,"removed":1,"patch":"@@ -1 +1 @@\n-old\n+new"},{"path":"n.md","added":0,"removed":0,"patch":"New file","untracked":true},{"path":""}]}""")
        val f = parseDiff(o)
        assertEquals(2, f.size)
        assertEquals("+2 -1", f[0].counts)
        assertTrue(f[1].untracked)
        assertTrue(parseDiff(JSONObject("{}")).isEmpty())
    }

    @Test fun patch_lines_get_their_kind() {
        val k = PatchLine.parse("@@ -1,2 +1,2 @@\n keep\n-old\n+new").map { it.kind }
        assertEquals(listOf(PatchLine.Kind.HUNK, PatchLine.Kind.CONTEXT, PatchLine.Kind.DEL, PatchLine.Kind.ADD), k)
    }

    @Test fun parses_pictures_and_info() {
        val p = parsePictures(JSONObject("""{"pictures":[{"n":0,"name":"a.png","kind":"read"},{"n":1}]}"""))
        assertEquals(listOf("a.png", "image"), p.map { it.name })
        val dev = parsePreviewInfo(JSONObject("""{"live":"dev","port":5173,"host":"","start":"","git":true}"""))
        assertTrue(dev.hasLive); assertNull(dev.dwUrl)
        val dw = parsePreviewInfo(JSONObject("""{"live":"dw","host":"dev01.example.net","start":""}"""))
        assertEquals("https://dev01.example.net/", dw.dwUrl)
        assertFalse(parsePreviewInfo(JSONObject("""{"live":"none","start":"dev"}""")).hasLive)
    }

    @Test fun unreachable_text_names_tailscale_only_for_100_addresses() {
        assertEquals("Laptop not reachable. Is Tailscale on?", ChangesText.unreachable("http://100.64.1.2:8765"))
        assertEquals("Laptop not reachable.", ChangesText.unreachable("http://192.168.1.5:8765"))
    }

    @Test fun preview_address_carries_the_encoded_pane_and_token() {
        assertEquals("http://100.1.2.3:8765/preview/%253/?token=a%2Bb", BridgeClient("100.1.2.3:8765", "a+b").previewUrl("%3"))
    }
}
