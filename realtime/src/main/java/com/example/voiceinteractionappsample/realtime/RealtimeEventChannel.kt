package com.example.voiceinteractionappsample.realtime

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.webrtc.DataChannel

/**
 * Wraps the "oai-events" DataChannel (4節). Pure transport — event modeling is
 * [RealtimeEventCodec].
 *
 * Registers its observer eagerly at construction (not lazily on first [incoming] collection)
 * and buffers into an unlimited [Channel] — a message that arrives before anyone calls
 * [incoming] (e.g. a fast reply to our own session.update) must not be lost.
 */
class RealtimeEventChannel(private val dataChannel: DataChannel) {

    private val messages = Channel<String>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()

    init {
        if (dataChannel.state() == DataChannel.State.OPEN) opened.complete(Unit)
        dataChannel.registerObserver(
            object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) = Unit

                override fun onStateChange() {
                    if (dataChannel.state() == DataChannel.State.OPEN) {
                        opened.complete(Unit)
                    }
                }

                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (buffer == null) return
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    messages.trySendBlocking(String(bytes, StandardCharsets.UTF_8))
                }
            }
        )
    }

    /** Suspends until the underlying DataChannel reaches OPEN (sending before that can be dropped). */
    suspend fun awaitOpen() = opened.await()

    fun send(json: String) {
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8)),
            false,
        )
        dataChannel.send(buffer)
    }

    fun incoming(): Flow<String> = messages.receiveAsFlow()

    fun close() {
        messages.close()
        dataChannel.unregisterObserver()
        dataChannel.close()
    }
}
