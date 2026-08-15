package com.example.voiceinteractionappsample.localagent

/**
 * LocalAgentController のうち JVM 単体テスト可能な純粋ロジック(issue #48)。
 * Android 依存ゼロ — :localagent の src/test で仮想時間なしに検証する。
 */

private val PUNCTUATION = Regex("[。、．，！？!?\\s]")

/**
 * AEC 残差の断片(「そ。」等)をユーザー発話として扱わないためのフィルタ。
 * 句読点・空白を除いた実質文字数が [minChars] 以上なら true。
 */
internal fun isAcceptableUtterance(text: String, minChars: Int = 3): Boolean =
    text.replace(PUNCTUATION, "").length >= minChars

/**
 * ツール実行結果の読み上げ文(issue #50)。2 回目の LLM 往復を省く固定文
 * (ponytail: モデルに結果を語らせる方式はパース漏れ救済経路と整合しないため採らない。
 *  自然な応答が必要になったら ToolResponse を返して再推論する方式に変える)。
 */
internal fun toolConfirmationText(argumentsJson: String, outcome: String, resultCode: String): String {
    val query = runCatching {
        org.json.JSONObject(argumentsJson).optString("query")
    }.getOrDefault("")
    return if (outcome == "SUCCESS" && resultCode == "OPENED" && query.isNotBlank()) {
        "YouTubeで「$query」を検索します"
    } else {
        "すみません、うまく開けませんでした。"
    }
}

/**
 * バージイン判定: 50ms 周期の tick で speech 検出が [threshold] 回連続したら成立。
 * 瞬間ノイズ・AEC 残差での誤発火を防ぐ(4 回 = 200ms 持続)。
 */
internal class BargeInDetector(private val threshold: Int = 4) {
    private var streak = 0

    /** @return この tick でバージイン成立したら true(成立時 streak はリセット)。 */
    fun tick(speechActive: Boolean): Boolean {
        if (!speechActive) {
            streak = 0
            return false
        }
        if (++streak >= threshold) {
            streak = 0
            return true
        }
        return false
    }

    fun reset() {
        streak = 0
    }
}
