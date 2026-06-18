package com.example.appwordgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.appwordgame.game.GameEngine
import com.example.appwordgame.game.Player
import com.example.appwordgame.network.GameSessionManager
import com.example.appwordgame.network.SerializedGameState
import com.example.appwordgame.network.SessionStatus
import com.example.appwordgame.ui.game.GameScreen
import com.example.appwordgame.ui.lobby.AddPlayerScreen
import com.example.appwordgame.ui.lobby.AddPlayerViewModel
import com.example.appwordgame.ui.lobby.CreateGameScreen
import com.example.appwordgame.ui.lobby.JoinGameScreen
import com.example.appwordgame.ui.lobby.JoinGameViewModel
import com.example.appwordgame.ui.lobby.LobbyViewModel
import com.example.appwordgame.ui.theme.AppWordGameTheme
import com.example.appwordgame.ui.welcome.WelcomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppWordGameTheme(dynamicColor = false) {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as WordGameApplication

    NavHost(
        navController = navController,
        startDestination = "welcome",
        modifier = Modifier.fillMaxSize(),
    ) {
        composable("welcome") {
            WelcomeScreen(
                onCreateGame = { nickname ->
                    app.setGameSessionManager(
                        GameSessionManager(context, localNickname = nickname, isHost = true)
                    )
                    navController.navigate("create_game") {
                        popUpTo("welcome") { inclusive = false }
                    }
                },
                onJoinGame = {
                    navController.navigate("join_game") {
                        popUpTo("welcome") { inclusive = false }
                    }
                },
            )
        }

        composable("create_game") {
            val sm = app.gameSessionManager
            if (sm == null || !sm.isHost) {
                navController.navigate("welcome") { popUpTo("welcome") { inclusive = true } }
                return@composable
            }

            val lobbyVm: LobbyViewModel = viewModel()
            val sessionState by sm.state.collectAsState()

            LaunchedEffect(sessionState) {
                lobbyVm.updateFromSession(sessionState)
            }
            LaunchedEffect(sessionState.status) {
                if (sessionState.status == SessionStatus.IN_GAME) {
                    navController.navigate("game") {
                        popUpTo("welcome") { inclusive = false }
                    }
                }
            }

            CreateGameScreen(
                viewModel = lobbyVm,
                onAddPlayer = { navController.navigate("add_player") },
                onStartGame = {
                    val dict = app.dictionary.value
                    if (dict != null && sessionState.players.size >= 2) {
                        val engine = GameEngine(dict, playerCount = sessionState.players.size.coerceIn(2, 4))
                        val activePlayers = Player.activePlayers(sessionState.players.size)
                        val nicknames = mutableMapOf<Player, String>()
                        sessionState.players.forEachIndexed { idx, info ->
                            activePlayers.getOrNull(idx)?.let { nicknames[it] = info.nickname }
                        }
                        sm.startGame(SerializedGameState.fromGameEngine(engine, nicknames).toJson())
                    }
                },
                onBack = {
                    app.setGameSessionManager(null)
                    navController.navigate("welcome") { popUpTo("welcome") { inclusive = true } }
                },
            )
        }

        composable("add_player") {
            val sm = app.gameSessionManager
            if (sm == null || !sm.isHost) {
                navController.navigate("welcome") { popUpTo("welcome") { inclusive = true } }
                return@composable
            }

            val addVm: AddPlayerViewModel = viewModel()
            val sessionState by sm.state.collectAsState()

            LaunchedEffect(sessionState.status) {
                if (sessionState.status == SessionStatus.CONNECTED) addVm.onConnected()
            }

            AddPlayerScreen(
                viewModel = addVm,
                onGenerateInvitation = { sm.generateInvitation() },
                onConnect = { answer -> sm.applyAnswer(answer) },
                onBack = { navController.popBackStack() },
                onConnected = { navController.popBackStack() },
            )
        }

        composable("join_game") {
            val joinVm: JoinGameViewModel = viewModel()
            val sessionRef = remember { mutableStateOf<GameSessionManager?>(null) }
            val mgr = sessionRef.value

            if (mgr != null && !mgr.isHost) {
                val st by mgr.state.collectAsState()
                LaunchedEffect(st.status, st.hostNickname, st.players) {
                    when (st.status) {
                        SessionStatus.CONNECTED, SessionStatus.WAITING_FOR_GAME_START -> {
                            joinVm.onLobbyUpdated(st.hostNickname, st.players)
                        }
                        SessionStatus.IN_GAME -> {
                            navController.navigate("game") {
                                popUpTo("welcome") { inclusive = false }
                            }
                        }
                        SessionStatus.ERROR -> {
                            joinVm.onError(st.error ?: "Connection error")
                        }
                        else -> {}
                    }
                }
            }

            JoinGameScreen(
                viewModel = joinVm,
                onGenerateAnswer = { nickname, invitation ->
                    val manager = GameSessionManager(context, localNickname = nickname, isHost = false)
                    app.setGameSessionManager(manager)
                    sessionRef.value = manager
                    manager.createAnswerFromInvitation(invitation)
                },
                onBack = {
                    app.setGameSessionManager(null)
                    navController.navigate("welcome") { popUpTo("welcome") { inclusive = true } }
                },
            )
        }

        composable("game") {
            val sessionManager = app.gameSessionManager
            val sessionState by sessionManager?.state?.collectAsState()
                ?: remember { mutableStateOf(null) }
            val initialState = sessionState?.gameStateJson
            val players = sessionState?.players ?: emptyList()
            val nicknames = players.mapIndexed { idx, info -> idx to info.nickname }.toMap()
            val localIdx = if (sessionManager?.isHost == true) {
                0
            } else {
                val myNickname = sessionManager?.localNickname
                val foundIdx = players.indexOfFirst { it.nickname == myNickname }
                if (foundIdx >= 0) foundIdx else 1
            }

            DisposableEffect(Unit) {
                onDispose { app.setGameSessionManager(null) }
            }

            GameScreen(
                sessionManager = sessionManager,
                initialState = initialState,
                playerNicknames = nicknames,
                localPlayerIndex = localIdx,
                isHost = sessionManager?.isHost ?: false,
                playerCount = players.size.coerceIn(2, 4),
            )
        }
    }
}
