package com.example.appwordgame.game

import android.content.res.AssetManager

class AssetDictionary(
    assetManager: AssetManager,
    fileName: String = "short_wordlist.txt"
) : Dictionary {

    private val words: HashSet<String>

    init {
        words = assetManager.open(fileName).use { stream ->
            val set = HashSet<String>(150_000)
            stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val word = line.trim().uppercase()
                if (word.length in 2..15) set.add(word)
            }
            set
        }
    }

    override fun isValid(word: String): Boolean = word.uppercase() in words

    val size: Int get() = words.size
}
