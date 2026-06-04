package com.example.appwordgame

import android.app.Application
import com.example.appwordgame.game.AssetDictionary
import com.example.appwordgame.game.Dictionary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WordGameApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _dictionary = MutableStateFlow<Dictionary?>(null)

    // Emits null while loading, non-null once the word list is ready.
    val dictionary: StateFlow<Dictionary?> = _dictionary.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            _dictionary.value = AssetDictionary(assets)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        scope.cancel()
    }
}
