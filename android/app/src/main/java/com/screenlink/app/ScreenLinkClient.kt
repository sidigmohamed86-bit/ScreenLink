package com.screenlink.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
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

    fun init(context: Context, cb: (String, String?) -> Unit) {
        app = context.applicationContext
        callback = cb

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions()
        )

        val egl = EglBase.create()
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
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
                Obs(),
                SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(j.getString("type")),
                    j.getString("sdp")
                )
            )
        }

        socket!!.on("ice-candidate") { a ->
            val j = a[0] as JSONObject
            peer?.addIceCandidate(
                IceCandidate(
                    j.optString("sdpMid", null),
                    j.optInt("sdpMLineIndex"),
                    j.getString("candidate")
                )
            )
        }

        socket!!.connect()
    }

    fun createRoom() {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        room = (1..6).map { chars.random() }.joinToString("")
        socket?.emit("create-room", JSONObject().put("room", room))
    }

    fun createCaptureIntent(): Intent {
        val m = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return m.createScreenCaptureIntent()
    }

    fun startSharing(resultCode: Int, data: Intent) {
        if (peer == null || room == null) {
            callback?.invoke("Wait for the computer to join", room)
            return
        }

        try {
            // Android 14+ requires the mediaProjection foreground service
            // to be running BEFORE MediaProjection is obtained by WebRTC.
            ScreenShareService.onReady = {
                beginCapture(resultCode, data)
            }

            app.startForegroundService(
                Intent(app, ScreenShareService::class.java)
            )
        } catch (e: Exception) {
            ScreenShareService.onReady = null
            callback?.invoke("Screen share failed: " + e.message, room)
        }
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        try {
            val egl = EglBase.create()

            helper = SurfaceTextureHelper.create(
                "ScreenLink",
                egl.eglBaseContext
            )

            source = factory!!.createVideoSource(true)

            capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        callback?.invoke("Screen capture stopped", room)
                    }
                }
            )

            capturer!!.initialize(
                helper,
                app,
                source!!.capturerObserver
            )

            val d = app.resources.displayMetrics
            capturer!!.startCapture(
                d.widthPixels,
                d.heightPixels,
                30
            )

            track = factory!!.createVideoTrack("screen", source)
            peer!!.addTrack(track, listOf("screen"))

            peer!!.createOffer(object : Obs() {
                override fun onCreateSuccess(s: SessionDescription) {
                    peer?.setLocalDescription(Obs(), s)

                    val offer = JSONObject()
                        .put("type", s.type.canonicalForm())
                        .put("sdp", s.description)

                    socket?.emit(
                        "offer",
                        JSONObject()
                            .put("room", room)
                            .put("offer", offer)
                    )

                    callback?.invoke("Screen sharing", room)
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
        ScreenShareService.onReady = null

        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        capturer?.dispose()
        capturer = null

        track?.dispose()
        track = null

        source?.dispose()
        source = null

        helper?.dispose()
        helper = null

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
    }

    private open class Obs : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String) {}
        override fun onSetFailure(s: String) {}
    }
}
