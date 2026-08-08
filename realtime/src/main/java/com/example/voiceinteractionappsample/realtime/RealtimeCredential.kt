package com.example.voiceinteractionappsample.realtime

import java.time.Instant

/**
 * Mirrors the Session Broker response contract (docs/broker-contract.md):
 * ```json
 * { "clientSecret": "...", "expiresAt": "...", "sessionConfigVersion": "..." }
 * ```
 *
 * This is our own Broker's shape, not OpenAI's. Verified live against
 * `POST /v1/realtime/client_secrets` (2026-08): OpenAI's own response uses `value` (the
 * secret) and `expires_at` (unix epoch seconds), not `clientSecret`/`expiresAt` ISO-8601 —
 * the real Broker implementation maps OpenAI's `value`/`expires_at` onto this shape, it does
 * not pass them through verbatim.
 */
data class RealtimeCredential(
    val clientSecret: String,
    val expiresAt: Instant,
    val sessionConfigVersion: String,
) {
    // clientSecretはログ等に晒さない（3-5節）。data classの既定toStringは全プロパティを
    // 出力するため、ここで上書きしないと将来どこかでうっかりログに出る事故が起きる。
    override fun toString(): String =
        "RealtimeCredential(clientSecret=***, expiresAt=$expiresAt, sessionConfigVersion=$sessionConfigVersion)"
}

/** Contract for obtaining a short-lived OpenAI Realtime credential (5節). */
interface RealtimeCredentialProvider {
    suspend fun fetchCredential(): RealtimeCredential
}
