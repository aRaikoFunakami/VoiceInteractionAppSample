package com.example.voiceinteractionappsample.realtime

import android.content.Context

/**
 * Which backend to connect to (issue #43, #47).
 * [LOCAL_AGENT] は完全オンデバイスの local voice agent (issue #48) — サーバー URL を持たない。
 */
enum class RealtimeServerMode { OPENAI, LOCAL, LOCAL_AGENT }

/** 会話言語の設定。3モード全て(OpenAI/Local/LocalAgent)がこの設定に従う。 */
enum class ConversationLanguage(val code: String) { JA("ja"), EN("en") }

/**
 * Persists the server-switch setting so it survives app restarts without a rebuild (issue #43).
 * Written by :app's settings Activity, read by :via at session start. Plain SharedPreferences —
 * it's two small values, no need for a datastore dependency.
 *
 * OpenAI mode used to hardcode the broker host to the AVD loopback address (`10.0.2.2`), which
 * made a real device unable to reach the broker at all (no way to enter its own address). Both
 * OpenAI and Local mode now point the broker at [localHost] — it defaults to `10.0.2.2` so
 * emulator use is unaffected, and a real device just needs the host PC's LAN IP entered instead
 * (which `backend/local_broker.py` prints on startup). Only the WebRTC calls endpoint still
 * differs: OpenAI mode talks to OpenAI's own servers for that, Local mode to [localHost].
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

    var language: ConversationLanguage
        get() = runCatching { ConversationLanguage.valueOf(prefs.getString(KEY_LANGUAGE, null) ?: "") }
            .getOrDefault(ConversationLanguage.JA)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.name).apply()

    val brokerUrl: String
        get() = when (mode) {
            RealtimeServerMode.OPENAI -> "http://$localHost:8787/api/realtime/session"
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
        const val KEY_LANGUAGE = "language"
        const val DEFAULT_LOCAL_HOST = "10.0.2.2"
    }
}
