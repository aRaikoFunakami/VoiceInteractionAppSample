package com.example.voiceinteractionappsample.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLogicTest {

    @Test
    fun fragmentFilter_rejectsAecResidueFragments() {
        // AEC 残差の典型: 句読点込みで短い
        assertFalse(isAcceptableUtterance("そ。"))
        assertFalse(isAcceptableUtterance("え？"))
        assertFalse(isAcceptableUtterance("。。。"))
        assertFalse(isAcceptableUtterance("  はい "))  // 実質 2 文字
        assertFalse(isAcceptableUtterance(""))
    }

    @Test
    fun fragmentFilter_acceptsRealUtterances() {
        assertTrue(isAcceptableUtterance("こんにちは"))
        assertTrue(isAcceptableUtterance("今日の天気は？"))   // 句読点除去後 6 文字
        assertTrue(isAcceptableUtterance("はい。そう。"))      // 実質 4 文字
    }

    @Test
    fun bargeIn_requiresFourConsecutiveTicks() {
        val d = BargeInDetector(threshold = 4)
        assertFalse(d.tick(true))
        assertFalse(d.tick(true))
        assertFalse(d.tick(true))
        assertTrue(d.tick(true)) // 4 連続目で成立
    }

    @Test
    fun bargeIn_streakResetsOnSilence() {
        val d = BargeInDetector(threshold = 4)
        d.tick(true); d.tick(true); d.tick(true)
        assertFalse(d.tick(false)) // 途切れたらリセット
        d.tick(true); d.tick(true); d.tick(true)
        assertTrue(d.tick(true))   // 改めて 4 連続で成立
    }

    @Test
    fun bargeIn_firesAgainAfterSuccess() {
        val d = BargeInDetector(threshold = 2)
        assertFalse(d.tick(true))
        assertTrue(d.tick(true))
        // 成立後は streak リセット — 次の成立にも再び連続 2 回必要
        assertFalse(d.tick(true))
        assertTrue(d.tick(true))
    }

    @Test
    fun bargeIn_defaultThresholdIsFour() {
        val d = BargeInDetector()
        var fired = 0
        repeat(8) { if (d.tick(true)) fired++ }
        assertEquals(2, fired) // 8 tick で 2 回成立(4 連続 × 2)
    }
}
