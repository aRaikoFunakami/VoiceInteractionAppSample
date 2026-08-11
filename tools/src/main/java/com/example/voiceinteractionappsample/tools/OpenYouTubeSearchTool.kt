package com.example.voiceinteractionappsample.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import org.json.JSONObject

/** 12節: the only outcomes this tool ever reports. */
enum class YouTubeSearchOutcome { OPENED, NO_HANDLER, INVALID_ARGUMENT, FAILED }

/**
 * 12節/13節: opens a YouTube search in Chrome.
 *
 * 実機で発見: このAAOSイメージにはブラウザが無く（実車と同様の設計）、`ACTION_VIEW`の
 * implicit intentはAAOSのintent解決ポリシーにより一般アプリが除外され、常にLink Viewer
 * （QRコード）にしか着地しなかった。しかしコンポーネントを明示指定すれば
 * （`setClassName`）この除外は効かず、実際に起動できることを実機で確認した — その代わり
 * Chromeがこの端末にインストールされている必要がある（ユーザー要望によりこれを前提とする。
 * 素のAAOSイメージには入っていないので `adb install` で別途入れること、docs参照）。
 */
class OpenYouTubeSearchTool(private val context: Context) : DeviceTool {
    override val name = OpenYouTubeSearchToolSchema.NAME
    override val requiredArgumentFields = setOf("query")

    override suspend fun execute(callId: String, arguments: JSONObject): JSONObject {
        val query = arguments.optString("query", "")
        if (query.isBlank()) return result(YouTubeSearchOutcome.INVALID_ARGUMENT)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildYouTubeSearchUrl(query))).apply {
            setClassName(CHROME_PACKAGE, CHROME_MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            result(YouTubeSearchOutcome.OPENED)
        } catch (e: ActivityNotFoundException) {
            // 12節: 例外をcatchしてOPENEDにしてはならない — この一行がこのクラスで一番重要な
            // 保証。Chromeが入っていない端末ではここに来る（NO_HANDLER）。
            result(YouTubeSearchOutcome.NO_HANDLER)
        }
    }

    private fun result(outcome: YouTubeSearchOutcome): JSONObject =
        JSONObject().put("result", outcome.name)

    private companion object {
        // 実機で確認済み: com.google.android.apps.chrome.Main が実体のメインactivity。
        const val CHROME_PACKAGE = "org.chromium.chrome"
        const val CHROME_MAIN_ACTIVITY = "com.google.android.apps.chrome.Main"
    }
}

/** query は文字列連結ではなく URI component としてエンコードする（12節）。 internal: unit testable without a Context. */
internal fun buildYouTubeSearchUrl(query: String): String =
    "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
