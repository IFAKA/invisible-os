"""Tests for story.py: python3 -m unittest -v test_story   (from the bridge/ folder). A fake model function, no Ollama."""
import json
import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import story  # noqa: E402

SRC = ("I ran the tests in build.py and 3 of 12 failed. The failing ones are in Parser. "
       "I fixed the first one and wrote the change to notes.md. Should I commit the fix to the main branch now?")


def reply(s, g=""):
    return json.dumps({"story": s, "go": g})


class GuardTest(unittest.TestCase):
    def test_true_story_passes(self):
        ok, why = story.check_story("Tests failed, 3 of 12. Fixed one. Commit the fix?", SRC)
        self.assertTrue(ok, why)

    def test_invented_number_name_file_quote(self):
        for bad in ["5 of 12 tests failed.", "The tests failed in Lexer.", "Wrote the change to todo.txt.", 'Fixed "the cache" bug.']:
            self.assertFalse(story.check_story(bad, SRC)[0], bad)

    def test_sentence_start_capital_is_free(self):
        self.assertTrue(story.check_story("Done. Waiting for you.", SRC + " x" * 20)[0])

    def test_ai_word_is_free(self):
        self.assertTrue(story.check_story("The AI fixed 3 bugs.", SRC)[0])

    def test_must_be_shorter_and_not_long(self):
        self.assertFalse(story.check_story("Tests failed.", "Tests failed.")[0])
        self.assertFalse(story.check_story(" ".join(["tests"] * 31), SRC * 5)[0])
        self.assertFalse(story.check_story("", SRC)[0])

    def test_context_counts_as_source(self):
        self.assertTrue(story.check_story("Fixed in Parser.", SRC + " more more", "Parser, proj")[0])
        self.assertFalse(story.check_story("Fixed in Zed.", SRC, "Parser, proj")[0])

    def test_go(self):
        self.assertEqual(story.check_go("commit the fix", SRC), "commit the fix")
        self.assertEqual(story.check_go("one two three four five six", SRC), "")
        self.assertEqual(story.check_go("push 99 files", SRC), "")


class MakeStoryTest(unittest.TestCase):
    def test_model_story_used(self):
        s, g, ok = story.make_story("claude", SRC, "", "fb", lambda p: reply("Tests failed, 3 of 12. Commit the fix?", "commit the fix"))
        self.assertEqual((s, g, ok), ("Tests failed, 3 of 12. Commit the fix?", "commit the fix", True))

    def test_guard_failure_falls_back(self):
        s, g, ok = story.make_story("claude", SRC, "", "need line", lambda p: reply("9 tests failed.", "go"))
        self.assertEqual((s, g, ok), ("need line", "", False))

    def test_model_down_or_junk(self):
        def down(p):
            raise OSError("down")
        self.assertEqual(story.make_story("claude", SRC, "", "", down)[0], story.first_sentence(SRC))
        self.assertEqual(story.make_story("claude", SRC, "", "fb", lambda p: "not json")[0], "fb")
        self.assertEqual(story.make_story("claude", SRC, "", "fb", None)[0], "fb")

    def test_first_sentence_cut(self):
        long = " ".join("w%d" % i for i in range(40)) + ". Next."
        self.assertEqual(len(story.first_sentence(long).split()), 25)

    def test_bad_go_dropped_story_kept(self):
        s, g, ok = story.make_story("claude", SRC, "", "fb", lambda p: reply("Tests failed, 3 of 12.", "delete 77 files now please"))
        self.assertEqual((s, g, ok), ("Tests failed, 3 of 12.", "", True))

    def test_shell_prompt_mentions_terminal(self):
        self.assertIn("terminal", story.build_prompt("shell", "x"))


class CacheTest(unittest.TestCase):
    def test_peek_is_async_then_cached(self):
        calls, gate = [], threading.Event()
        def model(p):
            calls.append(p); gate.wait(2)
            return reply("Tests failed, 3 of 12.", "commit the fix")
        c = story.StoryCache(model)
        self.assertEqual(c.peek("%1", "claude", SRC, "", "fb"), {"story": "", "go": ""})   # does not wait
        self.assertEqual(c.peek("%1", "claude", SRC, "", "fb"), {"story": "", "go": ""})   # no second run
        gate.set()
        self.assertEqual(c.ask("%1", "claude", SRC, "", "fb")["story"], "Tests failed, 3 of 12.")
        self.assertEqual(c.peek("%1", "claude", SRC)["go"], "commit the fix")
        self.assertEqual(len(calls), 1)

    def test_new_source_recomputes_and_pane_is_separate(self):
        calls = []
        def model(p):
            calls.append(1); return reply("Tests failed, 3 of 12.")
        c = story.StoryCache(model)
        c.ask("%1", "claude", SRC)
        c.ask("%2", "claude", SRC)
        c.ask("%1", "claude", SRC + " Also 3 of 12 failed again.")
        self.assertEqual(len(calls), 3)
        c.ask("%1", "claude", SRC + " Also 3 of 12 failed again.")
        self.assertEqual(len(calls), 3)

    def test_ask_timeout_gives_fallback_and_work_continues(self):
        gate = threading.Event()
        def slow(p):
            gate.wait(3); return reply("Tests failed, 3 of 12.")
        c = story.StoryCache(slow)
        t = time.time()
        self.assertEqual(c.ask("%1", "claude", SRC, "", "need line", wait=0.2)["story"], "need line")
        self.assertLess(time.time() - t, 1.0)
        gate.set(); time.sleep(0.2)
        self.assertEqual(c.peek("%1", "claude", SRC)["story"], "Tests failed, 3 of 12.")

    def test_failure_is_cached_then_retried_after_a_while(self):
        now, calls = [0.0], []
        def model(p):
            calls.append(1); raise OSError("down")
        c = story.StoryCache(model, clock=lambda: now[0])
        self.assertEqual(c.ask("%1", "claude", SRC, "", "fb")["story"], "fb")
        c.ask("%1", "claude", SRC, "", "fb")
        self.assertEqual(len(calls), 1)
        now[0] = 100.0
        c.ask("%1", "claude", SRC, "", "fb")
        self.assertEqual(len(calls), 2)

    def test_no_model_answers_at_once_with_fallback(self):
        c = story.StoryCache(None)
        self.assertEqual(c.peek("%1", "claude", SRC, "", "need line"), {"story": "need line", "go": ""})

    def test_empty_source_uses_fallback_without_model(self):
        c = story.StoryCache(lambda p: 1 / 0)
        self.assertEqual(c.peek("%1", "claude", "", "", "waiting"), {"story": "waiting", "go": ""})


class PlainTest(unittest.TestCase):
    WHAT = "rm -rf build/cache"

    def model(self, line):
        return lambda prompt: json.dumps({"plain": line})

    def test_good_line(self):
        self.assertEqual(story.make_plain(self.WHAT, self.model("Deletes the folder build/cache")), "Deletes the folder build/cache")

    def test_reassurance_is_refused(self):
        for bad in ["Deletes a cache folder, it is safe", "A harmless cleanup", "Normal cleanup of files", "This is fine to run", "Deletes files, risky"]:
            self.assertEqual(story.make_plain(self.WHAT, self.model(bad)), "", bad)

    def test_too_long_or_invented_or_empty(self):
        self.assertEqual(story.make_plain(self.WHAT, self.model("Deletes " + "the old " * 6 + "folder")), "")
        self.assertEqual(story.make_plain(self.WHAT, self.model("Deletes the file notes.md")), "")
        self.assertEqual(story.make_plain(self.WHAT, self.model("")), "")
        self.assertEqual(story.make_plain(self.WHAT, lambda p: "not json"), "")
        self.assertEqual(story.make_plain(self.WHAT, None), "")

    def test_failure_is_empty(self):
        def boom(p):
            raise OSError("down")
        self.assertEqual(story.make_plain(self.WHAT, boom), "")

    def test_cache_never_waits_and_fills_later(self):
        calls = []
        def model(p):
            calls.append(1)
            return json.dumps({"plain": "Deletes the folder build/cache"})
        c = story.StoryCache(model)
        c.plain(self.WHAT)
        for _ in range(100):
            if c.plain(self.WHAT):
                break
            time.sleep(0.02)
        self.assertEqual(c.plain(self.WHAT), "Deletes the folder build/cache")
        self.assertEqual(len(calls), 1)

    def test_no_model_is_empty(self):
        self.assertEqual(story.StoryCache(None).plain(self.WHAT), "")


if __name__ == "__main__":
    unittest.main()


class ContextPromptTest(unittest.TestCase):
    def test_request_in_prompt_and_cut(self):
        p = story.build_prompt("claude", SRC, "ctx", "fix the parser " + "x" * 2000)
        self.assertIn("The user's last request in this window", p)
        self.assertLess(p.count("x"), story.REQUEST_CHARS)
        self.assertNotIn("last request", story.build_prompt("claude", SRC, "ctx"))

    def test_cache_key_includes_request_and_ai_off_skips_model(self):
        self.assertNotEqual(story.StoryCache.key("%1", SRC, "a"), story.StoryCache.key("%1", SRC, "b"))
        calls = []
        c = story.StoryCache(lambda p: calls.append(p) or reply("x"))
        self.assertEqual(c.peek("%1", "claude", SRC, "", "fb", "", False), {"story": "fb", "go": ""})
        self.assertEqual(c.ask("%1", "claude", SRC, "", "fb", 0.1, "", False), {"story": "fb", "go": ""})
        self.assertEqual(c.plain("rm -rf x", "", False), "")
        self.assertEqual(calls, [])

    def test_plain_prompt_why_and_guard(self):
        p = story.build_plain_prompt("rm -rf build", "I will clear the build folder.")
        self.assertIn("Why (Claude's words): I will clear the build folder.", p)
        self.assertNotIn("Why (", story.build_plain_prompt("rm -rf build"))
        self.assertEqual(story.check_plain("Deletes the Build folder", "rm -rf build", "I will clear the Build folder"), "Deletes the Build folder")
        self.assertEqual(story.check_plain("Deletes the safe folder", "rm -rf build", "why"), "")
        self.assertEqual(story.check_plain("Deletes it, it is safe", "rm -rf build", "It is safe"), "")
