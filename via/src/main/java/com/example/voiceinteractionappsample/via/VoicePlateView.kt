package com.example.voiceinteractionappsample.via

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.widget.TextView
import com.example.voiceinteractionappsample.session.ConversationSessionState

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
            append("\n接続: ")
            append(session.connection.name)
            if (session.assistantTranscript.isNotBlank()) {
                append("\n\nAI: ")
                append(session.assistantTranscript)
            }
            if (session.userTranscript.isNotBlank()) {
                append("\nあなた: ")
                append(session.userTranscript)
            }
            if (session.interruptionCount > 0) {
                append("\n\n⚠️ 割り込み検出: ")
                append(session.interruptionCount)
                append("回")
            }
            if (session.totalTokens > 0) {
                append("\n\nトークン: ")
                append(session.totalTokens)
                append(" (約$")
                append(String.format("%.4f", session.totalCostUsd))
                append(")")
            }
        }
    }
}
