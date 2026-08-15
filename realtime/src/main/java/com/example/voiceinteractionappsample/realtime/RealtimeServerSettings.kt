package com.example.voiceinteractionappsample.realtime

import android.content.Context

/**
 * Which backend to connect to (issue #43, #47).
 * [LOCAL_AGENT] は完全オンデバイスの local voice agent (issue #48) — サーバー URL を持たない。
 */
enum class RealtimeServerMode { OPENAI, LOCAL, LOCAL_AGENT }

/**
 * Persists the server-switch setting so it survives app restarts without a rebuild (issue #43).
 * Written by :app's settings Activity, read by :via at session start. Plain SharedPreferences —
 * it's two small values, no need for a datastore dependency.
 *
 * OpenAI mode keeps the previous hardcoded AVD broker host (`10.0.2.2`, unaffected by this
 * setting — real-device/real-broker hosts are out of scope per the issue, only local-server
 * switching is). Local mode points both broker and WebRTC calls endpoint at the host the user
 * enters, per docs/broker-contract.md and local_realtime_llm's ports (8787 broker, 8765 calls).
 */
class RealtimeServerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: RealtimeServerMode
        get() = runCatching { RealtimeServerMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(RealtimeServerMode.OPENAI)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var localHost: String
        get() = prefs.getString(KEY_LOCAL_HOST, DEFAULT_LOCAL_HOST) ?: DEFAULT_LOCAL_HOST
        set(value) = prefs.edit().putString(KEY_LOCAL_HOST, value).apply()

    val brokerUrl: String
        get() = when (mode) {
            RealtimeServerMode.OPENAI -> "http://$AVD_HOST_LOOPBACK:8787/api/realtime/session"
            RealtimeServerMode.LOCAL -> "http://$localHost:8787/api/realtime/session"
            RealtimeServerMode.LOCAL_AGENT -> "" // オンデバイス動作のためサーバー不要 (#47)
        }

    val realtimeCallsUrl: String
        get() = when (mode) {
            RealtimeServerMode.OPENAI -> RealtimeWebRtcClient.DEFAULT_REALTIME_CALLS_URL
            RealtimeServerMode.LOCAL -> "http://$localHost:8765/v1/realtime/calls"
            RealtimeServerMode.LOCAL_AGENT -> "" // 同上
        }

    private companion object {
        const val PREFS_NAME = "realtime_server_settings"
        const val KEY_MODE = "mode"
        const val KEY_LOCAL_HOST = "local_host"
        const val DEFAULT_LOCAL_HOST = "10.0.2.2"
        const val AVD_HOST_LOOPBACK = "10.0.2.2"
    }
}
