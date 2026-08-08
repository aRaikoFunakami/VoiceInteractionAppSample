package com.example.voiceinteractionappsample.realtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeEventCodecTest {

    @Test
    fun decodeExtractsType() {
        val event = RealtimeEventCodec.decode("""{"type":"session.updated","session":{"id":"sess_1"}}""")

        assertEquals("session.updated", event.type)
        assertEquals("sess_1", event.raw.getJSONObject("session").getString("id"))
    }

    @Test
    fun decodeFallsBackToUnknownWhenTypeMissing() {
        val event = RealtimeEventCodec.decode("""{"foo":"bar"}""")

        assertEquals("unknown", event.type)
    }

    @Test
    fun encodeSessionUpdateWrapsPayload() {
        val session = JSONObject().put("voice", "marin")

        val json = RealtimeEventCodec.encodeSessionUpdate(session)
        val parsed = JSONObject(json)

        assertEquals("session.update", parsed.getString("type"))
        assertTrue(parsed.getJSONObject("session").getString("voice") == "marin")
    }
}
