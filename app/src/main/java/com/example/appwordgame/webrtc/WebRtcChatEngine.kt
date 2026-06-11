package com.example.appwordgame.webrtc

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume


data class ChatMessage(
    val text: String,
    val mine: Boolean,
    val timestamp: String,
    val fromPeerId: String? = null
)

data class WebRtcChatUiState(
    val connectionState: String = "Disconnected",
    val iceGatheringState: String = "New",
    val dataChannelState: String = "Closed",
    val isDataChannelOpen: Boolean = false,
    val role: String = "Idle",
    val localPeerId: String = "",
    val connectedPeers: List<String> = emptyList(),
    val relayPeerId: String? = null,
    val localDescription: String = "",
    val localCandidates: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val logLines: List<String> = listOf("${nowLabel()} Ready. Bootstrap once, then the mesh connects automatically.")
)

class WebRtcChatEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val peerStates = mutableMapOf<String, PeerState>()
    private var pendingPeer: PeerState? = null
    private val localPeerId = generatePeerId()
    private var relayPeerId: String? = null

    private val peerConnectionFactory: PeerConnectionFactory

    private val rtcConfig = PeerConnection.RTCConfiguration(
        arrayListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private val _state = MutableStateFlow(WebRtcChatUiState(localPeerId = localPeerId))
    val state: StateFlow<WebRtcChatUiState> = _state.asStateFlow()
    

    private data class PeerState(
        var peerId: String?,
        val connection: PeerConnection,
        var dataChannel: DataChannel? = null,
        var dataChannelObserver: DataChannel.Observer? = null,
        val pendingRemoteCandidates: MutableList<IceCandidate> = mutableListOf(),
        val isBootstrap: Boolean = false,
        var iceConnectionState: PeerConnection.IceConnectionState = PeerConnection.IceConnectionState.NEW,
        var iceGatheringState: PeerConnection.IceGatheringState = PeerConnection.IceGatheringState.NEW
    )

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        appendLog("WebRTC factory initialized.")
    }

    fun createOffer() {
        scope.launch {
            updateRole("Offerer")
            val peerState = ensureBootstrapPeer()
            ensureLocalDataChannel(peerState)

            val offer = try {
                peerState.connection.createOfferAsync()
            } catch (e: Exception) {
                appendLog("Failed to create offer: ${e.message}")
                return@launch
            }
            
            try {
                peerState.connection.setLocalDescriptionAsync(offer)
                updateState { it.copy(localDescription = offer.toPrettyJson()) }
                appendLog("Local offer created. Copy it to the remote peer.")
            } catch (e: Exception) {
                appendLog("Failed to set local description: ${e.message}")
            }
        }
    }

    fun createAnswer() {
        scope.launch {
            updateRole("Answerer")
            val peerState = ensureBootstrapPeer()
            if (peerState.connection.remoteDescription == null) {
                appendLog("Set the remote offer before creating an answer.")
                return@launch
            }

            val answer = try {
                peerState.connection.createAnswerAsync()
            } catch (e: Exception) {
                appendLog("Failed to create answer: ${e.message}")
                return@launch
            }
            
            try {
                peerState.connection.setLocalDescriptionAsync(answer)
                updateState { it.copy(localDescription = answer.toPrettyJson()) }
                appendLog("Local answer created. Send it back to the offerer.")
            } catch (e: Exception) {
                appendLog("Failed to set local description: ${e.message}")
            }
        }
    }

    fun setRemoteDescription(text: String) {
        scope.launch {
            val peerState = ensureBootstrapPeer()
            val description = runCatching { parseDescription(text) }
                .getOrElse {
                    appendLog("Failed to parse remote description: ${it.message}")
                    return@launch
                }

            try {
                peerState.connection.setRemoteDescriptionAsync(description)
                appendLog("Applied remote ${description.type.canonicalName()} description.")
                flushPendingRemoteCandidates(peerState)
            } catch (e: Exception) {
                appendLog("Failed to set remote description: ${e.message}")
            }
        }
    }

    fun addRemoteCandidates(text: String) {
        scope.launch {
            val peerState = ensureBootstrapPeer()
            val candidates = runCatching { parseCandidates(text) }
                .getOrElse {
                    appendLog("Failed to parse remote candidates: ${it.message}")
                    return@launch
                }

            if (candidates.isEmpty()) {
                appendLog("No remote candidates were provided.")
                return@launch
            }

            if (peerState.connection.remoteDescription == null) {
                peerState.pendingRemoteCandidates += candidates
                appendLog("Queued ${candidates.size} remote candidate(s) until the remote description is set.")
                return@launch
            }

            candidates.forEach { candidate ->
                peerState.connection.addIceCandidateAsync(candidate)
            }
            appendLog("Added ${candidates.size} remote candidate(s).")
        }
    }

    fun sendMessage(text: String) {
        scope.launch {
            val openPeers = mutableListOf<PeerState>()
            peerStates.values.forEach { if (it.dataChannel?.state() == DataChannel.State.OPEN) openPeers.add(it) }
            pendingPeer?.let { if (it.dataChannel?.state() == DataChannel.State.OPEN) openPeers.add(it) }

            if (openPeers.isEmpty()) {
                appendLog("No data channels are open yet.")
                return@launch
            }

            val payload = JSONObject().apply {
                put("type", "chat")
                put("from", localPeerId)
                put("text", text)
                put("timestamp", nowLabel())
            }.toString()

            val bytes = payload.toByteArray(Charsets.UTF_8)
            openPeers.forEach { peer ->
                peer.dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            }
            updateState { state ->
                state.copy(messages = state.messages + ChatMessage(text = text, mine = true, timestamp = nowLabel(), fromPeerId = localPeerId))
            }
        }
    }

    fun reset() {
        peerStates.values.forEach { peer ->
            peer.dataChannel?.close()
            peer.connection.close()
        }
        pendingPeer?.dataChannel?.close()
        pendingPeer?.connection?.close()
        peerStates.clear()
        pendingPeer = null
        relayPeerId = null
        updateState {
            it.copy(
                connectionState = "Disconnected",
                iceGatheringState = "New",
                dataChannelState = "Closed",
                isDataChannelOpen = false,
                role = "Idle",
                connectedPeers = emptyList(),
                relayPeerId = null,
                localDescription = "",
                localCandidates = "",
                messages = emptyList(),
                logLines = it.logLines + "${nowLabel()} Peer connection reset."
            )
        }
        appendLog("Peer connection reset.")
    }

    override fun close() {
        peerStates.values.forEach { peer ->
            peer.dataChannel?.close()
            peer.connection.close()
        }
        pendingPeer?.dataChannel?.close()
        pendingPeer?.connection?.close()
        peerStates.clear()
        pendingPeer = null
        scope.cancel()
        peerConnectionFactory.dispose()
    }

    private fun ensureBootstrapPeer(): PeerState {
        val existing = pendingPeer
        if (existing != null) {
            return existing
        }

        val peerState = createPeerState(peerId = null, isBootstrap = true)
        pendingPeer = peerState
        updateAggregateState()
        appendLog("Peer connection created.")
        return peerState
    }

    private fun createPeerState(peerId: String?, isBootstrap: Boolean): PeerState {
        lateinit var peerState: PeerState
        val connection = requireNotNull(
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        peerState.iceConnectionState = newState
                        updateAggregateState()
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                        peerState.iceGatheringState = newState
                        updateAggregateState()
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        if (candidate != null) {
                            handleLocalCandidate(peerState, candidate)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onDataChannel(channel: DataChannel?) {
                        if (channel != null) {
                            attachDataChannel(peerState, channel)
                        }
                    }

                    override fun onRenegotiationNeeded() = Unit

                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit

                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
                }
            )
        )

        peerState = PeerState(peerId = peerId, connection = connection, isBootstrap = isBootstrap)
        return peerState
    }

    private fun ensureLocalDataChannel(peerState: PeerState) {
        if (peerState.dataChannel != null) {
            return
        }

        val channel = peerState.connection.createDataChannel("chat", DataChannel.Init())
        attachDataChannel(peerState, channel)
        appendLog("Local data channel created.")
    }

    private fun attachDataChannel(peerState: PeerState, channel: DataChannel) {
        peerState.dataChannelObserver?.let { peerState.dataChannel?.unregisterObserver() }
        peerState.dataChannel = channel
        peerState.dataChannelObserver = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    sendHello(peerState)
                }
                updateAggregateState()
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    return
                }

                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                handleIncomingMessage(peerState, text)
            }
        }
        channel.registerObserver(peerState.dataChannelObserver)
        if (channel.state() == DataChannel.State.OPEN) {
            sendHello(peerState)
        }
        updateAggregateState()
        appendLog("Data channel attached.")
    }

    private suspend fun flushPendingRemoteCandidates(peerState: PeerState) {
        if (peerState.pendingRemoteCandidates.isEmpty()) {
            return
        }

        val queued = peerState.pendingRemoteCandidates.toList()
        peerState.pendingRemoteCandidates.clear()
        queued.forEach { candidate ->
            peerState.connection.addIceCandidateAsync(candidate)
        }
        appendLog("Flushed ${queued.size} queued remote candidate(s).")
    }

    private fun handleLocalCandidate(peerState: PeerState, candidate: IceCandidate) {
        val peerId = peerState.peerId
        if (peerId == null) {
            appendLocalCandidate(candidate)
            return
        }

        val signal = JSONObject().apply {
            put("type", "candidate")
            put("candidate", serializeCandidate(candidate))
        }
        sendSignal(peerId, signal)
    }

    private fun handleIncomingMessage(peerState: PeerState, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null || !json.has("type")) {
            val from = peerState.peerId
            updateState { state ->
                state.copy(messages = state.messages + ChatMessage(text = text, mine = false, timestamp = nowLabel(), fromPeerId = from))
            }
            return
        }

        when (json.optString("type")) {
            "hello" -> handleHello(peerState, json)
            "peer-list" -> handlePeerList(json)
            "signal" -> handleSignalMessage(json)
            "chat" -> handleChat(json, peerState.peerId)
        }
    }

    private fun handleChat(json: JSONObject, fallbackPeerId: String?) {
        val text = json.optString("text")
        if (text.isBlank()) {
            return
        }
        val from = json.optString("from").takeIf { it.isNotBlank() } ?: fallbackPeerId
        updateState { state ->
            state.copy(messages = state.messages + ChatMessage(text = text, mine = false, timestamp = nowLabel(), fromPeerId = from))
        }
    }

    private fun sendHello(peerState: PeerState) {
        val message = JSONObject().apply {
            put("type", "hello")
            put("peerId", localPeerId)
            put("knownPeers", JSONArray(peerStates.keys.toList()))
        }
        sendJson(peerState, message)
    }

    private fun handleHello(peerState: PeerState, json: JSONObject) {
        val remoteId = json.optString("peerId").takeIf { it.isNotBlank() } ?: return
        assignPeerId(peerState, remoteId)
        if (relayPeerId == null) {
            relayPeerId = remoteId
            appendLog("Using $remoteId as the signaling relay.")
        }
        sendPeerList(remoteId)
        updateAggregateState()
    }

    private fun assignPeerId(peerState: PeerState, peerId: String) {
        if (peerState.peerId == peerId) {
            return
        }

        if (peerStates.containsKey(peerId)) {
            appendLog("Peer $peerId is already connected.")
            return
        }

        peerState.peerId = peerId
        peerStates[peerId] = peerState
        if (peerState.isBootstrap) {
            pendingPeer = null
        }
        updateAggregateState()
    }

    private fun sendPeerList(peerId: String) {
        val peers = peerStates.keys.filter { it != peerId && it != localPeerId }
        if (peers.isEmpty()) {
            return
        }
        val message = JSONObject().apply {
            put("type", "peer-list")
            put("peers", JSONArray(peers))
        }
        sendToPeer(peerId, message)
    }

    private fun handlePeerList(json: JSONObject) {
        val peers = json.optJSONArray("peers") ?: return
        for (index in 0 until peers.length()) {
            val peerId = peers.optString(index)
            if (peerId.isBlank() || peerId == localPeerId || peerStates.containsKey(peerId)) {
                continue
            }
            scope.launch {
                startConnectionToPeer(peerId)
            }
        }
    }

    private suspend fun startConnectionToPeer(peerId: String) {
        if (peerStates.containsKey(peerId)) {
            return
        }

        val peerState = createPeerState(peerId = peerId, isBootstrap = false)
        peerStates[peerId] = peerState
        ensureLocalDataChannel(peerState)
        updateAggregateState()

        val offer = try {
            peerState.connection.createOfferAsync()
        } catch (e: Exception) {
            appendLog("Failed to create offer for $peerId: ${e.message}")
            return
        }
        
        try {
            peerState.connection.setLocalDescriptionAsync(offer)
            val signal = JSONObject().apply {
                put("type", "offer")
                put("sdp", offer.description)
            }
            sendSignal(peerId, signal)
            appendLog("Sent offer to peer $peerId.")
        } catch (e: Exception) {
            appendLog("Failed to set local description for $peerId: ${e.message}")
        }
    }

    private fun handleSignalMessage(json: JSONObject) {
        val to = json.optString("to")
        if (to.isBlank()) {
            return
        }

        if (to != localPeerId) {
            relaySignal(json)
            return
        }

        val from = json.optString("from").takeIf { it.isNotBlank() } ?: return
        val signal = json.optJSONObject("signal") ?: return
        when (signal.optString("type")) {
            "offer" -> scope.launch { handleOffer(from, signal) }
            "answer" -> scope.launch { handleAnswer(from, signal) }
            "candidate" -> scope.launch { handleCandidate(from, signal) }
        }
    }

    private fun relaySignal(message: JSONObject) {
        val target = message.optString("to")
        if (target.isBlank()) {
            return
        }
        if (!sendToPeer(target, message)) {
            appendLog("Unable to relay signal to $target.")
        }
    }

    private suspend fun handleOffer(from: String, signal: JSONObject) {
        val sdp = signal.optString("sdp").takeIf { it.isNotBlank() } ?: return
        val peerState = peerStates[from] ?: createPeerState(peerId = from, isBootstrap = false).also {
            peerStates[from] = it
        }
        updateAggregateState()

        try {
            peerState.connection.setRemoteDescriptionAsync(SessionDescription(SessionDescription.Type.OFFER, sdp))
            val answer = peerState.connection.createAnswerAsync()
            peerState.connection.setLocalDescriptionAsync(answer)
            val response = JSONObject().apply {
                put("type", "answer")
                put("sdp", answer.description)
            }
            sendSignal(from, response)
            flushPendingRemoteCandidates(peerState)
        } catch (e: Exception) {
            appendLog("Failed to process offer from $from: ${e.message}")
        }
    }

    private suspend fun handleAnswer(from: String, signal: JSONObject) {
        val sdp = signal.optString("sdp").takeIf { it.isNotBlank() } ?: return
        val peerState = peerStates[from] ?: return
        try {
            peerState.connection.setRemoteDescriptionAsync(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            flushPendingRemoteCandidates(peerState)
        } catch (e: Exception) {
            appendLog("Failed to process answer from $from: ${e.message}")
        }
    }

    private suspend fun handleCandidate(from: String, signal: JSONObject) {
        val candidateJson = signal.optJSONObject("candidate") ?: return
        val candidate = try { parseCandidateObject(candidateJson) } catch (e: Exception) { return }
        val peerState = peerStates[from] ?: createPeerState(peerId = from, isBootstrap = false).also {
            peerStates[from] = it
        }
        updateAggregateState()

        if (peerState.connection.remoteDescription == null) {
            peerState.pendingRemoteCandidates += candidate
            return
        }
        peerState.connection.addIceCandidateAsync(candidate)
    }

    private fun sendSignal(targetPeerId: String, signal: JSONObject) {
        val message = JSONObject().apply {
            put("type", "signal")
            put("from", localPeerId)
            put("to", targetPeerId)
            put("signal", signal)
        }

        if (sendToPeer(targetPeerId, message)) {
            return
        }

        val relay = relayPeerId
        if (relay != null && relay != targetPeerId && sendToPeer(relay, message)) {
            return
        }
        appendLog("No route to peer $targetPeerId.")
    }

    private fun sendToPeer(peerId: String, message: JSONObject): Boolean {
        val peer = peerStates[peerId] ?: return false
        val channel = peer.dataChannel ?: return false
        if (channel.state() != DataChannel.State.OPEN) {
            return false
        }
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        return true
    }

    private fun sendJson(peerState: PeerState, message: JSONObject) {
        val channel = peerState.dataChannel ?: return
        if (channel.state() != DataChannel.State.OPEN) {
            return
        }
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun updateAggregateState() {
        val peers = peerStates.keys.sorted()
        val openChannels = peerStates.values.count { it.dataChannel?.state() == DataChannel.State.OPEN }
        val pendingOpen = if (pendingPeer?.dataChannel?.state() == DataChannel.State.OPEN) 1 else 0
        val totalOpen = openChannels + pendingOpen
        
        val connectionState = when {
            peers.isNotEmpty() -> "Connected (${peers.size})"
            pendingPeer != null -> "Connecting"
            else -> "Disconnected"
        }
        val dataChannelState = if (totalOpen > 0) "Open ($totalOpen)" else "Closed"
        
        val allStates = peerStates.values.toMutableList()
        pendingPeer?.let { allStates.add(it) }
        
        val gatheringState = when {
            allStates.any { it.iceGatheringState == PeerConnection.IceGatheringState.GATHERING } -> "Gathering"
            allStates.any { it.iceGatheringState == PeerConnection.IceGatheringState.COMPLETE } -> "Complete"
            else -> "New"
        }

        updateState {
            it.copy(
                connectionState = connectionState,
                dataChannelState = dataChannelState,
                isDataChannelOpen = totalOpen > 0,
                iceGatheringState = gatheringState,
                connectedPeers = peers,
                relayPeerId = relayPeerId
            )
        }
    }

    private fun serializeCandidate(candidate: IceCandidate): JSONObject {
        return JSONObject().apply {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }
    }

    private fun parseCandidateObject(json: JSONObject): IceCandidate {
        val candidate = when {
            json.has("candidate") -> json.getString("candidate")
            json.has("sdp") -> json.getString("sdp")
            else -> throw IllegalArgumentException("Missing candidate value")
        }
        val sdpMid = json.optString("sdpMid").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing sdpMid")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
        if (sdpMLineIndex < 0) {
            throw IllegalArgumentException("Missing sdpMLineIndex")
        }
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    private fun appendLocalCandidate(candidate: IceCandidate) {
        val json = serializeCandidate(candidate).toString()

        updateState { state ->
            val nextCandidates = if (state.localCandidates.isBlank()) json else state.localCandidates + "\n" + json
            state.copy(localCandidates = nextCandidates)
        }
        appendLog("Generated a local ICE candidate.")
    }

    private fun parseDescription(text: String): SessionDescription {
        val json = JSONObject(text.trim())
        val type = json.optString("type").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing type")
        val sdp = json.optString("sdp").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing sdp")

        return SessionDescription(parseSessionType(type), sdp)
    }

    private fun parseCandidates(text: String): List<IceCandidate> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                val json = JSONObject(line)
                val candidate = when {
                    json.has("candidate") -> json.getString("candidate")
                    json.has("sdp") -> json.getString("sdp")
                    else -> throw IllegalArgumentException("Missing candidate value")
                }
                val sdpMid = json.optString("sdpMid").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing sdpMid")
                val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
                if (sdpMLineIndex < 0) {
                    throw IllegalArgumentException("Missing sdpMLineIndex")
                }
                IceCandidate(sdpMid, sdpMLineIndex, candidate)
            }
            .toList()
    }

    private suspend fun PeerConnection.createOfferAsync(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                if (continuation.isActive) continuation.resume(desc)
            }

            override fun onCreateFailure(error: String) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(RuntimeException(error)))
            }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.createAnswerAsync(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                if (continuation.isActive) continuation.resume(desc)
            }

            override fun onCreateFailure(error: String) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(RuntimeException(error)))
            }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocalDescriptionAsync(description: SessionDescription) = suspendCancellableCoroutine<Unit> { continuation ->
        setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(RuntimeException(error)))
            }
        }, description)
    }

    private suspend fun PeerConnection.setRemoteDescriptionAsync(description: SessionDescription) = suspendCancellableCoroutine<Unit> { continuation ->
        setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(RuntimeException(error)))
            }
        }, description)
    }

    private suspend fun PeerConnection.addIceCandidateAsync(candidate: IceCandidate) = suspendCancellableCoroutine<Unit> { continuation ->
        addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onAddFailure(error: String?) {
                appendLog("ICE Candidate ignored: $error")
                if (continuation.isActive) continuation.resume(Unit)
            }
        })
    }

    private fun updateRole(role: String) {
        updateState { it.copy(role = role) }
    }

    private fun updateState(reducer: (WebRtcChatUiState) -> WebRtcChatUiState) {
        _state.update(reducer)
    }

    private fun appendLog(message: String) {
        updateState { state ->
            state.copy(logLines = state.logLines + "${nowLabel()} $message")
        }
    }

    private fun SessionDescription.toPrettyJson(): String {
        return JSONObject().apply {
            put("type", type.canonicalName())
            put("sdp", description)
        }.toString(2)
    }

    private fun PeerConnection.IceConnectionState.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun PeerConnection.IceGatheringState.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun DataChannel.State.displayName(): String = name.lowercase().replaceFirstChar { it.uppercase() }

    private fun SessionDescription.Type.canonicalName(): String = when (this) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }

    private fun parseSessionType(value: String): SessionDescription.Type = when (value.lowercase(Locale.US)) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> throw IllegalArgumentException("Unsupported session type: $value")
    }

    private fun generatePeerId(): String {
        return UUID.randomUUID().toString().take(8)
    }
}

private abstract class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) = Unit

    override fun onSetSuccess() = Unit

    override fun onCreateFailure(error: String) = Unit

    override fun onSetFailure(error: String) = Unit
}

internal fun nowLabel(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
