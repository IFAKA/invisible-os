import unittest
import choice

SINGLE = """
 ☐ Approach

Which approach do you want for the menu?

❯ 1. Tree
     Group apps by what you want to do
  2. Flat list
     One long list, most used first
  3. Type something.
────────────────────────────────────────
  4. Chat about this

Enter to select · ↑/↓ to navigate · Esc to cancel
""".splitlines()

MULTI = """
← ☐ Features  ☐ Sounds  ✔ Submit →

Which features should I build?

  1. [ ] Digest
       One line over the notifications
❯ 2. [x] Short
       A short version of long messages
  3. [ ] Type something
  4. Submit

Enter to select · ↑/↓ to navigate · Esc to cancel
""".splitlines()

PLAN = """
 Ready to code?

 Here is Claude's plan:
 ╌╌╌╌╌╌╌╌╌╌
  Add a digest.
 ╌╌╌╌╌╌╌╌╌╌

 Would you like to proceed?

 ❯ 1. Yes, and auto-accept edits
   2. Yes, and manually approve edits
   3. No, keep planning

 ctrl-g to edit in Vim · Esc to cancel
""".splitlines()


class ChoiceTest(unittest.TestCase):
    def test_single(self):
        m = choice.parse_screen(SINGLE)
        self.assertEqual(m["question"], "Which approach do you want for the menu?")
        self.assertFalse(m["multi"])
        self.assertEqual([(o["n"], o["label"], o["desc"]) for o in m["options"]],
                         [(1, "Tree", "Group apps by what you want to do"), (2, "Flat list", "One long list, most used first")])
        self.assertEqual((m["other"], m["cursor"]), (3, 1))
        self.assertTrue(choice.public(m)["other"])

    def test_multi(self):
        m = choice.parse_screen(MULTI)
        self.assertTrue(m["multi"])
        self.assertEqual([o["label"] for o in m["options"]], ["Digest", "Short"])
        self.assertEqual((m["other"], m["submit"], m["cursor"], m["checked"]), (3, 4, 2, [2]))

    def test_plan(self):
        m = choice.parse_screen(PLAN)
        self.assertEqual(m["question"], "Would you like to proceed?")
        self.assertEqual(len(m["options"]), 3)
        self.assertEqual(m["other"], 0)

    def test_not_menus(self):
        self.assertIsNone(choice.parse_screen(["$ ls", "1. one", "2. two", "$ "]))
        # a numbered list in Claude's message, no cursor and no footer
        self.assertIsNone(choice.parse_screen(["Steps:", "1. Do a", "2. Do b", "> "]))
        # a footer but no cursor
        self.assertIsNone(choice.parse_screen(["Q?", "  1. a", "  2. b", "Esc to cancel"]))
        self.assertIsNone(choice.parse_screen([]))

    def test_moves(self):
        self.assertEqual(choice.moves(1, 3), ["Down", "Down"])
        self.assertEqual(choice.moves(3, 1), ["Up", "Up"])
        self.assertEqual(choice.moves(2, 2), [])


if __name__ == "__main__":
    unittest.main()
