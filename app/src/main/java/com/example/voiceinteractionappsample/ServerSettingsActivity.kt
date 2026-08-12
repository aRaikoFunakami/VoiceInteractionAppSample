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
            settings.localHost = localHost.text.toString().ifBlank { settings.localHost }
            Toast.makeText(this, R.string.server_settings_saved, Toast.LENGTH_SHORT).show()
        }
    }
}
