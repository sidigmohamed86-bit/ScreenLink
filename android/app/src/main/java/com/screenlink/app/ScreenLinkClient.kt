package com.screenlink.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*

object ScreenLinkClient {
    const val CAPTURE_REQUEST = 7001
    private const val SERVER_URL = "https://screenlink-kmqd.onrender.com"

    private lateinit var app: Context
    private var socket: Socket? = null
    private var room: String? = null
    private var callback: ((String, String?) -> Unit)? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var sender: RtpSender? = null
    private var eglBase: EglBase? = null
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var sharing = false

    fun init(context: Context, cb: (String, String?) -> Unit) {
        app = context.applicationContext
        callback = cb

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )

        eglBase = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        socket = IO.socket(SERVER_URL)

        socket!!.on(Socket.EVENT_CONNECT) {
            callback?.invoke("Online", room)
        }

        socket!!.on("room-created") { a ->
            room = a[0].toString()
            callback?.invoke("Room created", room)
        }

        socket!!.on("peer-joined") {
            createPeer()
            callback?.invoke("Computer connected", room)
        }

        socket!!.on("answer") { a ->
            val j = a[0] as JSONObject
            peer?.setRemoteDescription(
                object : Obs() {
                    override fun onSetSuccess() {
                        remoteDescriptionSet = true
                        pendingIceCandidates.forEach { peer?.addIceCandidate(it) }
                        pendingIceCandidates.clear()
                    }

                    override fun onSetFailure(error: String) {
                        callback?.invoke("Connection setup failed: $error", room)
                    }
                },
                SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(j.getString("type")),
                    j.getString("sdp")
                )
            )
        }

        socket!!.on("viewer-disconnected") {
            peer?.close()
            peer = null
            remoteDescriptionSet = false
            pendingIceCandidates.clear()
            callback?.invoke("Computer disconnected", room)
        }

        socket!!.on("ice-candidate") { a ->
            val j = a[0] as JSONObject
            val c = IceCandidate(
                j.optString("sdpMid", null),
                j.optInt("sdpMLineIndex"),
                j.getString("candidate")
            )
            if (remoteDescriptionSet) {
                peer?.addIceCandidate(c)
            } else {
                pendingIceCandidates.add(c)
            }
        }

        socket!!.connect()
    }

    fun createRoom() {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        room = (1..6).map { chars.random() }.joinToString("")
        socket?.emit("create-room", JSONObject().put("room", room))
    }

    fun canStartSharing(): Boolean {
        return room != null && peer != null
    }

    fun createCaptureIntent(): Intent {
        val m = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= 34) {
            m.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForUserChoice()
            )
        } else {
            m.createScreenCaptureIntent()
        }
    }

    fun startSharing(resultCode: Int, data: Intent) {
        if (peer == null || room == null) {
            callback?.invoke("Wait for the computer to join", room)
            return
        }

        try {
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCodeFromService: Int, resultData: Bundle?) {
                    if (resultCodeFromService == ScreenShareService.RESULT_READY) {
                        beginCapture(resultCode, data)
                    } else {
                        callback?.invoke(
                            "Screen share failed: " +
                                (resultData?.getString("error") ?: "foreground service failed"),
                            room
                        )
                    }
                }
            }

            val serviceIntent = Intent(app, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                putExtra(ScreenShareService.EXTRA_RESULT_RECEIVER, receiver)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }
        } catch (e: Exception) {
            callback?.invoke("Screen share failed: " + (e.message ?: e.javaClass.simpleName), room)
        }
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        if (sharing) return
        try {
            helper = SurfaceTextureHelper.create(
                "ScreenLink",
                eglBase!!.eglBaseContext
            )

            source = factory!!.createVideoSource(true)

            capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        callback?.invoke("Screen capture stopped", room)
                        stopSharing()
                    }
                }
            )

            capturer!!.initialize(
                helper,
                app,
                source!!.capturerObserver
            )

            val d = app.resources.displayMetrics
            val scale = minOf(
                1920f / maxOf(d.widthPixels, d.heightPixels),
                1080f / minOf(d.widthPixels, d.heightPixels),
                1f
            )
            var width = (d.widthPixels * scale).toInt().coerceAtLeast(2)
            var height = (d.heightPixels * scale).toInt().coerceAtLeast(2)
            if (width % 2 != 0) width--
            if (height % 2 != 0) height--
            capturer!!.startCapture(width, height, 60)
            sharing = true

            track = factory!!.createVideoTrack("screen", source)
            if (sender == null) {
                sender = peer!!.addTrack(track, listOf("screen"))
            } else {
                sender!!.setTrack(track, true)
            }

            peer!!.createOffer(object : Obs() {
                override fun onCreateSuccess(s: SessionDescription) {
                    peer?.setLocalDescription(object : Obs() {
                        override fun onSetSuccess() {
                            val local = peer?.localDescription ?: return
                            val offer = JSONObject()
                                .put("type", local.type.canonicalForm())
                                .put("sdp", local.description)

                            socket?.emit(
                                "offer",
                                JSONObject()
                                    .put("room", room)
                                    .put("offer", offer)
                            )

                            callback?.invoke("Screen sharing", room)
                        }

                        override fun onSetFailure(error: String) {
                            callback?.invoke("Connection setup failed: $error", room)
                        }
                    }, s)
                }

                override fun onCreateFailure(error: String) {
                    callback?.invoke("Connection setup failed: $error", room)
                }
            }, MediaConstraints())

        } catch (e: Exception) {
            callback?.invoke("Screen share failed: " + e.message, room)
        }
    }

    private fun createPeer() {
        if (peer != null) return

        val config = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder(
                    "stun:stun.l.google.com:19302"
                ).createIceServer(),

                PeerConnection.IceServer.builder(
                    "stun:stun.cloudflare.com:3478"
                ).createIceServer()
            )
        )

        peer = factory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(c: IceCandidate) {
                    val j = JSONObject()
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                        .put("candidate", c.sdp)

                    socket?.emit(
                        "ice-candidate",
                        JSONObject()
                            .put("room", room)
                            .put("candidate", j)
                    )
                }

                override fun onConnectionChange(
                    s: PeerConnection.PeerConnectionState
                ) {
                    callback?.invoke(s.name, room)
                }

                override fun onSignalingChange(
                    s: PeerConnection.SignalingState
                ) {}

                override fun onIceConnectionChange(
                    s: PeerConnection.IceConnectionState
                ) {}

                override fun onIceConnectionReceivingChange(b: Boolean) {}

                override fun onIceGatheringChange(
                    s: PeerConnection.IceGatheringState
                ) {}

                override fun onIceCandidatesRemoved(
                    c: Array<IceCandidate>
                ) {}

                override fun onAddStream(s: MediaStream) {}

                override fun onRemoveStream(s: MediaStream) {}

                override fun onDataChannel(d: DataChannel) {}

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(
                    r: RtpReceiver,
                    s: Array<MediaStream>
                ) {}
            }
        )
    }

    fun stopSharing() {
        socket?.emit("stop-share", JSONObject().put("room", room))

        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        capturer?.dispose()
        capturer = null

        sender?.setTrack(null, false)

        track?.dispose()
        track = null

        source?.dispose()
        source = null

        helper?.dispose()
        helper = null
        sharing = false
        pendingIceCandidates.clear()

        app.stopService(
            Intent(app, ScreenShareService::class.java)
        )
    }

    fun shutdown() {
        stopSharing()

        peer?.close()
        peer = null

        socket?.disconnect()
        socket = null
        eglBase?.release()
        eglBase = null
    }

    private open class Obs : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String) {}
        override fun onSetFailure(s: String) {}
    }
}