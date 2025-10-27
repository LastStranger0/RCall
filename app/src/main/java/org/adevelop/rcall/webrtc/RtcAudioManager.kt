package org.adevelop.rcall.webrtc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import androidx.annotation.RequiresPermission

class RtcAudioManager(private val context: Context) {

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private var isBluetoothHeadsetConnected = false

    // BroadcastReceiver to detect Bluetooth headset connection changes
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                when (state) {
                    BluetoothHeadset.STATE_CONNECTED -> {
                        Log.d("RtcAudioManager", "Bluetooth headset connected.")
                        isBluetoothHeadsetConnected = true
                        updateAudioRoute()
                    }
                    BluetoothHeadset.STATE_DISCONNECTED -> {
                        Log.d("RtcAudioManager", "Bluetooth headset disconnected.")
                        isBluetoothHeadsetConnected = false
                        updateAudioRoute()
                    }
                }
            }
        }
    }

    fun start() {
        Log.d("RtcAudioManager", "Activating audio management.")

        // Register the receiver for Bluetooth state changes
        val filter = IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        context.registerReceiver(bluetoothReceiver, filter)

        // Request audio focus for communication.
        audioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
        // Set the mode to in-communication.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        // Check initial Bluetooth state and update routing
        updateAudioRoute()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun checkInitialBluetoothState() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        isBluetoothHeadsetConnected = bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
        Log.d("RtcAudioManager", "Initial Bluetooth headset connected state: $isBluetoothHeadsetConnected")
    }

    private fun updateAudioRoute() {
        if (isBluetoothHeadsetConnected) {
            // If a Bluetooth headset is connected, start Bluetooth SCO and let the system handle routing.
            Log.d("RtcAudioManager", "Starting Bluetooth SCO and disabling speakerphone.")
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            audioManager.isSpeakerphoneOn = false
        } else {
            // If no Bluetooth headset, stop SCO and route audio to the speakerphone.
            Log.d("RtcAudioManager", "Stopping Bluetooth SCO and enabling speakerphone.")
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = true
        }
    }

    fun stop() {
        Log.d("RtcAudioManager", "Deactivating audio management.")

        // Unregister the receiver to avoid memory leaks
        try {
            context.unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w("RtcAudioManager", "Bluetooth receiver was not registered.", e)
        }

        // Stop SCO if it was on
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }

        // Restore default audio mode.
        audioManager.mode = AudioManager.MODE_NORMAL
        // Abandon audio focus.
        audioManager.abandonAudioFocus(null)
        // Turn off speakerphone.
        audioManager.isSpeakerphoneOn = false
    }
}