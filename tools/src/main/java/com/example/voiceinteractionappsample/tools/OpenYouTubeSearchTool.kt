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
 * 12節/13節: opens a YouTube search via `ACTION_VIEW`, never a browser directly (13節: those
 * are different requirements — a stock browser package isn't hardcoded here). No specific
 * Chrome/browser package is hardcoded (12節).
 */
class OpenYouTubeSearchTool(private val context: Context) : DeviceTool {
    override val name = OpenYouTubeSearchToolSchema.NAME
    override val requiredArgumentFields = setOf("query")

    override suspend fun execute(callId: String, arguments: JSONObject): JSONObject {
        val query = arguments.optString("query", "")
        if (query.isBlank()) return result(YouTubeSearchOutcome.INVALID_ARGUMENT)

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(buildYouTubeSearchUrl(query)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            return result(YouTubeSearchOutcome.NO_HANDLER)
        }

        return try {
            context.startActivity(intent)
            result(YouTubeSearchOutcome.OPENED)
        } catch (e: ActivityNotFoundException) {
            // 12節: 例外をcatchしてOPENEDにしてはならない — この一行がこのクラスで一番重要な
            // 保証。resolveActivity()とstartActivity()の間の競合はNO_HANDLERとして扱う。
            result(YouTubeSearchOutcome.NO_HANDLER)
        }
    }

    private fun result(outcome: YouTubeSearchOutcome): JSONObject =
        JSONObject().put("result", outcome.name)
}

/** query は文字列連結ではなく URI component としてエンコードする（12節）。 internal: unit testable without a Context. */
internal fun buildYouTubeSearchUrl(query: String): String =
    "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8")
