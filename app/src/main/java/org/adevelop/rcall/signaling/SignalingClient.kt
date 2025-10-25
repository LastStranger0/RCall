package org.adevelop.rcall.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SignalingClient(
    private val baseWs: String,
    parentScope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectedToSignaling()
        fun onPeerJoined(peerId: String)
        fun onOffer(from: String, sdp: String)
        fun onAnswer(from: String, sdp: String)
        fun onIce(from: String, candidate: IceCandidate)
        fun onClosed(reason: String)
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentRoomId: String? = null
    private val peerId: String = UUID.randomUUID().toString()

    fun getPeerId(): String = peerId

    private val isConnecting = AtomicBoolean(false)
    private var reconnectionJob: Job? = null

    fun connect(roomId: String) {
        if (!isConnecting.compareAndSet(false, true)) {
            Log.w("SignalingClient", "Connection attempt already in progress for room: $roomId")
            return
        }
        webSocket?.close(1000, "Changing room")
        webSocket = null
        this.currentRoomId = roomId

        val url = "$baseWs?room=$roomId&peer=$peerId"
        val request = Request.Builder().url(url).build()

        Log.d("SignalingClient", "Connecting to URL: $url")
        client.newWebSocket(request, SignalingWebSocketListener())
    }

    private inner class SignalingWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@SignalingClient.webSocket = webSocket
            isConnecting.set(false)
            reconnectionJob?.cancel()
            Log.i("SignalingClient", "WebSocket connection opened to room: $currentRoomId")
            listener.onConnectedToSignaling()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                val envelope = runCatching { json.decodeFromString<Envelope>(text) }.getOrNull() ?: return@launch
                if (envelope.from == peerId) return@launch

                Log.d("SignalingClient", "Received message: ${envelope.type} from ${envelope.from}")
                val fromId = envelope.from ?: return@launch

                when (envelope.type) {
                    "peer-joined" -> envelope.peerId?.let { listener.onPeerJoined(it) }
                    "offer" -> envelope.sdp?.let { listener.onOffer(fromId, it) }
                    "answer" -> envelope.sdp?.let { listener.onAnswer(fromId, it) }
                    "ice" -> {
                        val c = envelope.candidate ?: return@launch
                        val iceCandidate = IceCandidate(
                            c.sdpMid,
                            c.sdpMLineIndex ?: -1,
                            c.candidate
                        )
                        listener.onIce(fromId, iceCandidate)
                    }
                    "error" -> Log.e("SignalingClient", "Server error: ${envelope.payload}")
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w("SignalingClient", "WebSocket closing: $code / $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@SignalingClient.webSocket = null
            isConnecting.set(false)
            Log.w("SignalingClient", "WebSocket closed: $code / $reason")
            listener.onClosed("Connection closed: $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@SignalingClient.webSocket = null
            isConnecting.set(false)
            Log.e("SignalingClient", "WebSocket failure", t)
            if (reconnectionJob?.isActive != true) {
                reconnect()
            }
        }
    }

    private fun reconnect() {
        val roomToReconnect = currentRoomId ?: return
        reconnectionJob = scope.launch {
            Log.d("SignalingClient", "Scheduling reconnect in 3 seconds...")
            delay(3000)
            if (webSocket == null) {
                connect(roomToReconnect)
            }
        }
    }

    fun sendOffer(sdp: SessionDescription) = send(Envelope(type = "offer", sdp = sdp.description))
    fun sendAnswer(sdp: SessionDescription) = send(Envelope(type = "answer", sdp = sdp.description))
    fun sendIce(candidate: IceCandidate) {
        val cand = Candidate(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp
        )
        send(Envelope(type = "ice", candidate = cand))
    }

    fun leave() {
        scope.coroutineContext.cancelChildren()
        webSocket?.close(1000, "User left")
        webSocket = null
        currentRoomId = null
    }

    private fun send(data: Envelope) {
        if (webSocket == null) {
            Log.w("SignalingClient", "Cannot send message, WebSocket is not active.")
            return
        }
        scope.launch {
            val messageToSend = data.copy(room = currentRoomId, from = peerId)
            val message = json.encodeToString(messageToSend)
            webSocket?.send(message)
        }
    }
}


@Serializable
data class Envelope(
    val type: String,
    val room: String? = null,
    val peerId: String? = null,
    val from: String? = null,
    val sdp: String? = null,
    val candidate: Candidate? = null,
    val payload: String? = null
)

@Serializable
data class Candidate(
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null
)
