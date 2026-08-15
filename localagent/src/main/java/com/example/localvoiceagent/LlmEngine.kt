package com.example.localvoiceagent

import com.example.voiceinteractionappsample.localagent.LocalToolBridge
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.tool
import java.io.File

/**
 * LiteRT-LM (Gemma 4 E2B) のラッパ(android-local-voice-agent からの移植、issue #48)。
 * モデルは scripts/fetch_gemma.sh が /data/local/tmp/llm/ へ push する。
 * initialize()/ask() はブロッキング。呼び出し側が worker thread で実行すること。
 *
 * 移植時の変更(docs/local-voice-agent-dev-plan.md §3.3, R9):
 * - ask()/resetConversation()/close()/initialize() を @Synchronized で直列化。
 *   sendMessage() 実行中に別スレッドが conversation.close() を呼ぶと native ハンドルの
 *   use-after-free になりうるため、状態を変える操作は同時に走らせない。
 * - cancelActive() 追加(ロックを取らずに実行中の推論を打ち切る。LiteRT-LM の
 *   Conversation.cancelProcess() は in-flight sendMessage を中断するための API)。
 */
class LlmEngine {
    companion object {
        const val MODEL_PATH = "/data/local/tmp/llm/gemma-4-E2B-it.litertlm"

        // 音声会話向け: 短く話し言葉で返す
        const val SYSTEM_INSTRUCTION =
            "あなたは音声会話アシスタントです。日本語で、2文以内の短い話し言葉で答えてください。"

        fun modelAvailable(): Boolean = File(MODEL_PATH).canRead()
    }

    private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    /** モデルロード。数秒〜10 秒程度かかる。 */
    @Synchronized
    fun initialize() {
        if (engine != null) return
        check(modelAvailable()) { "model not found: $MODEL_PATH" }
        val e = Engine(EngineConfig(modelPath = MODEL_PATH, backend = Backend.CPU()))
        e.initialize()
        engine = e
        conversation = newConversation(e)
    }

    // issue #50: YouTube 検索ツールを宣言付きで会話に組み込む。実行は自動ではなく
    // DeviceToolExecutor 経由(automaticToolCalling = false)。
    private fun newConversation(e: Engine): Conversation = e.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(
                SYSTEM_INSTRUCTION + LocalToolBridge.TOOL_INSTRUCTION,
            ),
            tools = listOf(tool(LocalToolBridge.YouTubeToolSet())),
            automaticToolCalling = false,
        ),
    )

    fun isInitialized(): Boolean = engine != null

    /** 同期テキスト対話。応答テキストを返す。cancelActive() で中断されると例外で抜ける。 */
    @Synchronized
    fun ask(prompt: String): String = askMessage(prompt).toString()

    /** 同期対話(ツールコール判定用に Message のまま返す)。issue #50。 */
    @Synchronized
    fun askMessage(prompt: String): Message {
        val c = conversation ?: error("not initialized")
        return c.sendMessage(prompt)
    }

    /**
     * 実行中の sendMessage() を打ち切る。@Synchronized を取らない —
     * ask() がロックを握ったまま推論している最中に呼べることがこの関数の存在意義。
     */
    fun cancelActive() {
        runCatching { conversation?.cancelProcess() }
    }

    /** 会話履歴をリセットする(セッション開始ごとに呼ぶ。履歴の無限成長防止)。 */
    @Synchronized
    fun resetConversation() {
        val e = engine ?: return
        runCatching { conversation?.close() }
        conversation = newConversation(e)
    }

    @Synchronized
    fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }
}
