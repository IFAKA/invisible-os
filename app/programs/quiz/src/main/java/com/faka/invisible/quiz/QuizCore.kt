package com.faka.invisible.quiz

import java.util.Random

/** One question: [choices] has the right answer first (the game shuffles them when it asks). */
class Question(val text: String, val right: String, val wrong1: String, val wrong2: String)

/** A question as asked: three choices in a random order, [answer] is the index of the right one. */
class Asked(val text: String, val choices: List<String>, val answer: Int)

/** Pure rules and data of the Quiz. */
object QuizCore {
    const val PER_GAME = 10

    val questions: List<Question> = listOf(
        Question("How many days are in a week?", "Seven", "Five", "Ten"),
        Question("What color is the sky on a clear day?", "Blue", "Green", "Red"),
        Question("Which animal says moo?", "A cow", "A dog", "A cat"),
        Question("How many legs does a spider have?", "Eight", "Six", "Ten"),
        Question("What is the capital of France?", "Paris", "Rome", "Madrid"),
        Question("What is the capital of Spain?", "Madrid", "Lisbon", "Paris"),
        Question("What is the capital of Italy?", "Rome", "Venice", "Milan"),
        Question("What is the capital of Japan?", "Tokyo", "Kyoto", "Seoul"),
        Question("What is the capital of Argentina?", "Buenos Aires", "Santiago", "Lima"),
        Question("What is the biggest ocean?", "The Pacific", "The Atlantic", "The Indian"),
        Question("Which planet is closest to the sun?", "Mercury", "Venus", "Mars"),
        Question("Which planet is called the red planet?", "Mars", "Jupiter", "Saturn"),
        Question("What is the biggest planet?", "Jupiter", "Earth", "Neptune"),
        Question("What do bees make?", "Honey", "Milk", "Butter"),
        Question("What gas do we breathe in to live?", "Oxygen", "Helium", "Carbon"),
        Question("What is frozen water called?", "Ice", "Steam", "Fog"),
        Question("How many minutes are in an hour?", "Sixty", "Fifty", "One hundred"),
        Question("How many months are in a year?", "Twelve", "Ten", "Eleven"),
        Question("What is two times nine?", "Eighteen", "Sixteen", "Twenty"),
        Question("What is half of one hundred?", "Fifty", "Twenty five", "Forty"),
        Question("How many sides does a triangle have?", "Three", "Four", "Five"),
        Question("How many sides does a square have?", "Four", "Three", "Six"),
        Question("What is the largest animal on Earth?", "The blue whale", "The elephant", "The shark"),
        Question("Which animal is the fastest on land?", "The cheetah", "The horse", "The lion"),
        Question("Which bird cannot fly?", "The penguin", "The eagle", "The duck"),
        Question("What is the hottest season?", "Summer", "Winter", "Spring"),
        Question("Which body part pumps blood?", "The heart", "The lung", "The knee"),
        Question("How many days are in a leap year?", "366", "365", "364"),
        Question("What is the first letter of the alphabet?", "A", "B", "Z"),
        Question("What language do people speak in Brazil?", "Portuguese", "Spanish", "French"),
        Question("Which continent is Egypt in?", "Africa", "Asia", "Europe"),
        Question("Which is the longest river in South America?", "The Amazon", "The Nile", "The Danube"),
        Question("Who painted the Mona Lisa?", "Leonardo da Vinci", "Picasso", "Van Gogh"),
        Question("Who wrote Romeo and Juliet?", "Shakespeare", "Dickens", "Twain"),
        Question("What metal is liquid at room temperature?", "Mercury", "Iron", "Gold"),
        Question("What do we call a baby dog?", "A puppy", "A kitten", "A calf"),
        Question("Which instrument has black and white keys?", "The piano", "The drum", "The flute"),
        Question("How many players are on a soccer team on the field?", "Eleven", "Nine", "Fifteen"),
        Question("What shape is a ball?", "A sphere", "A cube", "A cone"),
        Question("Which season comes after winter?", "Spring", "Autumn", "Summer"),
    )

    /** [n] different questions, each with its choices shuffled. */
    fun pick(rng: Random, n: Int = PER_GAME): List<Asked> = questions.shuffled(rng).take(n).map { q ->
        val order = listOf(q.right, q.wrong1, q.wrong2).shuffled(rng)
        Asked(q.text, order, order.indexOf(q.right))
    }
}
