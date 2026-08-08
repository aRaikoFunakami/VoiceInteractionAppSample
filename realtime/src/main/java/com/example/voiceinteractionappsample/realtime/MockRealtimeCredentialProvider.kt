package com.example.voiceinteractionappsample.realtime

import java.time.Instant

/**
 * Development-only credential source (3-2, docs/broker-contract.md).
 *
 * Session Broker is out of scope for this repo. Until a real Broker exists, configure this
 * with a client secret obtained manually and OUTSIDE of Android — e.g. curl'd from
 * `POST https://api.openai.com/v1/realtime/client_secrets` using a developer's own standard
 * API key on a workstation. Never embed a standard OpenAI API key in this app (5節/24節).
 *
 * Swap this for a real `RealtimeCredentialProvider` implementation once Broker exists;
 * [RealtimeWebRtcClient] (3-3) depends only on the interface, not on this class.
 */
class MockRealtimeCredentialProvider(
    private val clientSecret: String,
    private val expiresAt: Instant,
    private val sessionConfigVersion: String = "mock",
) : RealtimeCredentialProvider {
    override suspend fun fetchCredential(): RealtimeCredential =
        RealtimeCredential(clientSecret, expiresAt, sessionConfigVersion)
}
