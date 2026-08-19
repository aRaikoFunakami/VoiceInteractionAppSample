package com.example.voiceinteractionappsample.via

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.voiceinteractionappsample.localagent.LocalAgentController
import com.example.voiceinteractionappsample.realtime.HttpRealtimeCredentialProvider
import com.example.voiceinteractionappsample.realtime.RealtimeServerMode
import com.example.voiceinteractionappsample.realtime.RealtimeServerSettings
import com.example.voiceinteractionappsample.session.AudioInputState
import com.example.voiceinteractionappsample.session.AudioOutputState
import com.example.voiceinteractionappsample.session.ConnectionState
import com.example.voiceinteractionappsample.session.ConversationController
import com.example.voiceinteractionappsample.session.ConversationSessionState
import com.example.voiceinteractionappsample.session.ConversationState
import com.example.voiceinteractionappsample.session.DisconnectReason
import com.example.voiceinteractionappsample.session.VoiceSessionController
import com.example.voiceinteractionappsample.tools.DeviceToolExecutor
import com.example.voiceinteractionappsample.tools.OpenYouTubeSearchTool
import com.example.voiceinteractionappsample.tools.OpenYouTubeSearchToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Owns Voice Plate + the conversation session lifecycle (1節, 17節) — and actually starts/stops
 * a real Realtime conversation via [ConversationController] (7-1節: this was the missing half
 * of "接続" — ConversationController existed and was tested standalone since Phase 7, but was
 * never wired to the session PTT/TTT actually drives, so pressing PTT only ever showed the
 * Voice Plate without connecting to anything).
 *
 * Needs `backend/local_broker.py` running on the host — Session Broker itself is out of scope
 * for this repo (docs/broker-contract.md). See docs/how-to-run.md.
 */
class CarVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    // Dispatchers.Main is required here — controller.state emits from ConversationController's
    // own background-dispatched coroutines, and VoicePlateView.setState() touches a real View,
    // which crashes off the main thread. Found live: the first end-to-end run threw exactly
    // this (ViewRootImpl rejecting the setState() call chain from a background thread).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var voicePlateView: VoicePlateView? = null
    // 実機で発見（"ツールを呼び出せない"）: スキーマ・実行パイプライン自体はPhase 8-9で
    // 作って個別にテスト済みだったが、ConversationControllerへの配線が漏れていた
    // （session.updateの`tools`が常に空配列のままサーバーに送られていた）。
    // issue #43: rebuilt in onShow() (not just once here at construction) — this session
    // instance can live across many show/hide cycles (onHide() never calls finish()), so
    // reading RealtimeServerSettings only at construction would freeze whichever server was
    // configured when the session was first created, instead of the setting taking effect
    // on the next PTT press as intended.
    private var controller = createController()
    // issue #47: onShow()ごとに launchIn していた state コレクタが解放されず PTT のたびに
    // 増えていた(旧コレクタは破棄済み controller を監視し続ける)。Job を保持して張り替える。
    private var stateJob: Job? = null
    // 実機で発見: onShow()のトグル判定を controller.state.value.connection(非同期の後片付けが
    // 終わるまで古い値のまま)で行っていたため、強制終了直後にマイクを押すと毎回「まだアクティブ」
    // と誤判定し、新しい会話が二度と始まらなかった。ここは我々自身が同期的に更新するフラグにし、
    // cancel()の後片付け(スレッドjoinで最大数秒)の完了タイミングと切り離す。
    private var sessionActive = false
    // onHide()は「フレームワークが我々の関与なくhideした(バックグラウンド遷移等)」場合にのみ
    // controllerをcancel()すればよい。自分からhide()を呼ぶ経路(トグル停止・auto-terminate)は
    // 対象のcontrollerを呼び出し側で明示的に処理済み(auto-terminateはcancel()の後にしか
    // 呼ばれない)なので、onHide()側で可変フィールドcontrollerを読み直すと、そのhide()が届く前に
    // 次のonShow()が新しい(無関係な)controllerに差し替えていた場合、今動いている新セッションを
    // 誤ってcancelしてしまう。このフラグで「onHide()は何もしなくていい」ケースを区別する。
    private var selfInitiatedHide = false

    private fun createController(): VoiceSessionController {
        val serverSettings = RealtimeServerSettings(context)
        if (serverSettings.mode == RealtimeServerMode.LOCAL_AGENT) {
            // issue #48/#50: 完全オンデバイスの local voice agent + YouTube 検索ツール。
            return LocalAgentController(
                context = context,
                toolExecutor = DeviceToolExecutor(listOf(OpenYouTubeSearchTool(context))),
                language = serverSettings.language,
                onAutoTerminated = { reason ->
                    Log.i(TAG, "local agent auto-terminated: $reason — hiding Voice Plate")
                    scope.launch { sessionActive = false; selfInitiatedHide = true; hide() }
                },
            )
        }
        return ConversationController(
            context = context,
            credentialProvider = HttpRealtimeCredentialProvider(serverSettings.brokerUrl),
            realtimeCallsUrl = serverSettings.realtimeCallsUrl,
            toolSchemas = JSONArray().put(OpenYouTubeSearchToolSchema.toJson()),
            toolExecutor = DeviceToolExecutor(listOf(OpenYouTubeSearchTool(context))),
            language = serverSettings.language,
            onAutoTerminated = { reason ->
                // 実機で発見: watchdogは正しくRTC/micを止めていたが、誰もVoice Plateを隠さない
                // ため画面だけが古い状態のまま残っていた。self-terminate側からもhide()を呼ぶ。
                // onAutoTerminatedはバックグラウンドディスパッチャから呼ばれるのでMainへ渡す。
                Log.i(TAG, "auto-terminated: $reason — hiding Voice Plate")
                scope.launch { sessionActive = false; selfInitiatedHide = true; hide() }
            },
        )
    }

    override fun onCreateContentView(): View {
        // 実機で発見(issue #61 の権限リクエスト検証中): VoiceInteractionSession のウィンドウは
        // 既定で画面全体(fillxfill)のサイズを取る。中身の角丸カードは WRAP_CONTENT で
        // 左上に小さく描画されるだけだが、ウィンドウ自体の当たり判定は画面全体のまま — かつ
        // NOT_TOUCH_MODAL は「ウィンドウの外側」へのタップだけを後ろへ通す仕組みなので、
        // ウィンドウが画面全体である限り「外側」が存在せず、カードの外の透明な領域を含め
        // 全タップをこのウィンドウが吸収してしまう。実行時権限ダイアログ(#61 で追加)を
        // 表示してもタップがまったく届かず操作不能になったのはこれが原因。
        // ウィンドウ自体を中身に合わせて WRAP_CONTENT にし、NOT_TOUCH_MODAL を実際に機能させる。
        // VoiceInteractionSession.getWindow() は Dialog を返す。実際の Window は
        // その Dialog.getWindow() 側にある。
        window?.window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.window?.setGravity(Gravity.TOP or Gravity.START)

        val plate = VoicePlateView(context).also { voicePlateView = it }
        // ユーザー指摘: このウィンドウは背景が透明なまま(TYPE_VOICE_INTERACTIONの既定)で、
        // かつ中身が画面幅いっぱいに伸びていたため、後ろの画面の文字とデバッグ表示が重なって
        // 読みにくかった。Google Assistant自身のUI（丸みのある半透明の吹き出し、画面には
        // り付かず内容に合わせたサイズ）を参考に、コンテンツに合わせた角丸カードにする。
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(plate)
            background = GradientDrawable().apply {
                setColor(0xE6202124.toInt()) // 濃いグレー、半透明
                cornerRadius = 32f
            }
            setPadding(32, 32, 32, 32)
        }
        return FrameLayout(context).apply {
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.TOP or Gravity.START; setMargins(32, 32, 32, 32) },
            )
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        Log.i(TAG, "onShow() called, showFlags=$showFlags")
        super.onShow(args, showFlags)

        // ユーザー指摘: Googleアシスタントはマイクボタンをもう一度押すとすぐ終了するが、
        // このアプリはそうならなかった。実機で確認済み: マイクボタンを2回目押した時も
        // onShow()自体はちゃんと再度呼ばれている（フレームワークが自動でhide()に
        // 振り替えてはくれない）。つまりトグル判定はアプリ側の責務 — 既に会話が
        // アクティブな状態でonShow()が来たら「終了しろ」という意図として扱う。
        // sessionActive(同期フラグ)で判定する。controller.state.value.connection を見ていた
        // 旧実装は、cancel()の非同期な後片付け(スレッドjoinで最大数秒)が終わるまで古い値の
        // ままだったため、後片付け完了前にマイクを押すと毎回「まだアクティブ」と誤判定し、
        // 新しい会話が二度と始まらなかった(「一度強制終了すると会話できなくなる」の原因)。
        if (sessionActive) {
            Log.i(TAG, "onShow() while already active — treating as toggle-to-stop")
            // controllerを直接cancel()するのではなくローカル変数に取ってから呼ぶ — hide()から
            // onHide()が実際に届くまでの間に次のonShow()がcontrollerフィールドを新しい
            // (無関係な)controllerへ差し替えても、このcancel()の対象が影響を受けないようにする。
            val old = controller
            sessionActive = false
            selfInitiatedHide = true
            scope.launch { old.cancel() }
            hide()
            return
        }

        // issue #43: fresh RealtimeServerSettings read for every PTT press (see createController()).
        controller = createController()
        sessionActive = true
        stateJob?.cancel()
        stateJob = controller.state.onEach { updateVoicePlate(it) }.launchIn(scope)
        scope.launch {
            try {
                controller.start()
            } catch (e: Exception) {
                Log.w(TAG, "controller.start() failed", e)
                voicePlateView?.render(VoicePlateState.ERROR, ConversationSessionState())
                // issue #47: 例外で抜けると state が CONNECTING のまま残り、次の PTT が
                // toggle-to-stop に化けて再試行に 2 回かかる。cancel() で DISCONNECTED に戻す。
                sessionActive = false
                runCatching { controller.cancel(DisconnectReason.ERROR) }
            }
        }
    }

    override fun onHide() {
        Log.i(TAG, "onHide() called")
        // 17節: onHide()と完全終了を厳密に区別するにはAAOS上のhide理由取得が必要で未確定
        // （26節）。このサンプルでは簡略化して「hideされたら会話も終える」とする —
        // ponytail: この単純化には天井がある。hide理由（バックグラウンド遷移 vs ユーザーに
        // よる明示的終了）を区別する必要が出たら見直す。
        // code review で指摘: 自分からhide()を呼んだ経路(トグル停止・auto-terminate)は
        // 対象のcontrollerを呼び出し元で既に処理済み。ここで可変フィールドcontrollerを
        // 読み直すと、そのhide()がonHide()として届く前に次のonShow()が新しい(無関係な)
        // controllerに差し替えていた場合、今動いている新セッションを誤ってcancelしてしまう
        // (LOCAL_AGENTでは共有シングルトンのTTS/LLM推論まで巻き込んで中断されうる)。
        // selfInitiatedHideで「フレームワーク起因の本物のhide」だけに絞る。
        if (selfInitiatedHide) {
            selfInitiatedHide = false
        } else {
            sessionActive = false
            scope.launch { controller.cancel() }
        }
        super.onHide()
    }

    private fun updateVoicePlate(state: ConversationSessionState) {
        val plateState = when {
            state.connection == ConnectionState.FAILED -> VoicePlateState.ERROR
            // audioserverクラッシュ等でAudioTrack/AudioRecordの初期化自体が失敗するケース
            // (実機で発見) — 接続自体はCONNECTEDのまま残るのでconnection軸だけでは拾えない。
            state.audioInput == AudioInputState.ERROR -> VoicePlateState.ERROR
            state.audioOutput == AudioOutputState.ERROR -> VoicePlateState.ERROR
            state.connection == ConnectionState.CONNECTING -> VoicePlateState.WORKING
            state.audioOutput == AudioOutputState.PLAYING -> VoicePlateState.SPEAKING
            state.conversation == ConversationState.MODEL_PROCESSING -> VoicePlateState.THINKING
            // issue #50: ローカルツール実行中(YouTube 起動など)。LOCAL_AGENT のみ発生する。
            state.conversation == ConversationState.TOOL_EXECUTING -> VoicePlateState.WORKING
            else -> VoicePlateState.LISTENING
        }
        voicePlateView?.render(plateState, state)
    }

    private companion object {
        const val TAG = "CarVoiceInteractionSession"
    }
}
