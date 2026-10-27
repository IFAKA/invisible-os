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


if __name__ == "__main__":
    unittest.main()
