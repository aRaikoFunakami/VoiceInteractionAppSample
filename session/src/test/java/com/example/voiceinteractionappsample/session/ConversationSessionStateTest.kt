package com.example.voiceinteractionappsample.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSessionStateTest {

    @Test
    fun bargeInCombinationOfCapturingAndPlayingIsNormalNotAnError() {
        // 10節: 正常なbarge-in中は AudioInputState=CAPTURING かつ AudioOutputState=PLAYING が
        // 成立する。これを不正状態として扱ってはならない — つまりこの組み合わせを構築しても
        // 例外にならず、両方の値がそのまま保持されることを確認する。
        val state = ConversationSessionState(
            connection = ConnectionState.CONNECTED,
            audioInput = AudioInputState.CAPTURING,
            audioOutput = AudioOutputState.PLAYING,
            conversation = ConversationState.USER_SPEAKING,
        )

        assertEquals(AudioInputState.CAPTURING, state.audioInput)
        assertEquals(AudioOutputState.PLAYING, state.audioOutput)
    }

    @Test
    fun defaultStateIsFullyDisconnectedAndIdle() {
        val state = ConversationSessionState()

        assertEquals(ConnectionState.DISCONNECTED, state.connection)
        assertEquals(AudioInputState.STOPPED, state.audioInput)
        assertEquals(AudioOutputState.IDLE, state.audioOutput)
        assertEquals(ConversationState.IDLE, state.conversation)
    }
}
