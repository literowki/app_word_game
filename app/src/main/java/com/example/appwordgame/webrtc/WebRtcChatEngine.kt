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
import kotlin.coroutines.resume


data class ChatMessage(
    val text: String,
    val mine: Boolean,
    val timestamp: String
)

data class WebRtcChatUiState(
    val connectionState: String = "Disconnected",
    val iceGatheringState: String = "New",
    val dataChannelState: String = "Closed",
    val role: String = "Idle",
    val localDescription: String = "",
    val localCandidates: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val logLines: List<String> = listOf("${nowLabel()} Ready. Create an offer on one side and an answer on the other.")
)

class WebRtcChatEngine(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    private val peerConnectionFactory: PeerConnectionFactory

    private val rtcConfig = PeerConnection.RTCConfiguration(
        arrayListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    ).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }

    private val _state = MutableStateFlow(WebRtcChatUiState())
    val state: StateFlow<WebRtcChatUiState> = _state.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var dataChannelObserver: DataChannel.Observer? = null

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
            val connection = ensurePeerConnection()
            ensureLocalDataChannel(connection)

            val offer = connection.createOfferAsync()
            connection.setLocalDescriptionAsync(offer)
            updateState { it.copy(localDescription = offer.toPrettyJson()) }
            appendLog("Local offer created. Copy it to the remote peer.")
        }
    }

    fun createAnswer() {
        scope.launch {
            updateRole("Answerer")
            val connection = ensurePeerConnection()
            if (connection.remoteDescription == null) {
                appendLog("Set the remote offer before creating an answer.")
                return@launch
            }

            val answer = connection.createAnswerAsync()
            connection.setLocalDescriptionAsync(answer)
            updateState { it.copy(localDescription = answer.toPrettyJson()) }
            appendLog("Local answer created. Send it back to the offerer.")
        }
    }

    fun setRemoteDescription(text: String) {
        scope.launch {
            val connection = ensurePeerConnection()
            val description = runCatching { parseDescription(text) }
                .getOrElse {
                    appendLog("Failed to parse remote description: ${it.message}")
                    return@launch
                }

            connection.setRemoteDescriptionAsync(description)
            appendLog("Applied remote ${description.type.canonicalName()} description.")
            flushPendingRemoteCandidates()
        }
    }

    fun addRemoteCandidates(text: String) {
        scope.launch {
            val connection = ensurePeerConnection()
            val candidates = runCatching { parseCandidates(text) }
                .getOrElse {
                    appendLog("Failed to parse remote candidates: ${it.message}")
                    return@launch
                }

            if (candidates.isEmpty()) {
                appendLog("No remote candidates were provided.")
                return@launch
            }

            if (connection.remoteDescription == null) {
                pendingRemoteCandidates += candidates
                appendLog("Queued ${candidates.size} remote candidate(s) until the remote description is set.")
                return@launch
            }

            candidates.forEach { candidate ->
                connection.addIceCandidateAsync(candidate)
            }
            appendLog("Added ${candidates.size} remote candidate(s).")
        }
    }

    fun sendMessage(text: String) {
        scope.launch {
            val channel = dataChannel
            if (channel == null || channel.state() != DataChannel.State.OPEN) {
                appendLog("Data channel is not open yet.")
                return@launch
            }

            val bytes = text.toByteArray(Charsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
            updateState { state ->
                state.copy(messages = state.messages + ChatMessage(text = text, mine = true, timestamp = nowLabel()))
            }
        }
    }

    fun reset() {
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
        dataChannelObserver = null
        pendingRemoteCandidates.clear()
        updateState {
            it.copy(
                connectionState = "Disconnected",
                iceGatheringState = "New",
                dataChannelState = "Closed",
                role = "Idle",
                localDescription = "",
                localCandidates = "",
                messages = emptyList(),
                logLines = it.logLines + "${nowLabel()} Peer connection reset."
            )
        }
        appendLog("Peer connection reset.")
    }

    override fun close() {
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
        dataChannelObserver = null
        pendingRemoteCandidates.clear()
        scope.cancel()
        peerConnectionFactory.dispose()
    }

    private fun ensurePeerConnection(): PeerConnection {
        val existing = peerConnection
        if (existing != null) {
            return existing
        }

        val connection = requireNotNull(
            peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                        updateState { it.copy(connectionState = newState.displayName()) }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                        updateState { it.copy(iceGatheringState = newState.displayName()) }
                    }

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        if (candidate != null) {
                            appendLocalCandidate(candidate)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit

                    override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit

                    override fun onDataChannel(channel: DataChannel?) {
                        if (channel != null) {
                            attachDataChannel(channel)
                        }
                    }

                    override fun onRenegotiationNeeded() = Unit

                    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit

                    override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) = Unit
                }
            )
        )

        peerConnection = connection
        updateState { it.copy(connectionState = "Connecting") }
        appendLog("Peer connection created.")
        return connection
    }

    private fun ensureLocalDataChannel(connection: PeerConnection) {
        if (dataChannel != null) {
            return
        }

        val channel = connection.createDataChannel("chat", DataChannel.Init())
        attachDataChannel(channel)
        appendLog("Local data channel created.")
    }

    private fun attachDataChannel(channel: DataChannel) {
        dataChannel?.unregisterObserver()
        dataChannel = channel
        dataChannelObserver = object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                updateState { it.copy(dataChannelState = channel.state().displayName()) }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    return
                }

                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                updateState { state ->
                    state.copy(messages = state.messages + ChatMessage(text = text, mine = false, timestamp = nowLabel()))
                }
            }
        }
        channel.registerObserver(dataChannelObserver)
        updateState { it.copy(dataChannelState = channel.state().displayName()) }
        appendLog("Data channel attached.")
    }

    private suspend fun flushPendingRemoteCandidates() {
        val connection = peerConnection ?: return
        if (pendingRemoteCandidates.isEmpty()) {
            return
        }

        val queued = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
        queued.forEach { candidate ->
            connection.addIceCandidateAsync(candidate)
        }
        appendLog("Flushed ${queued.size} queued remote candidate(s).")
    }

    private fun appendLocalCandidate(candidate: IceCandidate) {
        val json = JSONObject().apply {
            put("candidate", candidate.sdp)
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
        }.toString()

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
                val candidate = json.optString("candidate").takeIf { it.isNotBlank() }
                    ?: json.optString("sdp").takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("Missing candidate value")
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
                continuation.resume(desc)
            }

            override fun onCreateFailure(error: String) {
                continuation.cancel(RuntimeException(error))
            }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.createAnswerAsync(): SessionDescription = suspendCancellableCoroutine { continuation ->
        createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                continuation.resume(desc)
            }

            override fun onCreateFailure(error: String) {
                continuation.cancel(RuntimeException(error))
            }
        }, MediaConstraints())
    }

    private suspend fun PeerConnection.setLocalDescriptionAsync(description: SessionDescription) = suspendCancellableCoroutine<Unit> { continuation ->
        setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                continuation.cancel(RuntimeException(error))
            }
        }, description)
    }

    private suspend fun PeerConnection.setRemoteDescriptionAsync(description: SessionDescription) = suspendCancellableCoroutine<Unit> { continuation ->
        setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                continuation.resume(Unit)
            }

            override fun onSetFailure(error: String) {
                continuation.cancel(RuntimeException(error))
            }
        }, description)
    }

    private suspend fun PeerConnection.addIceCandidateAsync(candidate: IceCandidate) = suspendCancellableCoroutine<Unit> { continuation ->
        addIceCandidate(candidate, object : org.webrtc.AddIceObserver {
            override fun onAddSuccess() {
                continuation.resume(Unit)
            }

            override fun onAddFailure(error: String?) {
                continuation.cancel(RuntimeException(error ?: "Unknown ICE error"))
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
