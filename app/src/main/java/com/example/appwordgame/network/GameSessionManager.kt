package com.example.appwordgame.network

import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

enum class SessionStatus {
    IDLE,
    GENERATING_INVITATION,
    WAITING_FOR_ANSWER,
    CONNECTING,
    CONNECTED,
    WAITING_FOR_GAME_START,
    IN_GAME,
    ERROR,
    DISCONNECTED,
}

data class GameSessionState(
    val localNickname: String = "",
    val hostNickname: String = "",
    val players: List<PlayerInfo> = emptyList(),
    val status: SessionStatus = SessionStatus.IDLE,
    val invitationString: String? = null,
    val answerString: String? = null,
    val error: String? = null,
    val gameStateJson: String? = null,
)

class GameSessionManager(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    val localNickname: String,
    val isHost: Boolean,
) : Closeable {

    private val appContext = context.applicationContext
    val localPeerId = generatePeerId()

    // --- ARCHITEKTURA HUB (MULTI-PEER) ---
    private val activePeerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val activeDataChannels = ConcurrentHashMap<String, DataChannel>()

    private var pendingPeerConnection: PeerConnection? = null
    private var pendingDataChannel: DataChannel? = null
    //
    private var remotePeerId: String? = null
    private var remoteNickname: String? = null
    //
    private val collectedCandidates = mutableListOf<IceCandidate>()
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var gatheringDeferred: CompletableDeferred<Unit>? = null

    private val peerConnectionFactory: PeerConnectionFactory

    private val rtcConfig = PeerConnection.RTCConfiguration(
        arrayListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
    }

    private val _state = MutableStateFlow(GameSessionState(localNickname = localNickname))
    val state: StateFlow<GameSessionState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<NetworkMessage> = _messages.asSharedFlow()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        _state.update {
            it.copy(players = listOf(PlayerInfo(localPeerId, localNickname, true)))
        }
    }

    suspend fun generateInvitation(): String {
        updateStatus(SessionStatus.GENERATING_INVITATION)
        val connection = createPeerConnection()
        pendingPeerConnection = connection
        Log.d("GameSessionManager", "Connection created for a new client")

        val channel = connection.createDataChannel("game", DataChannel.Init())
        Log.d("GameSessionManager", "Data channel created")
        attachDataChannel(channel, isPending = true)
        Log.d("GameSessionManager", "Data channel attached")

        val offer = connection.createOfferAsync()
        connection.setLocalDescriptionAsync(offer)
        waitForIceGathering()

        val combined = buildCombinedString(offer)
        _state.update { curr ->
            val nextStatus = if (curr.status.ordinal < SessionStatus.WAITING_FOR_ANSWER.ordinal) {
                SessionStatus.WAITING_FOR_ANSWER
            } else {
                curr.status
            }
            curr.copy(invitationString = combined, status = nextStatus)
        }
        return combined
    }

    suspend fun applyAnswer(answerJson: String): Boolean {
        updateStatus(SessionStatus.CONNECTING)
        return try {
            val json = JSONObject(answerJson)
            val sdpObj = json.getJSONObject("sdp")
            val sdp = SessionDescription(
                SessionDescription.Type.ANSWER,
                sdpObj.getString("sdp")
            )

            pendingPeerConnection?.setRemoteDescriptionAsync(sdp)

            val iceArray = json.optJSONArray("ice") ?: JSONArray()
            for (i in 0 until iceArray.length()) {
                val iceJson = iceArray.getJSONObject(i)
                val candidate = IceCandidate(
                    iceJson.getString("sdpMid"),
                    iceJson.getInt("sdpMLineIndex"),
                    iceJson.getString("candidate"),
                )
                pendingRemoteCandidates.add(candidate)
            }
            flushPendingCandidates(pendingPeerConnection)
            true
        } catch (e: Exception) {
            _state.update { it.copy(error = "Failed to apply answer: ${e.message}", status = SessionStatus.ERROR) }
            false
        }
    }

    suspend fun createAnswerFromInvitation(inviteJson: String): String {
        updateStatus(SessionStatus.GENERATING_INVITATION)
        return try {
            val json = JSONObject(inviteJson)
            val sdpObj = json.getJSONObject("sdp")
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpObj.getString("sdp"))

            val connection = createPeerConnection()
            pendingPeerConnection = connection
            connection.setRemoteDescriptionAsync(sdp)

            val iceArray = json.optJSONArray("ice") ?: JSONArray()
            for (i in 0 until iceArray.length()) {
                val iceJson = iceArray.getJSONObject(i)
                val candidate = IceCandidate(
                    iceJson.getString("sdpMid"),
                    iceJson.getInt("sdpMLineIndex"),
                    iceJson.getString("candidate"),
                )
                pendingRemoteCandidates.add(candidate)
            }
            flushPendingCandidates(connection)

            val answer = connection.createAnswerAsync()
            connection.setLocalDescriptionAsync(answer)
            waitForIceGathering()

            val combined = buildCombinedString(answer)
            _state.update { curr ->
                val nextStatus = if (curr.status.ordinal < SessionStatus.CONNECTING.ordinal) {
                    SessionStatus.CONNECTING
                } else {
                    curr.status
                }
                curr.copy(answerString = combined, status = nextStatus)
            }
            combined
        } catch (e: Exception) {
            _state.update { it.copy(error = "Failed to create answer: ${e.message}", status = SessionStatus.ERROR) }
            throw e
        }
    }

    // BROADCASTING
    fun sendMessage(message: NetworkMessage) {
        val json = JSONObject().apply {
            put("type", "game-msg")
            put("msgType", message.type.name)
            put("fromPeer", message.fromPeer)
            message.payload?.let { put("payload", it) }
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)

        activeDataChannels.forEach { (peerId, dc) ->
            if (dc.state() == DataChannel.State.OPEN) {
                dc.send(buffer)
                Log.d("GameSessionManager", "Sent message ${message.type} to peer: $peerId")
            }
        }
    }

    fun startGame(gameStateJson: String) {
        if (isHost) {
            sendMessage(NetworkMessage(MessageType.START_GAME, localPeerId, gameStateJson))
            _state.update { it.copy(gameStateJson = gameStateJson, status = SessionStatus.IN_GAME) }
        }
    }

    override fun close() {
        activeDataChannels.values.forEach { it.close() }
        activePeerConnections.values.forEach { it.close() }
        pendingDataChannel?.close()
        pendingPeerConnection?.close()
        scope.cancel()
        peerConnectionFactory.dispose()
    }

    private fun createPeerConnection(): PeerConnection {
        gatheringDeferred = CompletableDeferred()
        collectedCandidates.clear()

        var connectionRef: PeerConnection? = null
        
        val connection = requireNotNull(
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        Log.d("GameSessionManager", "ICE Connection State: $newState")
                        when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                if (_state.value.status.ordinal < SessionStatus.CONNECTED.ordinal) {
                                    updateStatus(SessionStatus.CONNECTED)
                                }
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED -> {
                                handleDisconnectForConnection(connectionRef)
                            }
                            else -> Unit
                        }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                        Log.d("GameSessionManager", "ICE Gathering State: $newState")
                        if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                            gatheringDeferred?.complete(Unit)
                        }
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let { collectedCandidates.add(it) }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onDataChannel(channel: DataChannel?) {
                        Log.d("GameSessionManager", "Remote DataChannel received")
                        if (channel != null) attachDataChannel(channel, isPending = true)
                    }

                    override fun onRenegotiationNeeded() = Unit
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
                }
            )
        )
        connectionRef = connection
        return connection
    }

    private fun attachDataChannel(channel: DataChannel, isPending: Boolean) {
        if (isPending) {
            pendingDataChannel = channel
        }

        pendingDataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val newState = channel.state()
                Log.d("GameSessionManager", "DataChannel State: $newState")
                if (channel.state() == DataChannel.State.OPEN) {
                    flushPendingCandidates(pendingPeerConnection)
                    sendHello(channel) // Przedstawiamy się nowemu połączonemu węzłowi
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleMessage(bytes.toString(Charsets.UTF_8), channel)
            }
        })
    }

    private fun sendHello(channel: DataChannel) {
        Log.d("GameSessionManager", "Sending Hello (isHost=$isHost)")
        val json = JSONObject().apply {
            put("type", "hello")
            put("peerId", localPeerId)
            put("nickname", localNickname)
            put("isHost", isHost)
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun handleMessage(text: String, channel: DataChannel) {
        Log.d("GameSessionManager", "Message received: $text")
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> handleHello(json, channel)
            "peer-info" -> handlePeerInfo(json)
            "game-msg" -> handleGameMessage(json)
        }
    }

    private fun handleHello(json: JSONObject, channel: DataChannel) {
        val remoteId = json.optString("peerId").takeIf { it.isNotBlank() } ?: return
        val remoteNickname = json.optString("nickname").takeIf { it.isNotBlank() } ?: remoteId

        activeDataChannels[remoteId] = channel
        pendingPeerConnection?.let { activePeerConnections[remoteId] = it }

        pendingDataChannel = null
        pendingPeerConnection = null

        // Dodajemy gracza do stanu UI
        val currentPlayers = _state.value.players.toMutableList()
        if (currentPlayers.none { it.id == remoteId }) {
            currentPlayers.add(PlayerInfo(remoteId, remoteNickname, true))
        } else {
            val idx = currentPlayers.indexOfFirst { it.id == remoteId }
            currentPlayers[idx] = currentPlayers[idx].copy(isConnected = true)
        }

        val hostNickname = if (isHost) localNickname else remoteNickname

        _state.update { curr ->
            val nextStatus = if (!isHost) {
                if (curr.status.ordinal < SessionStatus.WAITING_FOR_GAME_START.ordinal) SessionStatus.WAITING_FOR_GAME_START else curr.status
            } else {
                if (curr.status.ordinal < SessionStatus.CONNECTED.ordinal) SessionStatus.CONNECTED else curr.status
            }
            curr.copy(
                hostNickname = hostNickname,
                players = currentPlayers,
                status = nextStatus,
            )
        }

        // Host musi ogłosić zaktualizowaną listę graczy wszystkim (w tym nowemu graczowi)
        if (isHost) {
            broadcastPeerInfo()
        }
    }

    private fun broadcastPeerInfo() {
        val payload = JSONObject().apply {
            put("type", "peer-info")
            put("peerId", localPeerId)
            put("nickname", localNickname)
            put("isHost", isHost)
            put("players", JSONArray(_state.value.players.map { p ->
                JSONObject().apply {
                    put("id", p.id)
                    put("nickname", p.nickname)
                    put("connected", p.isConnected)
                }
            }))
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(bytes), false)

        activeDataChannels.values.forEach { dc ->
            if (dc.state() == DataChannel.State.OPEN) dc.send(buffer)
        }
    }

    private fun handlePeerInfo(json: JSONObject) {
        if (isHost) return // Host jest źródłem prawdy, nie przetwarza tego

        val playersArray = json.optJSONArray("players") ?: return
        val updatedPlayers = (0 until playersArray.length()).map { i ->
            val obj = playersArray.getJSONObject(i)
            PlayerInfo(
                id = obj.getString("id"),
                nickname = obj.getString("nickname"),
                isConnected = obj.optBoolean("connected", true),
            )
        }

        _state.update { it.copy(players = updatedPlayers) }
    }

    private fun handleGameMessage(json: JSONObject) {
        val msgType = runCatching { MessageType.valueOf(json.optString("msgType")) }.getOrNull() ?: return
        val fromPeer = json.optString("fromPeer", remotePeerId ?: "")
        val payload = json.optString("payload", "").ifBlank { null }

        if (msgType == MessageType.START_GAME) {
            _state.update { it.copy(gameStateJson = payload, status = SessionStatus.IN_GAME) }
        }

        scope.launch {
            _messages.emit(NetworkMessage(type = msgType, fromPeer = fromPeer, payload = payload))
        }
    }

    private fun handleDisconnectForConnection(connection: PeerConnection?) {
        if (connection == null) return

        // Szukamy, który gracz stracił połączenie
        val disconnectedPeerId = activePeerConnections.entries.firstOrNull { it.value == connection }?.key

        if (disconnectedPeerId != null) {
            Log.d("GameSessionManager", "Peer disconnected: $disconnectedPeerId")

            // Oznaczamy go jako offline w UI
            val currentPlayers = _state.value.players.toMutableList()
            val idx = currentPlayers.indexOfFirst { it.id == disconnectedPeerId }
            if (idx != -1) {
                currentPlayers[idx] = currentPlayers[idx].copy(isConnected = false)
            }

            _state.update { it.copy(players = currentPlayers) }

            if (isHost) {
                broadcastPeerInfo()
                sendMessage(NetworkMessage(MessageType.PLAYER_DISCONNECT, disconnectedPeerId))
            } else {
                // Jeśli my jesteśmy klientem i straciliśmy połączenie (czyli padł Host)
                _state.update { it.copy(status = SessionStatus.ERROR, error = "Utracono połączenie z Hostem") }
            }

            // Sprzątanie martwych połączeń
            activePeerConnections.remove(disconnectedPeerId)
            activeDataChannels.remove(disconnectedPeerId)
        }
    }

    private fun sendJson(json: JSONObject) {
        val dc = pendingDataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) {
            Log.w("GameSessionManager", "Cannot send JSON: DataChannel is not open")
            return
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun buildCombinedString(description: SessionDescription): String {
        return JSONObject().apply {
            put("sdp", JSONObject().apply {
                put("type", description.type.canonicalName())
                put("sdp", description.description)
            })
            put("ice", JSONArray(collectedCandidates.map { c ->
                JSONObject().apply {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                }
            }))
        }.toString(2)
    }

    private fun flushPendingCandidates(connection: PeerConnection?) {
        if (pendingRemoteCandidates.isEmpty() || connection == null) return
        Log.d("GameSessionManager", "Flushing ${pendingRemoteCandidates.size} candidates")
        val queued = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        queued.forEach { candidate ->
            connection.addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
                override fun onAddSuccess() = Unit
                override fun onAddFailure(error: String?) = Unit
            })
        }
    }

    private suspend fun waitForIceGathering() {
        val pc = pendingPeerConnection ?: return
        if (pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return
        
        try {
            withTimeout(3000) {
                gatheringDeferred?.await()
            }
        } catch (e: Exception) {
            Log.w("GameSessionManager", "ICE gathering wait finished (timeout or complete)")
        }
    }

    private fun updateStatus(status: SessionStatus) {
        _state.update { curr ->
            // Only update if it's not a downgrade of status (except for ERROR/DISCONNECTED)
            if (status == SessionStatus.ERROR || status == SessionStatus.DISCONNECTED || status.ordinal > curr.status.ordinal) {
                curr.copy(status = status)
            } else {
                curr
            }
        }
    }

    private fun generatePeerId(): String = UUID.randomUUID().toString().take(8)

    private fun SessionDescription.Type.canonicalName(): String = when (this) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }

    private suspend fun PeerConnection.createOfferAsync(): SessionDescription = suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String) { if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(e))) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String) = Unit
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.createAnswerAsync(): SessionDescription = suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription) { if (cont.isActive) cont.resume(d) }
            override fun onCreateFailure(e: String) { if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(e))) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(e: String) = Unit
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocalDescriptionAsync(desc: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String) { if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(e))) }
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(e: String) = Unit
        }, desc)
    }

    private suspend fun PeerConnection.setRemoteDescriptionAsync(desc: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(e: String) { if (cont.isActive) cont.resumeWith(Result.failure(RuntimeException(e))) }
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(e: String) = Unit
        }, desc)
    }

    private suspend fun PeerConnection.addIceCandidateAsync(candidate: IceCandidate) = suspendCancellableCoroutine<Unit> { cont ->
        addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onAddFailure(e: String?) { if (cont.isActive) cont.resume(Unit) }
        })
    }

}
