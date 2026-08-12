package com.example.voiceinteractionappsample

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.voiceinteractionappsample.realtime.RealtimeServerMode
import com.example.voiceinteractionappsample.realtime.RealtimeServerSettings

/**
 * Reachable from Settings > Apps > Default apps > Digital assistant app's gear icon
 * (`android:settingsActivity` on `via`'s `interaction_service.xml`, issue #43). Must work
 * standalone — Settings launches it as a plain explicit intent, with no VoiceInteractionSession
 * bound — so it only touches [RealtimeServerSettings], never :via or :session.
 */
class ServerSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server_settings)

        val settings = RealtimeServerSettings(this)
        val modeGroup = findViewById<RadioGroup>(R.id.mode_group)
        val localHost = findViewById<EditText>(R.id.local_host)

        modeGroup.check(if (settings.mode == RealtimeServerMode.LOCAL) R.id.mode_local else R.id.mode_openai)
        localHost.setText(settings.localHost)

        findViewById<android.widget.Button>(R.id.save_button).setOnClickListener {
            settings.mode = if (modeGroup.checkedRadioButtonId == R.id.mode_local) {
                RealtimeServerMode.LOCAL
            } else {
                RealtimeServerMode.OPENAI
            }
            // issue #43: RealtimeServerSettings interpolates this straight into "http://$host:port/...".
            // Users are likely to paste a full URL they saw in a server's own startup log (e.g.
            // "http://10.0.2.2:8765") rather than typing a bare host — strip scheme/path/port so
            // that doesn't produce a malformed double-port URL. Blank input leaves the saved host
            // untouched rather than writing a no-op self-assignment back to it.
            val sanitizedHost = sanitizeHost(localHost.text.toString())
            if (sanitizedHost.isNotEmpty()) {
                settings.localHost = sanitizedHost
            }
            Toast.makeText(this, R.string.server_settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeHost(input: String): String =
        input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
}
