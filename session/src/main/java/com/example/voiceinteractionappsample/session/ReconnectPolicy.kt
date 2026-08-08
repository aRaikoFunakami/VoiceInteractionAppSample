package com.example.voiceinteractionappsample.session

import kotlin.math.min
import kotlin.math.pow

/**
 * 7-3節: レビューで指摘された未定義点「再接続のリトライ回数・バックオフ・FAILEDへの遷移条件」
 * を数値として固定する。値そのものは実車評価前の暫定値（ponytail: 変更前提のノブ、他の
 * 未確定値と同じ扱い）。
 *
 * ICEが FAILED を直接報告した場合は再試行せず即座に尽きたものとして扱う
 * ([ConversationController] 側の配線)。DISCONNECTED（一時的切断）はここで定義する回数・
 * 間隔でリトライし、尽きたら FAILED へ遷移する。
 */
data class ReconnectPolicy(
    val maxAttempts: Int = 3,
    val initialBackoffMs: Long = 1_000,
    val backoffMultiplier: Double = 2.0,
    val maxBackoffMs: Long = 10_000,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must be >= 0, was $maxAttempts" }
        require(initialBackoffMs > 0) { "initialBackoffMs must be > 0, was $initialBackoffMs" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1.0, was $backoffMultiplier" }
    }

    /** True once [attempt] (1-indexed) exceeds [maxAttempts] — caller should transition to FAILED. */
    fun isExhausted(attempt: Int): Boolean = attempt > maxAttempts

    /** Backoff before retry number [attempt] (1-indexed), capped at [maxBackoffMs]. */
    fun backoffForAttempt(attempt: Int): Long {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val raw = initialBackoffMs * backoffMultiplier.pow(attempt - 1)
        return min(raw, maxBackoffMs.toDouble()).toLong()
    }
}
