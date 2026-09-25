package com.screenlink.app

import android.app.Activity
import android.app.AlertDialog
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
            if (!ScreenLinkClient.canStartSharing()) {
                status.text = "Connect the computer first"
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Allow screen sharing")
                .setMessage(
                    "ScreenLink needs your permission to capture your phone screen. " +
                    "Android will show a system confirmation next. Your screen is not shared until you allow it."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    status.text = "Waiting for screen-sharing permission…"
                    val intent = ScreenLinkClient.createCaptureIntent()
                    startActivityForResult(
                        intent,
                        ScreenLinkClient.CAPTURE_REQUEST
                    )
                }
                .show()
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
            status.text = "Starting screen sharing…"
            ScreenLinkClient.startSharing(resultCode, data)
            startShare.isEnabled = false
            stopShare.isEnabled = true
        } else {
            status.text = "Screen sharing permission was cancelled"
            startShare.isEnabled = true
            stopShare.isEnabled = false
        }
    }

    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) {
            ScreenLinkClient.shutdown()
        }
        super.onDestroy()
    }
}