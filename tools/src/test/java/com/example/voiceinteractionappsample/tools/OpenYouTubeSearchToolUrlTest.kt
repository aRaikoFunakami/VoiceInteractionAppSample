package com.example.voiceinteractionappsample.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenYouTubeSearchToolUrlTest {

    @Test
    fun encodesSpacesAndSpecialCharacters() {
        val url = buildYouTubeSearchUrl("Queen live & loud")

        assertEquals("https://www.youtube.com/results?search_query=Queen+live+%26+loud", url)
    }

    @Test
    fun encodesNonAsciiCharacters() {
        val url = buildYouTubeSearchUrl("クイーン ライブ")

        // 12節: 文字列連結ではなくURI componentとしてencodeする — 生の日本語や空白がそのまま
        // URLに残っていないことを確認する。
        assertEquals(
            "https://www.youtube.com/results?search_query=" +
                java.net.URLEncoder.encode("クイーン ライブ", "UTF-8"),
            url,
        )
    }

    @Test
    fun encodesQueryContainingUrlLikeText() {
        // 攻撃的な入力でもURL構造を壊さないこと（追加のクエリパラメータを注入できない）。
        val url = buildYouTubeSearchUrl("test&search_query=injected")

        assertEquals(
            "https://www.youtube.com/results?search_query=test%26search_query%3Dinjected",
            url,
        )
    }
}
