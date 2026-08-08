package com.example.voiceinteractionappsample.via

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * Minimal Voice Plate (16節): shows only the conversation display state
 * (Listening/Thinking/Speaking/Working/Error). Developer-facing microphone/output state is
 * diagnostic-build only (:diagnostics) and does not belong on this view.
 */
class VoicePlateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    init {
        textSize = 24f
        setPadding(48, 48, 48, 48)
    }

    fun setState(state: VoicePlateState) {
        text = state.name
    }
}
