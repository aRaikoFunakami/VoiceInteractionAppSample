# 会話言語(JA/EN)切り替え

設定画面の **Conversation language**(JA/EN)は、OpenAI Realtime / Local(OpenAI 互換ブローカー)/
On-device Local Voice Agent の3モード全てに適用される。デフォルトは JA(既存挙動を維持)。

![会話言語(JA/EN)切り替えロジック。設定画面で選んだ言語が RealtimeServerSettings に保存され、次回PTT時に createController() が読み込んで、OPENAI/LOCALモード用の ConversationController と LOCAL_AGENTモード用の LocalAgentController それぞれの下流(instructions・STT言語・TTS言語・固定文言)に伝播する。SenseVoice STT だけは元々多言語モデルのため切替対象外で常に自動判定。](images/conversation-language-flow.svg)

## 設定の保存と反映タイミング

- [`RealtimeServerSettings.language`](../realtime/src/main/java/com/example/voiceinteractionappsample/realtime/RealtimeServerSettings.kt) に `ConversationLanguage`(`JA` / `EN`)として SharedPreferences に永続化する。`mode` と同じ仕組み(`enum.name` を文字列保存、パース失敗時は JA にフォールバック)。
- 反映されるのは**次回 PTT 押下時**。[`CarVoiceInteractionSession.createController()`](../via/src/main/java/com/example/voiceinteractionappsample/via/CarVoiceInteractionSession.kt) が `onShow()` ごとにコントローラを作り直す既存設計(issue #43)に相乗りしているため、設定画面で保存した直後に会話中のセッションへ即時反映されるわけではない。

## OpenAI Realtime / Local(OpenAI 互換)モード

`mode = OPENAI` と `mode = LOCAL` は同じ [`ConversationController`](../session/src/main/java/com/example/voiceinteractionappsample/session/ConversationController.kt) を使う(差はブローカー/Realtime calls の接続先だけ)。`language` はコンストラクタ引数として渡され、以下3箇所に反映される。

| 反映先 | 内容 |
|---|---|
| `session.update` の `instructions` | 車載アシスタント人格プロンプトを JA/EN で丸ごと切り替え(`carAssistantInstructions(language)`)。Realtime API には出力言語を直接指定するフィールドが無いため、instructions で明示するのが標準的なやり方(既存実装からの方針を踏襲)。 |
| `session.update` の `audio.input.transcription.language` | Whisper の言語ヒントを `language.code`(`"ja"` / `"en"`)にする。 |
| 固定挨拶(`assistantTranscript` 初期表示) | `greetingText(language)` で JA/EN の文言を切り替え。 |

## On-device Local Voice Agent モード

`mode = LOCAL_AGENT` は [`LocalAgentController`](../localagent/src/main/java/com/example/voiceinteractionappsample/localagent/LocalAgentController.kt) が使う。`language` はセッション開始時(`start()`)に以下へ反映される。

| 反映先 | 内容 |
|---|---|
| Gemma 4 E2B(LiteRT-LM)の system instruction | `LocalAgentRuntime.llm.resetConversation(language)` が会話履歴リセットと同時にシステム指示(短い話し言葉で答える指示 + YouTube 検索ツールの利用指示)を JA/EN で切り替える。 |
| Supertonic TTS の合成言語 | `LocalAgentRuntime.ttsEngine.lang` に `"ja"` / `"en"` をセットし、`generateWithConfig` の `extra["lang"]` に渡す。 |
| SenseVoice STT | **切替なし。** `language = "auto"` 固定(下記「多言語モデルであることの確認」参照)。 |
| 固定挨拶・フォールバック文言 | `greetingText(language)` / `fallbackText(language)`。 |

## 多言語モデルであることの確認

ローカル3コンポーネントは、コード中のコメント(`supertonic-3-ja`、「日本語対応」等)が日本語ユースケース寄りの書き方をしているため一見 JA 専用に見えるが、実際に使用しているモデル自体は多言語対応である。

| コンポーネント | 使用モデル | 根拠 |
|---|---|---|
| LLM | Gemma 4 E2B(LiteRT-LM) | 140 言語以上で事前学習、35 言語以上を公式サポート([Gemma 4 model card](https://ai.google.dev/gemma/docs/core/model_card_4))。 |
| STT | sherpa-onnx SenseVoice `zh-en-ja-ko-yue-int8-2024-07-17` | モデル名の通り中国語/英語/日本語/韓国語/広東語対応。`language` を `"ja"` 固定にしていた既存実装を `"auto"` に変更し、モデル自身の自動判別に任せている(言語ごとに作り直せない lazy シングルトンのため)。 |
| TTS | sherpa-onnx **Supertonic v3**(`sherpa-onnx-supertonic-3-tts-int8-2026-05-11`) | v3 で 31 言語対応に拡張済み(英語・日本語含む)。`generateWithConfig` の `extra["lang"]` はこの言語切り替え用パラメータで、`"en"` も有効値([SupertonicTTS — sherpa docs](https://k2-fsa.github.io/sherpa/onnx/tts/supertonic.html)、[Supertonic v3 リリースノート](https://www.kiadev.net/news/2026-05-15-supertonic-v3-release))。 |

これはモデルカード/ドキュメント上の確認であり、実機上での動作検証(英語合成の自然さ、英語認識精度、英語応答の質)はまだ行っていない。`scripts/fetch_gemma.sh` / `scripts/fetch_stt_models.sh` / `scripts/fetch_supertonic.sh` で取得したモデルに対し、EN 設定で一度実際に発話して確認することを推奨する。
