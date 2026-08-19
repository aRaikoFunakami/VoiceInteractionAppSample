package com.example.voiceinteractionappsample

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.voiceinteractionappsample.localagent.LocalAgentRuntime
import com.example.voiceinteractionappsample.realtime.ConversationLanguage
import com.example.voiceinteractionappsample.realtime.RealtimeServerMode
import com.example.voiceinteractionappsample.realtime.RealtimeServerSettings
import kotlinx.coroutines.launch

/**
 * Reachable from Settings > Apps > Default apps > Digital assistant app's gear icon
 * (`android:settingsActivity` on `via`'s `interaction_service.xml`, issue #43). Must work
 * standalone — Settings launches it as a plain explicit intent, with no VoiceInteractionSession
 * bound — so it only touches [RealtimeServerSettings], never :via or :session.
 *
 * Compose/Material3 (issue #71) — was a fixed-sp XML layout that didn't adapt to large screens.
 */
class ServerSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = RealtimeServerSettings(this)
        setContent {
            ServerSettingsTheme {
                ServerSettingsScreen(
                    settings = settings,
                    modelsAvailable = LocalAgentRuntime::modelsAvailable,
                )
            }
        }
    }
}

@Composable
private fun ServerSettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(
            primary = colorResource(R.color.purple_200),
            secondary = colorResource(R.color.teal_200),
        )
        else -> lightColorScheme(
            primary = colorResource(R.color.purple_500),
            secondary = colorResource(R.color.teal_700),
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
private fun ServerSettingsScreen(
    settings: RealtimeServerSettings,
    modelsAvailable: () -> Boolean,
) {
    var mode by remember { mutableStateOf(settings.mode) }
    var hostInput by remember { mutableStateOf(settings.localHost) }
    var language by remember { mutableStateOf(settings.language) }
    // LOCAL_AGENT はオンデバイス動作でホスト設定を使わない (#47)
    val hostEnabled = mode != RealtimeServerMode.LOCAL_AGENT

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 保存直後のトースト表示は視認性が低かった(#71 fb) — Scaffoldの Snackbar に切り替える。
    val savedMessage = stringResource(R.string.server_settings_saved)
    val savedModelsMissingMessage = stringResource(R.string.server_settings_saved_models_missing)

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(innerPadding)) {
            // 大画面(タブレット等)では幅を絞って中央寄せし、見出しを一段大きくする — 端末サイズが
            // 上がってもテキストが相対的に小さく見えていた問題への対応 (issue #71)。
            val wide = maxWidth > 600.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 0.dp else 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val contentWidth = if (wide) Modifier.widthIn(max = 560.dp) else Modifier.fillMaxWidth()
                Column(contentWidth) {
                    Text(
                        text = stringResource(R.string.server_settings_title),
                        style = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 20.dp),
                    )

                    // ホスト欄は OpenAI/Local 接続にしか使わない (#47) ので、Connect to a server
                    // のグループ内に含める — Run without a server の下に置くのは意味的に誤りだった
                    // (#71 fb)。
                    SettingsSection(title = stringResource(R.string.server_settings_section_server)) {
                        ModeOption(
                            label = stringResource(R.string.server_settings_mode_openai),
                            selected = mode == RealtimeServerMode.OPENAI,
                            onClick = { mode = RealtimeServerMode.OPENAI },
                        )
                        HorizontalDivider()
                        ModeOption(
                            label = stringResource(R.string.server_settings_mode_local),
                            selected = mode == RealtimeServerMode.LOCAL,
                            onClick = { mode = RealtimeServerMode.LOCAL },
                        )
                        HorizontalDivider()
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text(stringResource(R.string.server_settings_local_host_hint)) },
                            enabled = hostEnabled,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    SettingsSection(title = stringResource(R.string.server_settings_section_ondevice)) {
                        ModeOption(
                            label = stringResource(R.string.server_settings_mode_local_agent),
                            selected = mode == RealtimeServerMode.LOCAL_AGENT,
                            onClick = { mode = RealtimeServerMode.LOCAL_AGENT },
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // 会話言語は3モード共通(instructions / STT / TTS に反映)。
                    SettingsSection(title = stringResource(R.string.server_settings_section_language)) {
                        ModeOption(
                            label = stringResource(R.string.server_settings_language_ja),
                            selected = language == ConversationLanguage.JA,
                            onClick = { language = ConversationLanguage.JA },
                        )
                        HorizontalDivider()
                        ModeOption(
                            label = stringResource(R.string.server_settings_language_en),
                            selected = language == ConversationLanguage.EN,
                            onClick = { language = ConversationLanguage.EN },
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = {
                            settings.mode = mode
                            settings.language = language
                            // issue #43: RealtimeServerSettings interpolates this straight into
                            // "http://$host:port/...". Users are likely to paste a full URL they
                            // saw in a server's own startup log (e.g. "http://10.0.2.2:8765")
                            // rather than typing a bare host — strip scheme/path/port so that
                            // doesn't produce a malformed double-port URL. Blank input leaves the
                            // saved host untouched.
                            val sanitizedHost = sanitizeHost(hostInput)
                            if (sanitizedHost.isNotEmpty()) {
                                settings.localHost = sanitizedHost
                            }
                            // issue #48: モデル未配置のまま LOCAL_AGENT を選んでも保存は許可する
                            // (後から push できる)が、気づけるように警告する。
                            val message = if (mode == RealtimeServerMode.LOCAL_AGENT && !modelsAvailable()) {
                                savedModelsMissingMessage
                            } else {
                                savedMessage
                            }
                            scope.launch { snackbarHostState.showSnackbar(message) }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.server_settings_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.selectableGroup(), content = content)
        }
    }
}

@Composable
private fun ModeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun sanitizeHost(input: String): String =
    input.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore("/")
        .substringBefore(":")
