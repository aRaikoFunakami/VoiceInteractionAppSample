package com.example.voiceinteractionappsample.session

import kotlinx.coroutines.flow.StateFlow

/**
 * 音声セッションのバックエンド差し替え点 (issue #47, docs/local-voice-agent-dev-plan.md §3.1)。
 * :via が会話バックエンドに要求するのはこの 3 メンバーだけ — OpenAI Realtime (WebRTC) の
 * [ConversationController] と、オンデバイス版 LocalAgentController (issue #48) の共通契約。
 *
 * 実装側の義務:
 * - [start]/[cancel] は Main ディスパッチャから直接 launch される(CarVoiceInteractionSession の
 *   scope は Dispatchers.Main)。ブロッキング処理は実装側が自分でワーカーへ逃がすこと。
 * - [cancel] のデフォルト引数はこのインターフェースだけが持つ。Kotlin は override 側での
 *   デフォルト引数の再宣言を禁止しているため、実装クラスでは付けない。
 */
interface VoiceSessionController {
    val state: StateFlow<ConversationSessionState>

    suspend fun start()

    suspend fun cancel(reason: DisconnectReason = DisconnectReason.USER_CANCEL)
}
