package com.screenlink.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var roomCode: TextView
    private lateinit var createRoom: Button
    private lateinit var startShare: Button
    private lateinit var stopShare: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        roomCode = findViewById(R.id.roomCode)
        createRoom = findViewById(R.id.createRoom)
        startShare = findViewById(R.id.startShare)
        stopShare = findViewById(R.id.stopShare)

        ScreenLinkClient.init(this) { state, code ->
            runOnUiThread {
                status.text = state
                if (code != null) {
                    roomCode.text = code
                    startShare.isEnabled = true
                }
                if (state == "Screen capture stopped" || state == "Sharing stopped") {
                    startShare.isEnabled = code != null
                    stopShare.isEnabled = false
                }
            }
        }

        createRoom.setOnClickListener {
            ScreenLinkClient.createRoom()
        }

        startShare.setOnClickListener {
            val intent = ScreenLinkClient.createCaptureIntent()
            startActivityForResult(intent, ScreenLinkClient.CAPTURE_REQUEST)
        }

        stopShare.setOnClickListener {
            ScreenLinkClient.stopSharing()
            stopShare.isEnabled = false
            startShare.isEnabled = true
            status.text = "Sharing stopped"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != ScreenLinkClient.CAPTURE_REQUEST) return

        if (resultCode == RESULT_OK && data != null) {
            ScreenLinkClient.startSharing(resultCode, data)
            startShare.isEnabled = false
            stopShare.isEnabled = true
        } else {
            status.text = "Screen sharing permission was cancelled"
        }
    }

    override fun onDestroy() {
        ScreenLinkClient.shutdown()
        super.onDestroy()
    }
}
