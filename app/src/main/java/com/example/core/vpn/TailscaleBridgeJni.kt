package com.example.core.vpn

import android.util.Log

/**
 * JNI Bridge to the native Go / tsnet / gVisor core.
 * Direct bridge to app/src/main/go/bridge.go.
 */
object TailscaleBridgeJni {

    private const val TAG = "TailscaleBridgeJni"
    var isLibraryLoaded = false
        private set

    init {
        try {
            System.loadLibrary("tsnetbridge")
            isLibraryLoaded = true
            Log.i(TAG, "Native tsnetbridge library loaded successfully!")
        } catch (e: UnsatisfiedLinkError) {
            isLibraryLoaded = false
            Log.w(TAG, "tsnetbridge native library not available in APK bundle: ${e.message}")
        }
    }

    external fun registerPacketListener(listener: Any): Int
    external fun startTsnet(authKey: String, nodeName: String, routes: String, dataDir: String): Int
    external fun stopTsnet(): Int
    external fun injectPacket(packet: ByteArray, length: Int): Int
    external fun getAssignedTailnetIp(): String
    external fun getBackendState(): String
}
