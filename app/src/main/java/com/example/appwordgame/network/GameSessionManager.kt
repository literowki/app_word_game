package com.example.appwordgame.network

import android.content.Context
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
    private val localPeerId = generatePeerId()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var remotePeerId: String? = null
    private var remoteNickname: String? = null
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
    }

    suspend fun generateInvitation(): String {
        updateStatus(SessionStatus.GENERATING_INVITATION)
        val connection = createPeerConnection()
        peerConnection = connection
        Log.d("GameSessionManager", "Connection created")
        val channel = connection.createDataChannel("game", DataChannel.Init())
        Log.d("GameSessionManager", "Data channel created")
        attachDataChannel(channel)
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
            peerConnection?.setRemoteDescriptionAsync(sdp)
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
            flushPendingCandidates()
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
            peerConnection = connection
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
            flushPendingCandidates()

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

    fun sendMessage(message: NetworkMessage) {
        val dc = dataChannel
        if (dc == null || dc.state() != DataChannel.State.OPEN) return
        val json = JSONObject().apply {
            put("type", "game-msg")
            put("msgType", message.type.name)
            put("fromPeer", message.fromPeer)
            message.payload?.let { put("payload", it) }
        }
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    fun startGame(gameStateJson: String) {
        if (isHost) {
            sendMessage(NetworkMessage(MessageType.START_GAME, localPeerId, gameStateJson))
            _state.update { it.copy(gameStateJson = gameStateJson, status = SessionStatus.IN_GAME) }
        }
    }

    override fun close() {
        dataChannel?.close()
        peerConnection?.close()
        scope.cancel()
        peerConnectionFactory.dispose()
    }

    private fun createPeerConnection(): PeerConnection {
        gatheringDeferred = CompletableDeferred()
        collectedCandidates.clear()
        
        val connection = requireNotNull(
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        Log.d("GameSessionManager", "ICE Connection State: $newState")
                        when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED -> {
                                if (isHost) {
                                    _state.update { state ->
                                        state.copy(
                                            players = state.players.map { it.copy(isConnected = true) },
                                            status = SessionStatus.CONNECTED,
                                            error = null,
                                        )
                                    }
                                    sendMessage(NetworkMessage(MessageType.PLAYER_RECONNECT, localPeerId))
                                }
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                if (isHost) {
                                    _state.update { state ->
                                        state.copy(
                                            players = state.players.map { it.copy(isConnected = false) },
                                            status = SessionStatus.DISCONNECTED,
                                            error = "Player disconnected",
                                        )
                                    }
                                    sendMessage(NetworkMessage(MessageType.PLAYER_DISCONNECT, localPeerId))
                                } else {
                                    _state.update {
                                        it.copy(
                                            status = SessionStatus.ERROR,
                                            error = "Host disconnected",
                                        )
                                    }
                                }
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                _state.update {
                                    it.copy(
                                        error = "Connection failed",
                                        status = SessionStatus.ERROR,
                                    )
                                }
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
                        if (candidate != null) {
                            collectedCandidates.add(candidate)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onDataChannel(channel: DataChannel?) {
                        Log.d("GameSessionManager", "Remote DataChannel received")
                        if (channel != null) attachDataChannel(channel)
                    }

                    override fun onRenegotiationNeeded() = Unit
                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
                }
            )
        )
        return connection
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel?.unregisterObserver()
        dataChannel = channel
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val newState = channel.state()
                Log.d("GameSessionManager", "DataChannel State: $newState")
                if (newState == DataChannel.State.OPEN) {
                    flushPendingCandidates()
                    sendHello()
                    _state.update { curr ->
                        val nextStatus = if (curr.status.ordinal < SessionStatus.CONNECTED.ordinal) {
                            SessionStatus.CONNECTED
                        } else {
                            curr.status
                        }
                        curr.copy(status = nextStatus)
                    }
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleMessage(bytes.toString(Charsets.UTF_8))
            }
        })
    }

    private fun sendHello() {
        Log.d("GameSessionManager", "Sending Hello (isHost=$isHost)")
        val json = JSONObject().apply {
            put("type", "hello")
            put("peerId", localPeerId)
            put("nickname", localNickname)
            put("isHost", isHost)
        }
        sendJson(json)
    }

    private fun handleMessage(text: String) {
        Log.d("GameSessionManager", "Message received: $text")
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> handleHello(json)
            "peer-info" -> handlePeerInfo(json)
            "game-msg" -> handleGameMessage(json)
        }
    }

    private fun handleHello(json: JSONObject) {
        val peerId = json.optString("peerId").takeIf { it.isNotBlank() } ?: return
        val nickname = json.optString("nickname").takeIf { it.isNotBlank() } ?: peerId
        remotePeerId = peerId
        remoteNickname = nickname

        val hostNickname = if (isHost) localNickname else nickname
        val players = listOf(
            PlayerInfo(id = localPeerId, nickname = localNickname, isConnected = true),
            PlayerInfo(id = peerId, nickname = nickname, isConnected = true),
        )
        _state.update { curr ->
            val nextStatus = if (!isHost) {
                if (curr.status.ordinal < SessionStatus.WAITING_FOR_GAME_START.ordinal) SessionStatus.WAITING_FOR_GAME_START else curr.status
            } else {
                if (curr.status.ordinal < SessionStatus.CONNECTED.ordinal) SessionStatus.CONNECTED else curr.status
            }
            curr.copy(
                hostNickname = hostNickname,
                players = players,
                status = nextStatus,
            )
        }

        if (isHost) {
            sendJson(JSONObject().apply {
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
            })
        }
    }

    private fun handlePeerInfo(json: JSONObject) {
        val peerId = json.optString("peerId").takeIf { it.isNotBlank() } ?: return
        val nickname = json.optString("nickname").takeIf { it.isNotBlank() } ?: peerId
        remotePeerId = peerId
        remoteNickname = nickname
        val hostNickname = if (isHost) localNickname else nickname

        val players = if (json.has("players")) {
            val arr = json.getJSONArray("players")
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PlayerInfo(
                    id = obj.getString("id"),
                    nickname = obj.getString("nickname"),
                    isConnected = obj.optBoolean("connected", true),
                )
            }
        } else {
            listOf(
                PlayerInfo(id = localPeerId, nickname = localNickname, isConnected = true),
                PlayerInfo(id = peerId, nickname = nickname, isConnected = true),
            )
        }
        _state.update { curr ->
            val nextStatus = if (!isHost) {
                if (curr.status.ordinal < SessionStatus.WAITING_FOR_GAME_START.ordinal) SessionStatus.WAITING_FOR_GAME_START else curr.status
            } else {
                if (curr.status.ordinal < SessionStatus.CONNECTED.ordinal) SessionStatus.CONNECTED else curr.status
            }
            curr.copy(
                players = players,
                hostNickname = hostNickname,
                status = nextStatus,
            )
        }
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

    private fun sendJson(json: JSONObject) {
        val dc = dataChannel
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

    private fun flushPendingCandidates() {
        if (pendingRemoteCandidates.isEmpty()) return
        Log.d("GameSessionManager", "Flushing ${pendingRemoteCandidates.size} candidates")
        val queued = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        queued.forEach { candidate ->
            peerConnection?.addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
                override fun onAddSuccess() = Unit
                override fun onAddFailure(error: String?) = Unit
            })
        }
    }

    private suspend fun waitForIceGathering() {
        val pc = peerConnection ?: return
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
