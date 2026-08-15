package com.example.voiceinteractionappsample

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localvoiceagent.LlmEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #50 スパイク: Gemma 4 E2B + LiteRT-LM ネイティブツール API の発火精度計測。
 * 合否基準(計画書 §10-2): 発火期待 10 発話中 ≥ 8 発火、非該当 10 発話で誤発火 0。
 *
 * automaticToolCalling=false で Message.toolCalls を数えるだけ(実行はしない)。
 * 発話ごとに新しい Conversation を作り、前の turn の影響を受けないようにする。
 */
@RunWith(AndroidJUnit4::class)
class SpikeToolCallTest {

    @Suppress("unused")
    class YouTubeToolSet : ToolSet {
        @Tool(
            description = "ユーザーが動画を見たい・探したいと明示的に頼んだときだけ、" +
                "YouTube 検索を開く。それ以外の話題では絶対に使わないこと。",
        )
        fun openYoutubeSearch(
            @ToolParam(description = "検索キーワード(日本語のまま)") query: String,
        ): String = "opened"
    }

    private val fireExpected = listOf(
        "猫の動画を見せて",
        "YouTubeで料理の動画を探して",
        "音楽ライブの動画が見たい",
        "富士山の登山の動画を再生して",
        "サッカーのハイライト動画を見せてください",
        "面白い動画を探して",
        "車のレビュー動画が見たいな",
        "ユーチューブでニュースの動画を開いて",
        "子供向けのアニメ動画を見せて",
        "リラックスできる音楽の動画をかけて",
    )

    private val noFireExpected = listOf(
        "こんにちは",
        "今日の天気はどうですか",
        "近くのレストランを教えて",
        "明日の予定を教えて",
        "日本の首都はどこですか",
        "エアコンの温度を下げて",
        "眠くなってきたよ",
        "おすすめの本を教えて",
        "3たす5はいくつ",
        "ありがとう、もう大丈夫です",
    )

    @Test
    fun measureToolFireAccuracy() {
        assumeTrue("gemma model not pushed", LlmEngine.modelAvailable())

        val engine = Engine(EngineConfig(modelPath = LlmEngine.MODEL_PATH, backend = Backend.CPU()))
        engine.initialize()
        val provider = tool(YouTubeToolSet())

        // 発見(preface probe): ツール宣言は Gemma のネイティブテンプレートで正しく注入されるが、
        // 宣言だけでは 2B クラスのモデルは一度もツールを選ばなかった(0/10)。
        // システム指示での明示的なガイダンスが必要。
        val instructionWithTool = LlmEngine.SYSTEM_INSTRUCTION +
            "ユーザーが動画を見たい・探したい・再生したいと頼んだときは、文章で答えずに必ず " +
            "open_youtube_search ツールを検索キーワード付きで呼び出してください。" +
            "動画に関係ない話題ではツールを使ってはいけません。"

        fun countToolCall(utterance: String): Pair<Boolean, String> {
            val conversation = engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instructionWithTool),
                    tools = listOf(provider),
                    automaticToolCalling = false,
                ),
            )
            try {
                val message = conversation.sendMessage(utterance)
                // 出荷ロジックと同一判定: ネイティブ toolCalls or パース漏れ救済(LocalToolBridge)
                val turn = com.example.voiceinteractionappsample.localagent.LocalToolBridge.toTurn(message)
                val fired = turn.toolCall != null
                val detail = if (fired) {
                    "${turn.toolCall!!.name}(${turn.toolCall!!.argumentsJson})"
                } else {
                    message.toString().take(60)
                }
                return fired to detail
            } finally {
                conversation.close()
            }
        }

        var fires = 0
        var falseFires = 0
        try {
            for (u in fireExpected) {
                val (fired, detail) = countToolCall(u)
                if (fired) fires++
                Log.i(TAG, "FIRE-EXPECTED ${if (fired) "✓fired" else "✗missed"}: \"$u\" -> $detail")
            }
            for (u in noFireExpected) {
                val (fired, detail) = countToolCall(u)
                if (fired) falseFires++
                Log.i(TAG, "NO-FIRE ${if (fired) "✗FALSE-FIRE" else "✓quiet"}: \"$u\" -> $detail")
            }
        } finally {
            engine.close()
        }

        Log.i(TAG, "RESULT: fires=$fires/10 falseFires=$falseFires/10 (pass: fires>=8 && falseFires==0)")
        assertTrue("fire rate too low: $fires/10", fires >= 8)
        assertTrue("false fires: $falseFires/10", falseFires == 0)
    }

    private companion object {
        const val TAG = "SpikeToolCallTest"
    }
}
