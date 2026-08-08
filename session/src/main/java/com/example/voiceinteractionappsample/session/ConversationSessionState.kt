package com.example.voiceinteractionappsample.session

/** WebRTC/Realtime connection lifecycle. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

/** Microphone capture state — independent of [AudioOutputState] (10節). */
enum class AudioInputState {
    STOPPED,
    CAPTURING,
    ERROR,
}

/** Assistant audio playback state — independent of [AudioInputState] (10節). */
enum class AudioOutputState {
    IDLE,
    PLAYING,
    ERROR,
}

/** Turn-taking state, orthogonal to the audio I/O states above. */
enum class ConversationState {
    IDLE,
    USER_SPEAKING,
    MODEL_PROCESSING,
    TOOL_EXECUTING,
    CANCELLING,
}

/**
 * The four state axes held together (10節). Deliberately NOT one combined enum — full-duplex
 * barge-in requires [AudioInputState.CAPTURING] and [AudioOutputState.PLAYING] to be true
 * *simultaneously*, and a single enum can't represent that without an invalid-looking
 * cross-product state. This holder places no restriction on which combinations are valid:
 * CAPTURING + PLAYING at once is the normal barge-in case, not an error (verified below).
 */
data class ConversationSessionState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val audioInput: AudioInputState = AudioInputState.STOPPED,
    val audioOutput: AudioOutputState = AudioOutputState.IDLE,
    val conversation: ConversationState = ConversationState.IDLE,
)
