package com.example.voiceinteractionappsample.realtime

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * 実機で発見（本番の車載アシスタント人格・日本語文字起こし設定を含むsession.updateが
     * 一度もサーバーに届いていなかった）: `connect()`はSDPシグナリングが終わった時点で返る
     * だけで、DataChannelのSCTPレベルOPENはその後非同期に来る。以前は[awaitOpen]を呼ぶかは
     * 呼び出し側任せにしていたが、誰も呼んでおらず起動直後の最初のsend()が黙ってdropされて
     * いた。全送信がここを通る一箇所で待つようにし、呼び出し側の記憶に頼らないようにする。
     *
     * タイムアウト付き — 別件で実機確認済みの通りICEがCHECKINGのまま繋がらないケースが
     * 実在するため、無条件にawaitすると[cancel]のteardown経路（response.cancel送信）が
     * 永久にハングしてしまう。開かないまま[SEND_OPEN_TIMEOUT_MS]経過したら諦めて捨てる。
     *
     * @return 送信できたら true。false = drop（後続イベントの送信可否を呼び出し側が
     *   判断できるように返す — session.update が落ちたのに response.create だけ届く、を防ぐ）。
     */
    suspend fun send(json: String): Boolean {
        val ready = withTimeoutOrNull(SEND_OPEN_TIMEOUT_MS) { awaitOpen() }
        if (ready == null) {
            Log.w(TAG, "send() dropped: DataChannel never reached OPEN within ${SEND_OPEN_TIMEOUT_MS}ms")
            return false
        }
        val buffer = DataChannel.Buffer(
            ByteBuffer.wrap(json.toByteArray(StandardCharsets.UTF_8)),
            false,
        )
        return dataChannel.send(buffer)
    }

    fun incoming(): Flow<String> = messages.receiveAsFlow()

    fun close() {
        messages.close()
        dataChannel.unregisterObserver()
        dataChannel.close()
    }

    private companion object {
        const val TAG = "RealtimeEventChannel"
        const val SEND_OPEN_TIMEOUT_MS = 5_000L
    }
}
