package com.example.appwordgame.game

interface Dictionary {
    fun isValid(word: String): Boolean
}

// In-memory dictionary backed by a set of words. Used in tests and can be used with
// a Polish word list loaded from assets (e.g. SJP Scrabble list from sjp.pl/sl/growy.php).
class SetDictionary(words: Collection<String>) : Dictionary {
    private val wordSet: HashSet<String> = words.mapTo(HashSet()) { it.uppercase() }

    override fun isValid(word: String): Boolean = word.uppercase() in wordSet
}
