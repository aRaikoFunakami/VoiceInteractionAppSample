package com.example.voiceinteractionappsample.localagent

import com.example.voiceinteractionappsample.tools.ToolCall
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.util.UUID
import org.json.JSONObject

/**
 * LiteRT-LM のネイティブツール API と :tools の [ToolCall] 契約の橋渡し(issue #50)。
 * OpenAI 側の RealtimeToolBridge に相当する。
 *
 * automaticToolCalling は使わない(false)— モデルのツールコールは必ず
 * DeviceToolExecutor のパイプライン(parse → required fields → policy → UX → execute)を
 * 通す、という :tools の設計をローカルでも守る。
 */
object LocalToolBridge {

    const val TOOL_NAME = "open_youtube_search"

    /**
     * スパイク実測(issue #50): Gemma のネイティブツールテンプレートに宣言を注入するだけでは
     * 2B クラスのモデルは一度も発火しない(0/10)。システム指示での明示ガイダンスが必須。
     */
    const val TOOL_INSTRUCTION =
        "ユーザーが動画を見たい・探したい・再生したいと頼んだときは、文章で答えずに必ず " +
            "open_youtube_search ツールを検索キーワード付きで呼び出してください。" +
            "動画に関係ない話題ではツールを使ってはいけません。"

    /** TOOL_INSTRUCTION の英語版(会話言語 EN のとき使用)。 */
    const val TOOL_INSTRUCTION_EN =
        "When the user asks to watch, find, or play a video, do not answer in text — " +
            "always call the open_youtube_search tool with a search query. " +
            "Never use the tool for topics unrelated to videos."

    /** ツール宣言(モデルへのスキーマ提示)専用。automaticToolCalling=false のため本体は呼ばれない。 */
    @Suppress("unused")
    class YouTubeToolSet : ToolSet {
        @Tool(
            description = "ユーザーが動画を見たい・探したいと明示的に頼んだときだけ、" +
                "YouTube 検索を開く。それ以外の話題では絶対に使わないこと。",
        )
        fun openYoutubeSearch(
            @ToolParam(description = "検索キーワード(日本語のまま)") query: String,
        ): String = "unused"
    }

    // ponytail: LiteRT-LM 0.16.0 のパース漏れ対策。スパイク実測でモデルのツールコール
    // 10 件中 6 件が Message.toolCalls に入らず、Gemma テンプレートの生文字列
    // (open_youtube_search{query:<|"|>猫の動画<|"|>})のままテキストに漏れた。
    // open_youtube_search 専用の救済。LiteRT-LM 側の修正かモデル更新で不要になったら消す。
    private val LEAKED_CALL = Regex("""open_youtube_search\{query:<\|"\|>(.*?)<\|"\|>""")

    /** LLM 応答 1 ターンぶんの解釈結果。toolCall が非 null ならツール実行、null なら発話。 */
    data class LlmTurn(val toolCall: ToolCall?, val replyText: String)

    fun toTurn(message: Message): LlmTurn {
        val text = message.toString()
        message.toolCalls.firstOrNull()?.let { tc ->
            return LlmTurn(
                toolCall = ToolCall(
                    callId = UUID.randomUUID().toString(), // OpenAI と違いサーバー採番がないため自前
                    name = tc.name,
                    argumentsJson = JSONObject(tc.arguments).toString(),
                ),
                replyText = "",
            )
        }
        parseLeakedToolCall(text)?.let { return LlmTurn(toolCall = it, replyText = "") }
        return LlmTurn(toolCall = null, replyText = text)
    }

    /** パース漏れしたツールコール文字列からの救済抽出(JVM テストあり)。 */
    internal fun parseLeakedToolCall(text: String): ToolCall? {
        val m = LEAKED_CALL.find(text) ?: return null
        return ToolCall(
            callId = UUID.randomUUID().toString(),
            name = TOOL_NAME,
            argumentsJson = JSONObject().put("query", m.groupValues[1]).toString(),
        )
    }
}
