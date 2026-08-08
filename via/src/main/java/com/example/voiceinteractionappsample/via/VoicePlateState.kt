package com.example.voiceinteractionappsample.via

/**
 * Voice Plate display state only (16節). This is deliberately NOT the same enum family as
 * ConnectionState/AudioInputState/AudioOutputState/ConversationState (:session, Phase 4) —
 * e.g. SPEAKING must never be read as "microphone disabled", the two concerns are separate.
 */
enum class VoicePlateState {
    LISTENING,
    THINKING,
    SPEAKING,
    WORKING,
    ERROR,
}
