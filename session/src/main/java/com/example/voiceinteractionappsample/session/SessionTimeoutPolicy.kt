package com.example.voiceinteractionappsample.session

/**
 * 26節「conversation idle timeout」— 元計画で未確定のまま残されていた項目。
 *
 * OpenAI Realtime APIは接続している間ずっと課金される。明示的な歯止めが無いと、
 * 「PTTを押したまま放置」や「周囲雑音がVADを誤検出し続けて会話が終わらない」といった
 * ケースで際限なく課金され得る。二段構えで守る:
 *
 * - [idleTimeoutMs]: 何のRealtimeイベントも来ない状態がこの時間続いたら強制切断
 *   （「押したまま放置」対策）。
 * - [maxSessionDurationMs]: 会話がどれだけアクティブでも、この時間で無条件に強制切断
 *   （「雑音でVADが誤トリガーし続けて会話が終わらない」対策 — idleTimeoutだけでは
 *   イベントが流れ続けている限り効かないため、これが最終防波堤になる）。
 *
 * 値そのものは他の未確定値（RealtimeVadConfig等）と同様、実運用前の暫定値。
 */
data class SessionTimeoutPolicy(
    val idleTimeoutMs: Long = 10_000,
    val maxSessionDurationMs: Long = 2 * 60_000,
) {
    init {
        require(idleTimeoutMs > 0) { "idleTimeoutMs must be > 0, was $idleTimeoutMs" }
        require(maxSessionDurationMs > 0) { "maxSessionDurationMs must be > 0, was $maxSessionDurationMs" }
    }
}
