package com.example.voiceinteractionappsample.via

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.TextView
import com.example.voiceinteractionappsample.session.ConversationSessionState
import com.example.voiceinteractionappsample.session.LoadingEngine

/**
 * Voice Plate (16節): shows the conversation display state (Listening/Thinking/Speaking/
 * Working/Error) plus a debug panel (ユーザー要望) — connection state, AI発話テキスト
 * (起動直後は固定の挨拶文)、ユーザー発話の音声認識結果。動いているかどうかが外から分かり
 * にくい、という実機フィードバックへの対応。デバッグ情報を分けた別ビューにする理由はなく
 * （このサンプルアプリ自体がdev/検証用途）、1つのTextViewに複数行でまとめる。
 */
class VoicePlateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        textSize = 20f
        setPadding(48, 48, 48, 48)
        // カード背景を付けたので、テーマ依存の既定色に頼らず明示的に白にする（実機で発見:
        // 背景なしの時は透明ウィンドウの下の画面色に助けられて偶然読めていただけだった）。
        setTextColor(Color.WHITE)
    }

    fun render(plateState: VoicePlateState, session: ConversationSessionState) {
        text = buildString {
            append(plateState.name)
            append("\n")
            append(context.getString(R.string.voice_plate_connection, session.connection.name))
            // LOCAL_AGENT の初回起動のみ populate される(pendingEngines は OpenAI モードでは
            // 常に空)。3 エンジンを並列ロードしていること・どれが終わったかをここで見せる。
            if (session.pendingEngines.isNotEmpty()) {
                append("\n\n")
                append(context.getString(R.string.voice_plate_loading_title))
                for (engine in LoadingEngine.entries) {
                    append("\n")
                    append(if (engine in session.pendingEngines) "… " else "✓ ")
                    append(context.getString(engineLabelRes(engine)))
                }
            }
            if (session.assistantTranscript.isNotBlank()) {
                append("\n\nAI: ")
                append(session.assistantTranscript)
            }
            if (session.userTranscript.isNotBlank()) {
                append("\n")
                append(context.getString(R.string.voice_plate_you_label))
                append(session.userTranscript)
            }
            if (session.interruptionCount > 0) {
                append("\n\n")
                append(context.getString(R.string.voice_plate_interruptions, session.interruptionCount))
            }
            if (session.totalTokens > 0) {
                append("\n\n")
                append(
                    context.getString(
                        R.string.voice_plate_tokens,
                        session.totalTokens,
                        String.format("%.4f", session.totalCostUsd),
                    ),
                )
            }
        }
    }

    private fun engineLabelRes(engine: LoadingEngine): Int = when (engine) {
        LoadingEngine.LLM -> R.string.voice_plate_engine_llm
        LoadingEngine.STT -> R.string.voice_plate_engine_stt
        LoadingEngine.TTS -> R.string.voice_plate_engine_tts
    }
}
