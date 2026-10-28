import json
import unittest
import aitext

ITEMS = [{"app": "WhatsApp", "title": "Ana", "text": "dinner?"}, {"app": "WhatsApp", "title": "Leo", "text": "ok"},
         {"app": "Rappi", "title": "Order", "text": "Your order is near"}, {"app": "Shop", "title": "SALE", "text": "50% off"}]
LONG = ("Hi, this is Dr. Perez's office. Your appointment is moved to Tuesday at 4 pm. Please come ten minutes early and bring your "
        "insurance card and the lab results from last month. If the time does not work, call us before Monday at noon. Thank you very much.")


class DigestTest(unittest.TestCase):
    def model(self, labels):
        return lambda p: json.dumps({"labels": labels})

    def test_counts_add_up(self):
        line = aitext.make_digest(ITEMS, self.model(["chat", "chat", "delivery", "junk"]))
        self.assertEqual(line, "2 chats, 1 delivery, 1 junk")
        self.assertTrue(aitext.check_digest(line, 4))

    def test_wrong_count_or_label(self):
        self.assertEqual(aitext.make_digest(ITEMS, self.model(["chat", "chat", "junk"])), "")
        self.assertEqual(aitext.make_digest(ITEMS, self.model(["chat", "chat", "weird", "junk"])), "")
        self.assertEqual(aitext.make_digest(ITEMS, lambda p: "no json"), "")
        self.assertEqual(aitext.make_digest(ITEMS, None), "")

    def test_failure(self):
        def boom(p):
            raise OSError("down")
        self.assertEqual(aitext.make_digest(ITEMS, boom), "")

    def test_check_digest(self):
        self.assertFalse(aitext.check_digest("3 chats, 2 junk", 8))
        self.assertTrue(aitext.check_digest("3 chats, 5 junk", 8))

    def test_groups_cut(self):
        labels = ["chat", "delivery", "news", "work", "payment", "junk"]
        line = aitext.digest_line(labels)
        self.assertEqual(line.count(","), 4)
        self.assertTrue(aitext.check_digest(line, 6))


class ShortTest(unittest.TestCase):
    def reply(self, s):
        return lambda p: json.dumps({"short": s})

    def test_ok(self):
        s = "Dr. Perez moved your appointment to Tuesday at 4 pm. Bring your insurance card."
        self.assertEqual(aitext.make_short(LONG, self.reply(s)), s)

    def test_invented_number(self):
        self.assertEqual(aitext.make_short(LONG, self.reply("Appointment moved to Tuesday at 5 pm.")), "")

    def test_not_shorter_or_long(self):
        self.assertEqual(aitext.make_short("Hi there", self.reply("Hi there and welcome")), "")
        self.assertEqual(aitext.make_short(LONG * 3, self.reply(" ".join(["word"] * 50))), "")

    def test_cache(self):
        n = []
        def m(p):
            n.append(1)
            return json.dumps({"short": "Appointment moved to Tuesday at 4 pm."})
        ai = aitext.AiText(m)
        self.assertEqual(ai.short(LONG), ai.short(LONG))
        self.assertEqual(len(n), 1)


CHAT = "Ana: can you pick up the cake from Dolce at 5?\nAna: it is for Leo's birthday"


class ContextTest(unittest.TestCase):
    def test_short_prompt_has_earlier_messages_and_guard_counts_them(self):
        prompts = []

        def m(p):
            prompts.append(p)
            return json.dumps({"short": "Dr. Perez moved it to Tuesday at 4 pm. This is for Leo."})
        s = aitext.make_short(LONG, m, CHAT)
        self.assertIn("Earlier messages in the same chat", prompts[0])
        self.assertIn("cake from Dolce", prompts[0])
        self.assertEqual(s, "Dr. Perez moved it to Tuesday at 4 pm. This is for Leo.")   # Leo is only in the context
        self.assertEqual(aitext.make_short(LONG, m, ""), "")   # without the context, Leo is invented
        self.assertNotIn("Earlier messages", aitext.build_short_prompt(LONG))

    def test_short_cache_key_includes_context(self):
        n = []

        def m(p):
            n.append(1)
            return json.dumps({"short": "Appointment moved to Tuesday at 4 pm."})
        ai = aitext.AiText(m)
        ai.short(LONG, "a"); ai.short(LONG, "a"); ai.short(LONG, "b")
        self.assertEqual(len(n), 2)

    def test_context_is_cut(self):
        p = aitext.build_short_prompt(LONG, "x" * 5000)
        self.assertLess(p.count("x"), aitext.CONTEXT_CHARS + 5)


class RepliesTest(unittest.TestCase):
    MSG = "Ana: can you send me the report by Friday?"

    def model(self, replies, tasks, seen=None):
        def m(p):
            if seen is not None:
                seen.append(p)
            return json.dumps({"replies": replies, "tasks": tasks})
        return m

    def test_ok(self):
        seen = []
        r = aitext.make_replies(self.MSG, "en", self.model(["Yes, by Friday", "No, sorry", "Not sure yet"], ["Send Ana the report by Friday"], seen), "Ana: hi")
        self.assertEqual(r, {"replies": ["Yes, by Friday", "No, sorry", "Not sure yet"], "tasks": ["Send Ana the report by Friday"]})
        self.assertIn("in English", seen[0]); self.assertIn("Ana: hi", seen[0])

    def test_invented_items_are_dropped_one_by_one(self):
        r = aitext.make_replies(self.MSG, "en", self.model(["Yes, by Friday", "I will send it at 9", "Ask Marco"], ["Call Marco"]))
        self.assertEqual(r, {"replies": ["Yes, by Friday"], "tasks": []})
        self.assertEqual(aitext.make_replies(self.MSG, "en", self.model(["At 9"], ["Call Marco"])), {})

    def test_too_long_repeats_junk_and_no_model(self):
        long = " ".join(["word"] * 13)
        self.assertEqual(aitext.make_replies(self.MSG, "en", self.model([long, "Ok", "ok"], []))["replies"], ["Ok"])
        self.assertEqual(aitext.make_replies(self.MSG, "en", lambda p: "nope"), {})
        self.assertEqual(aitext.make_replies(self.MSG, "en", None), {})

        def down(p):
            raise OSError("x")
        self.assertEqual(aitext.make_replies(self.MSG, "en", down), {})

    def test_spanish_and_cache_with_context(self):
        seen = []
        ai = aitext.AiText(self.model(["Sí", "No", "No sé"], [], seen))
        self.assertEqual(ai.replies(self.MSG, "es", "c")["replies"], ["Sí", "No", "No sé"])
        ai.replies(self.MSG, "es", "c"); ai.replies(self.MSG, "es", "d")
        self.assertEqual(len(seen), 2)
        self.assertIn("in Spanish", seen[0])
        self.assertEqual(aitext.AiText(None).replies(self.MSG, "en"), {"replies": [], "tasks": []})


if __name__ == "__main__":
    unittest.main()


class TaskLineTest(unittest.TestCase):
    def test_task_line_uses_task_prompt_and_guard(self):
        seen = []
        def model(p):
            seen.append(p)
            return '{"short":"Comprar una protoboard de 400 contactos"}'
        src = "Placa de pruebas (protoboard) 400 contactos, ~5 euros, para montar circuitos sin soldar en el proyecto"
        self.assertEqual(aitext.make_short(src, model, "", "task"), "Comprar una protoboard de 400 contactos")
        self.assertIn("to-do item", seen[0])
        self.assertEqual(aitext.make_short(src, lambda p: '{"short":"Comprar 999 cables"}', "", "task"), "")   # invented number is refused
