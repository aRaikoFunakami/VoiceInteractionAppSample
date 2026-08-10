package com.example.voiceinteractionappsample.realtime

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Calls Session Broker's `POST /api/realtime/session` (docs/broker-contract.md). Broker itself
 * is out of scope for this repo — see backend/local_broker.py for a local stand-in that runs
 * on a developer's own machine and never puts a standard OpenAI API key on the device.
 */
class HttpRealtimeCredentialProvider(
    private val brokerUrl: String,
) : RealtimeCredentialProvider {

    override suspend fun fetchCredential(): RealtimeCredential = withContext(Dispatchers.IO) {
        val connection = (URL(brokerUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.writeEmptyJsonBody() }
            val status = connection.responseCode
            if (status !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw RealtimeConnectionException("Broker request failed: HTTP $status $error")
            }
            val body = JSONObject(connection.inputStream.bufferedReader().readText())
            RealtimeCredential(
                clientSecret = body.getString("clientSecret"),
                expiresAt = Instant.parse(body.getString("expiresAt")),
                sessionConfigVersion = body.optString("sessionConfigVersion", "unknown"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun OutputStream.writeEmptyJsonBody() {
        write("{}".toByteArray(Charsets.UTF_8))
    }
}
