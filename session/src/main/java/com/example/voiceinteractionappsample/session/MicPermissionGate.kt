package com.example.voiceinteractionappsample.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * RECORD_AUDIO の実行時権限リクエスト(issue #61)。
 *
 * `VoiceInteractionSession` は `Activity` ではないため、通常の
 * `ActivityCompat.requestPermissions()`(内部的に Activity のウィンドウ経由でダイアログを
 * 出す)を直接呼べない。これまでは `adb shell pm grant` による事前付与が必須という運用で
 * 済ませていたが(docs/how-to-run.md 手順3)、実際のユーザーはそれをできない。
 *
 * ここでは最小限のトランポリン Activity([MicPermissionTrampolineActivity])を経由して
 * OS の権限ダイアログを出す。ConversationController(OpenAI)/LocalAgentController の両方が
 * `start()` の冒頭でこれを呼ぶ — RECORD_AUDIO は `:audio` モジュールが宣言する共有パーミッ
 * ションであり、片方のバックエンドだけ直す理由がないため。
 */
object MicPermissionGate {

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // 同時に 1 件しかリクエストしない前提(VoiceInteractionSession は show() ごとに単一の
    // controller しか動かさない)。トランポリン Activity と同一プロセス内でだけやり取りする
    // ため、静的な受け渡しで十分(プロセス跨ぎの通知機構は不要)。
    @Volatile private var pending: CancellableContinuation<Boolean>? = null

    /**
     * 権限を確認し、無ければダイアログを出して結果を待つ。呼び出し元は Main を塞がない
     * ディスパッチャ上で呼ぶこと(ダイアログの応答を待つ間サスペンドする)。
     */
    suspend fun request(context: Context): Boolean {
        if (isGranted(context)) return true
        Log.i(TAG, "requesting RECORD_AUDIO via trampoline")
        return suspendCancellableCoroutine { cont ->
            pending = cont
            context.startActivity(
                Intent(context, MicPermissionTrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            cont.invokeOnCancellation { pending = null }
        }
    }

    internal fun deliverResult(granted: Boolean) {
        val cont = pending ?: return
        pending = null
        if (cont.isActive) cont.resume(granted) { _, _, _ -> }
        Log.i(TAG, "RECORD_AUDIO request result: granted=$granted")
    }

    private const val TAG = "MicPermissionGate"
}
