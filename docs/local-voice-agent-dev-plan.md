# Local Voice Agent 対応 開発計画書

VIA アプリのバックエンドとして、既存の OpenAI Realtime API (WebRTC) に加え、完全オンデバイスの
local voice agent を選択できるようにする。本書は 3 リポジトリの全ソース調査 + 3 系統の独立レビュー
(事実整合性・AAOS 固有リスク・過剰設計/テスト容易性) + AAOS エミュレータでの実機容量検証に基づく
開発Ready の計画である。

- 本リポジトリ: AAOS VIA アプリ(OpenAI Realtime / WebRTC)
- `/Users/raiko.funakami/GitHub/libwebrtc/src` (`local-audio` ブランチ): WebRTC APM(AEC3/NS/AGC2)単体ラッパー `liblocal_audio_engine.so`
- `/Users/raiko.funakami/GitHub/android-local-voice-agent`: オンデバイス音声エージェントの動作実績あるリファレンス実装

---

## 1. スコープ

### やること(v1)

1. 設定画面に第 3 の選択肢 `LOCAL_AGENT` を追加(既存 OPENAI / LOCAL は無変更)
2. バックエンド差し替えの seam `VoiceSessionController` を導入(既存パスの挙動は不変)
3. 新モジュール `:localagent` にサンプル実装を移植し、PTT → オンデバイス音声会話(STT→LLM→TTS、バージイン対応)を Voice Plate 上で成立させる
4. ツールコール(`open_youtube_search`)を LiteRT-LM のネイティブツール機能で接続(Phase 5、スパイク合格が条件)

### やらないこと(スコープ外と明記)

- モデルのプロダクト配布(OTA 等)。開発・デモは `adb push` + `/data/local/tmp` 方式を踏襲。
  ただし priv-app/platform 署名で配布する将来に備え、モデルパス取得を 1 箇所の関数に集約しておく(§4)
- x86_64 向け `liblocal_audio_engine.so` のビルド(必要になったら既存 Docker ツールチェーンで `target_cpu="x64"` を追加ビルド)
- 実車スピーカー→マイク音響経路での AEC チューニング(既存 OpenAI モードと同じ未解決項目。`docs/aec-device-profiles.md` の Tests A–E を LOCAL_AGENT にも適用する、までが本計画)
- 実 SoC(車載ヘッドユニット相当)でのスループット最終確認(Phase 0/4 でゲートは置くが、実機入手までは host CPU 実測値が唯一の根拠)
- 多言語対応(全スタック日本語固定。サンプル同様)

---

## 2. 前提となる調査確定事項

### 2.1 バックエンド構成(サンプルの実績値)

```
AudioRecord(VOICE_RECOGNITION, 48k/mono/int16, READ_BLOCKING 480サンプル)
  → liblocal_audio_engine.so (WebRTC APM: AEC3 + NS + AGC2)
  → Silero VAD v5 + SenseVoice int8 (sherpa-onnx 1.13.5, ja)
  → Gemma 4 E2B (.litertlm, LiteRT-LM 0.16.0, CPU)
  → supertonic-3-ja int8 (sherpa-onnx TTS) → 48k リサンプル
  → AudioTrack(WRITE_BLOCKING) → processRender(AEC 参照信号)
```

実測(**エミュレータの host CPU 上**): LLM 応答 0.7〜1.1 秒、TTS RTF≈1.1、ERLE 33.8dB(合成エコー)、
10 分間アンダーラン 0、バージイン成功。**実 SoC での再測定は未実施**(§7 R13)。

### 2.2 ライブラリ検証結果(ローカルバイナリ検査済み)

| 項目 | 結果 |
|---|---|
| sherpa-onnx 1.13.5 AAR | 全 4 ABI 同梱(arm64/x86_64/v7a/x86)。minSdk 21。`libsherpa-onnx-c-api.so`/`-cxx-api.so` は Kotlin から未使用 → packaging excludes で 4.7MiB 削減可 |
| litertlm-android 0.16.0 | **Google Maven**(Central にはない)。arm64 + x86_64 同梱。minSdk 24。0.16.0 が最新。推移的依存: gson 2.13.2, kotlin-reflect 2.2.21, coroutines 1.9.0(**VIA と同一バージョン、衝突なし**)。`Conversation.cancelProcess()` / `sendMessageAsync(): Flow<Message>` 実在確認済み(§3.4 で使用) |
| LiteRT-LM ツールコール | **0.16.0 に搭載済み**: `Tool`/`ToolSet`/`ToolProvider`/`ConversationConfig(tools=…, automaticToolCalling=…)` + `ResponseFormat.json(schema)`/`regex()` 制約付きデコード |
| liblocal_audio_engine.so | 843KB、**arm64-v8a のみ**。エクスポートは `JNI_OnLoad` の 1 シンボルだけ。JNI バインド先クラスパス **`com/example/localvoiceagent/LocalAudioEngine` がバイナリにハードコード**(改名すると無音で失敗 → FQCN 維持が必須) |
| SONAME 衝突 | 7 つの .so すべて別名・別 JNI 名前空間。`pickFirst` 等は不要 |
| APK サイズ影響 | arm64 lean 構成で +46.6MiB(raw)/+18.6MiB(download 相当)。モデルは APK 非同梱なので影響外 |

### 2.3 VIA 側の統合ポイント(確定)

- `CarVoiceInteractionSession` が controller に要求するのは **`state: StateFlow<ConversationSessionState>` / `suspend start()` / `cancel()`** の 3 点 + ctor の `onAutoTerminated` コールバック契約(非 USER_CANCEL 時のみ、バックグラウンドディスパッチャから呼ばれる)。
- **`CarVoiceInteractionSession.scope` は `Dispatchers.Main`。`controller.start()`/`cancel()` はこのスコープから直接 `launch` される**(View 操作のため Main が必須)。既存 `ConversationController` は内部の全ブロッキング処理を自前で `withContext(Dispatchers.IO)` に逃がしているため問題化していないだけで、`suspend fun` であること自体は Main 回避を保証しない。**`LocalAgentController` は自分の責任でこれをやる必要がある**(§3.5)。
- `RealtimeServerMode` 追加時にコンパイラが強制する変更箇所: `RealtimeServerSettings.kt` の 2 つの exhaustive `when`。強制されないが必要: `ServerSettingsActivity` の 2 つの二値 if/else、レイアウトの RadioButton、strings.xml。
- `:diagnostics` は `RealtimeServerSettings` を一切参照しない → enum 追加でコンパイルは壊れない(ただし `checkBackendReachable()` は既存 LOCAL モードの時点で OpenAI 固定という既知バグあり)。
- ビルド: AGP 9.3.1 + **AGP 組み込み Kotlin(KGP 不使用)**、compileSdk 37 / minSdk 33 / Java 11、`abiFilters = [arm64-v8a, x86_64]`。新モジュールは既存 library モジュールの build ファイルをテンプレートにする。
- **確認済み・非リスク**: (a) AAOS マルチユーザーは `/data/local/tmp` の読み取りに影響しない(グローバル領域でユーザー非依存)。(b) `:via` は音声に一切触れず(`StubRecognitionService` は起動時に即エラーを返すだけでマイクを握らない)、WebRTC ADM(`VOICE_COMMUNICATION`)と本計画の `AudioRecord(VOICE_RECOGNITION)` は seam により排他利用されるため競合しない。

### 2.4 ビルド上の罠(事前確定)

1. **library モジュールにローカル AAR を直接依存させると AGP がエラーにする**
   (「Direct local .aar file dependencies are not supported when building an AAR」)。
   → 対策: `:localagent` は `compileOnly(files("libs/sherpa-onnx-1.13.5.aar"))`、
   `:app` 側に `implementation(files("../localagent/libs/sherpa-onnx-1.13.5.aar"))` を置いてランタイム同梱する。
   デコンパイル調査済み: AGP 9.3.1 のこのチェックは `runtimeClasspath` のみを見るため `compileOnly` は該当しない。
   sherpa AAR は `res/` を持たない(空の `R.txt`)ため、このワークアラウンドでよくある「リソース欠落」問題も起きない。
2. `.aar` と `.so` は **最初の Gradle sync 前に存在が必要**(`Null extracted folder for artifact` で失敗)。fetch スクリプトを README 手順の先頭に置く。
3. litertlm は Google Maven 配布。VIA の `settings.gradle.kts` の `dependencyResolutionManagement.repositories` に `google()` の content filter は掛かっていない(filter は `pluginManagement` のみ)ため追加作業不要 — 確認済み。

---

## 3. アーキテクチャ設計

### 3.1 seam: `VoiceSessionController`(`:session` に新設)

```kotlin
package com.example.voiceinteractionappsample.session

interface VoiceSessionController {
    val state: StateFlow<ConversationSessionState>
    suspend fun start()
    suspend fun cancel(reason: DisconnectReason = DisconnectReason.USER_CANCEL)
}
```

**「`override` を付けるだけで実装変更なし」ではない — 1 箇所の削除が必須。**
Kotlin は override 側でデフォルト引数を再宣言できない。`ConversationController.cancel()` の
`= DisconnectReason.USER_CANCEL` を削除し、`override suspend fun cancel(reason: DisconnectReason)` にする。
デフォルト値はインターフェース側にのみ存在すればよく、既存の無引数呼び出し(`CarVoiceInteractionSession.kt:137`
ほか)は問題なく解決される(インターフェースの override はシグネチャ経由でデフォルトを継承する)。
`state` と `start()` はそのまま `override` を付けるだけで良い。

`CarVoiceInteractionSession.createController(): VoiceSessionController` に広げ、`RealtimeServerSettings.mode` で分岐:

```kotlin
private fun createController(): VoiceSessionController {
    val serverSettings = RealtimeServerSettings(context)
    return when (serverSettings.mode) {
        RealtimeServerMode.LOCAL_AGENT -> LocalAgentController(
            context = context,
            toolExecutor = DeviceToolExecutor(listOf(OpenYouTubeSearchTool(context))),
            onAutoTerminated = { reason -> scope.launch { hide() } },
        )
        else -> ConversationController(/* 既存どおり */)
    }
}
```

- `onShow`/`onHide`/`updateVoicePlate` は無変更で動く(seam の 3 メンバーしか使っていないことをコード横断で確認済み)。
- **toggle-to-stop ガードは既存 OpenAI パスにも潜んでいた latch バグごと Phase 2 で直す**(§3.5 の FAILED 状態の項、§6 Phase 2 参照)。
- ついでに直す既存の軽微リーク: `onShow()` の `state.onEach{}.launchIn(scope)` が PTT ごとにコレクタを増やす → `Job` を保持して張り替える。

### 3.2 モジュール構成

```
:app  → :via → :session (api) → :realtime, :audio, :tools
      → :localagent (implementation) ← :app が直接依存(理由は §3.6)
:via  → :localagent (implementation) ← 新規
```

依存方向: `:localagent → :session`。循環なし(確認済み)。

### 3.3 `:localagent` の内部構成(サンプルからの移植 + 新規クラス)

| ファイル | 由来 | 修正点 |
|---|---|---|
| `com/example/localvoiceagent/LocalAudioEngine.kt` | 移植 | **パッケージ名をこの FQCN のまま維持**(JNI 契約)。それ以外無変更 |
| `audio/CapturePipeline.kt` | 移植 | ① `preProcess`/dump 系(`enableDump`, `dumpQueue`, `DumpEntry`, `startDumpWriter`, writer スレッド)を**丸ごと削除**(生音声の永続化は `docs/acceptance-checklist.md` #18 違反。呼び出し元だった `MainActivity` は移植対象外なのでデッドコードでもある)。②`engineHandle` を `@Volatile`化(64bit tearing 対策。破棄競合そのものは §3.5 の停止順序で解消済み)。③ `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)` を capture スレッド先頭に追加。④ ループ本体を `try/catch(Throwable)` で囲み、例外時は `readErrors` 相当のフラグを立てて継続(未捕捉例外はプロセス kill を招く) |
| `audio/RenderPipeline.kt` | 移植 | ①`onFramePlayed` を削除(dump 用、デッドコード)。②`AudioAttributes` を `USAGE_MEDIA` → **`USAGE_ASSISTANT`** に変更。③スレッド優先度同上。④`stop()` が `thread?.join(2000)` でタイムアウトした場合、**`destroy()` を呼ばず生存させたまま返す**(タイムアウト時は audioserver 系トラブルで対向スレッドがまだ native ハンドルを触っている可能性があるため、破棄よりリークを選ぶ) |
| `stt/SpeechRecognizer.kt` / `stt/SenseVoiceRecognizer.kt` | 移植 | ①`vad`/`recognizer` へのアクセスを `synchronized` で保護(sherpa の `Vad` はスレッドセーフでない。`reset()`/`close()` が worker スレッドの処理と競合しうる)。②`warmUp()` を追加(`vad`/`recognizer` の遅延初期化を先出しし、初回発話でのフレーム欠落を防ぐ)。③worker ループを `try/catch(Throwable)` で保護 |
| `tts/SpeechSynthesizer.kt` / `tts/SupertonicTts.kt` | 移植 | `SupertonicTts` に `warmUp()`(捨て合成 1 回で `OfflineTts` の遅延初期化を先出し)を追加。それ以外無変更 |
| `tts/TtsPlayer.kt` | 移植 | 無変更。ただしプロセス常駐のまま使うため、**セッション開始のたびに防御的に `cancel()` してから使う**(§3.5)— 前セッションの取り残しフレームが新セッションで再生される事故を防ぐ |
| `LlmEngine.kt` | 移植 | `ask()` の呼び出しを `Mutex` で直列化(`resetConversation()`/`close()` と `sendMessage()` が別スレッドから同時に native ハンドルへ触れないようにする)。Phase 5 でツールコール拡張(§5) |
| `LocalAgentRuntime.kt` | **新規** | プロセス生存のシングルトン(§3.4) |
| `LocalAgentController.kt` | **新規**(サンプルの ConversationController を土台に再設計) | §3.5 |

移植しないもの: `MainActivity.kt`(デバッグハーネス)、`WavWriter.kt`、`SherpaRuntime.kt`(不要)、
`AudioFrameBuffer`(libwebrtc 側。READ_BLOCKING 方式で不使用のため)。

**テスト容易性のための設計方針**: `LocalAgentController` は `LocalAgentRuntime` の各コンポーネントを
直接参照せず、コンストラクタで受け取る(デフォルト引数として `LocalAgentRuntime` を渡す)。

```kotlin
class LocalAgentController(
    private val context: Context,
    private val stt: SpeechRecognizer = LocalAgentRuntime.stt,
    private val ttsPlayer: TtsPlayer = LocalAgentRuntime.ttsPlayer,
    private val ask: suspend (String) -> String = { LocalAgentRuntime.llm.ask(it) },
    ...
)
```

`CapturePipeline`/`RenderPipeline` は `LocalAgentController` の**コンストラクタで**(`start()` の中ではなく)
生成し、`onCleanFrame`/`fillFrame`/`engineHandle` をそこで配線する。両クラスのコンストラクタは
`AudioRecord`/`AudioTrack` に触れない(実際に触れるのは `.start()` 呼び出し時のみ、サンプル実装で確認済み)ため、
**JVM 単体テストで `LocalAgentController` を素朴に構築しても Android フレームワーク呼び出しは発生しない**。
これにより、断片フィルタとバージイン判定(下記)を `start()`/`capture.start()` を一切呼ばずに直接テストできる
(内部処理を `internal suspend fun onUtterance(text: String)` / `internal fun watchdogTick()` として公開する)。
`onCleanFrame` は `CapturePipeline` の **`private val` コンストラクタ引数であり後から代入できない**ため、
サンプルの「`start()` 後に配線」という順序は踏襲しない — 上記のとおり構築時に確定させる。

### 3.4 `LocalAgentRuntime` — エンジンのプロセススコープ管理(新規・重要)

**理由**: `LlmEngine.initialize()` は約 10 秒ブロックする。VIA は PTT ごとに controller を作り直す設計
(設定の即時反映のため)なので、エンジンを controller に持たせると PTT ごとに 10 秒待ちになる。

```kotlin
object LocalAgentRuntime {
    val llm: LlmEngine
    val stt: SenseVoiceRecognizer      // stt-worker スレッドは生成時から常駐(サンプル仕様)
    val ttsEngine: SupertonicTts
    val ttsPlayer: TtsPlayer

    // companion static のみを呼ぶ。インスタンス(stt/ttsEngine/llm)を一切構築・参照しない
    // (:app の設定画面から呼ばれるため、うっかり呼ぶと設定画面を開いただけで sherpa の
    //  重量級コンストラクタが stt-worker スレッド上で走り出す)
    fun modelsAvailable(): Boolean =
        LlmEngine.modelAvailable() && SenseVoiceRecognizer.modelAvailable() && SupertonicTts.modelAvailable()
    fun engineLoaded(): Boolean = LocalAudioEngine.loaded

    private val initDeferred by lazy {
        runtimeScope.async(Dispatchers.IO) {
            llm.initialize()
            stt.warmUp()
            ttsEngine.warmUp()
        }
    }
    // Deferred をキャッシュしてキャンセル安全にする: 呼び出し元コルーチンがキャンセルされても
    // ロード処理自体は継続し、二重ロードやハンドルの二重生成を起こさない(§3.5 の generation 機構と対)
    suspend fun ensureInitialized() = initDeferred.await()
}
```

- 全て lazy。LOCAL_AGENT モードを一度も使わなければ何もロードしない(OPENAI モードへの影響ゼロ)。
- **`ensureInitialized()` はキャンセル安全**: `async` で開始したロード処理は `await()` 側がキャンセルされても
  続行される。呼び出し元コルーチンが割り込まれても、二重初期化やネイティブハンドルの多重生成が起きない
  (PTT を素早く連打しても安全)。
- **`llm.resetConversation()` は `Mutex` の内側でのみ呼ぶ**(`LlmEngine` 内で直列化。`ask()` 実行中に
  `resetConversation()`/`close()` が同時に native ハンドルへ触れると use-after-free の恐れがある)。
- **メモリ解放パス**: `ComponentCallbacks2.onTrimMemory()` を(`:app` の `Application` か常駐箇所で)実装し、
  `TRIM_MEMORY_COMPLETE`/`TRIM_MEMORY_UI_HIDDEN` かつセッション非アクティブ時に `llm.close()` +
  `initDeferred` のキャッシュ破棄を行う。LMK に巻き込まれて強制終了されるより、能動的に解放して
  次回 `ensureInitialized()` で綺麗に再ロードさせる方が安全(実装コストは小さいので v1 に含める。
  効果測定は Phase 4 の実測次第で調整)。
- 初回 PTT の 10 秒待ちは v1 では許容し、`CONNECTING`(Voice Plate 表示 `WORKING`)で見せる。
  設定保存時の先行ロードは行わない(§6 Phase 4 で削除。ANR 修正により必須ではなくなったため)。

### 3.5 `LocalAgentController` 設計

```kotlin
class LocalAgentController(
    private val context: Context,
    private val stt: SpeechRecognizer = LocalAgentRuntime.stt,
    private val ttsPlayer: TtsPlayer = LocalAgentRuntime.ttsPlayer,
    private val ask: suspend (String) -> String = { LocalAgentRuntime.llm.ask(it) },
    private val toolExecutor: DeviceToolExecutor = DeviceToolExecutor(emptyList()),
    private val sessionTimeoutPolicy: SessionTimeoutPolicy = SessionTimeoutPolicy(), // 既定値のまま。§8-1 参照
    private val onAutoTerminated: (DisconnectReason) -> Unit = {},
) : VoiceSessionController
```

**すべてのブロッキング処理は Main を回避する**: `start()`/`cancel()` はどちらも本体を
`withContext(Dispatchers.Default) { ... }` で包む。呼び出し元(`CarVoiceInteractionSession.scope`)は
`Dispatchers.Main` であり、`ensureInitialized()`(~10秒)や `CapturePipeline/RenderPipeline.stop()` の
`thread.join()`(最大 2〜3 秒)を Main 上で実行すると確実に ANR する。

**start() と cancel() の競合を防ぐ**: `Mutex` で両者の本体を直列化し、`AtomicInteger` の世代カウンタで
「キューで待っている間に別の cancel が確定した」ケースを検出する。PTT 連打(特に 10 秒のロード待ち中の
再押下)で「隠れたままマイクが起動し続ける」事故を防ぐための機構。

```kotlin
private val opMutex = Mutex()
private val generation = AtomicInteger(0)

override suspend fun start() = withContext(Dispatchers.Default) {
    if (_state.value.connection != DISCONNECTED) return@withContext
    val myGen = generation.get()
    opMutex.withLock {
        if (myGen != generation.get()) return@withLock  // 待機中に cancel() が先行した
        // ... 以下の手順。各サスペンションポイントの後で generation.get() == myGen を再確認し、
        // 不一致ならここまでに開始したリソースを畳んで抜ける(マイクを開けたまま放置しない)
    }
}

override suspend fun cancel(reason: DisconnectReason) = withContext(Dispatchers.Default) {
    generation.incrementAndGet()  // 先に世代を進めて、待機中の start() に即座に知らせる(ロック不要)
    ttsPlayer.cancel()
    LocalAgentRuntime.llm.cancelProcess() // 推論中なら打ち切る(LiteRT-LM 0.16.0 の Conversation API)
    opMutex.withLock {
        // 冪等な後片付け(下記)
    }
}
```

**start() の手順**(サンプルの `toggleConversation()` 順序を踏襲、コンストラクタで配線済みの
`capture`/`render` を前提):

1. ガード + Mutex 取得(上記)
2. `_state`: `connection = CONNECTING`
3. 前提チェック: `LocalAgentRuntime.engineLoaded()`(x86_64 等 .so 不在)と `modelsAvailable()` を確認。
   不合格 → `connection = FAILED`、`assistantTranscript` に理由(「モデル未配置…」等)を入れ、
   **`onAutoTerminated(DisconnectReason.ERROR)` を呼んでから return**(FAILED を後に残さない。理由は下記)
4. audio focus 取得。`requestAudioFocus()` の**戻り値を確認**し `AUDIOFOCUS_REQUEST_GRANTED` でなければ
   `FAILED` へ(既存 `ConversationController` は戻り値を無視しているが、これは倣わない)。
   `OnAudioFocusChangeListener` を登録し、`AUDIOFOCUS_LOSS`(通話/緊急時の `INTERACTION_EXCLUSIVE`)で
   即 `cancel(DisconnectReason.ERROR)`、`AUDIOFOCUS_LOSS_TRANSIENT` で `ttsPlayer.cancel()` のみ
5. `ttsPlayer.cancel()`(前セッションの取り残しフレームを念のため破棄)
6. `LocalAgentRuntime.ensureInitialized()`(初回のみ ~10 秒。Mutex 経由の generation チェック済みなので
   キャンセルされても安全)+ `Mutex` 内で `llm.resetConversation()`
7. `capture.start()` → `render.start()`(**この順。逆は AEC 参照が壊れる**)。
   **両方とも `Boolean` の戻り値を確認**(`false` なら `FAILED`。マイク権限拒否や `AudioRecord`
   初期化失敗を検出する唯一の経路)
8. `_state`: `connection = CONNECTED, audioInput = CAPTURING`、挨拶文を `assistantTranscript` に設定
9. watchdog コルーチンを 1 つだけ起動(50ms 周期。バージイン判定とセッションタイムアウト判定を
   同じループで行う。詳細は下記)

**STT 確定結果からの遷移**(`stt.onFinalResult` はコンストラクタ配線時点で `::onUtterance` を渡す。
**`stt-worker` スレッド上で直接重い処理をしない**— ここをブロックすると `isSpeechActive()` が固まって
バージインが機能しなくなる):

```kotlin
internal suspend fun onUtterance(text: String) {
    val effective = text.replace(Regex("[。、．，！？!?\\s]"), "")
    if (effective.length < 3) return  // 断片フィルタ
    val myGen = generation.get()
    _state.update { it.copy(conversation = MODEL_PROCESSING, userTranscript = text) }
    scope.launch(Dispatchers.Default) {           // stt-worker を解放してから推論
        val reply = runCatching { ask(text) }.getOrElse { FALLBACK_TEXT }
        if (myGen != generation.get()) return@launch   // late reply: セッションはもう終わっている
        _state.update { it.copy(assistantTranscript = reply, audioOutput = PLAYING) }
        ttsPlayer.speak(reply)
    }
}
```

**バージイン + タイムアウト watchdog(単一コルーチン、50ms 周期)**:
サンプルどおり 4 連続 `stt.isSpeechActive()` でバージイン成立(`ttsPlayer.cancel()`、`interruptionCount++`、
`audioOutput = IDLE`)。**"activity"(タイムアウトのリセット条件)は生の VAD 発火ではなく、
「断片フィルタを通過した確定発話」「状態遷移」「TTS 再生中」のみとする**
(車内ノイズによる VAD 誤検出でアイドルタイムアウトが無限に延長され、結果として
`maxSessionDurationMs` まで居座り続ける — §7 R11 の音声フォーカス問題と直結するため重要)。

**cancel(reason)**(既存 `ConversationController.cancel()` と同じ `safely(step)` パターン、冪等
— 冪等判定は `connection == DISCONNECTED` で行う。**FAILED はここでは「未処理」として扱い、
必ずクリアする経路を通す**):

`generation.incrementAndGet()`(上記)→ `ttsPlayer.cancel()` → `llm.cancelProcess()` →
watchdog コルーチン cancel(自己 cancel ガード付き。既存 `ConversationController` と同じパターンを踏襲
— watchdog 自身が `cancel()` を呼ぶ経路があるため) → `stt.onFinalResult = null` → `stt.reset()` →
**`render.stop()` → `capture.stop()`(この順。capture が APM ハンドルを破棄するため)** →
audio focus 解放(リスナー解除含む) → `_state` リセット → `reason != USER_CANCEL` なら `onAutoTerminated(reason)`。

エンジン群(`LocalAgentRuntime`)は **解放しない**(プロセス生存。解放は `onTrimMemory` 経由、§3.4)。

**FAILED 状態と toggle-to-stop ガードの相互作用(既存 OpenAI パスにも同根のバグあり)**:
`CarVoiceInteractionSession.onShow()` は `connection != DISCONNECTED` を「アクティブ」とみなし
`hide()` するが、`FAILED` のまま放置すると次の PTT が「停止」として消費されてしまい、
3 回目でようやく再試行できる。**修正は `LocalAgentController` 側ではなく `:via` のガード条件
1 箇所を直す**(`connection !in setOf(DISCONNECTED, FAILED)`)— これは既存の OpenAI パスの
潜在バグも同時に直る根本修正であり、Phase 2(seam 抽出)でまとめて行う。

**サンプルの既知欠陥への対処**(移植時に修正、再現させない):

| # | サンプルの欠陥 | 対処 |
|---|---|---|
| 1 | `ConversationController` が stop() 後に再 start() で `RejectedExecutionException` | 該当なし。コルーチンベースで再設計し、controller は PTT ごとに新規生成される単回使用オブジェクトとして扱う |
| 2 | `setStreamDelayMs` が capture.start() 前に呼ばれ無効 | `capture.start()` 成功後に呼ぶ。初期値 20ms(暫定)、`ponytail:` コメントで実測化を明記 |
| 3 | ストリーム遅延が未計測 | v1 は固定値 + 将来 `AudioTrack.getTimestamp()` ベース計測(ceiling 明記) |
| 4 | `USAGE_MEDIA` + audio focus なし | `USAGE_ASSISTANT` + focus 戻り値チェック + リスナー登録(上記) |
| 5 | 音声スレッドがデフォルト優先度 | `THREAD_PRIORITY_URGENT_AUDIO` 設定 |
| 6 | `engineHandle` が非 volatile な `Long`(64bit tearing の恐れ) | `@Volatile` 化。破棄競合そのものは停止順序(render→capture)で解消済み |
| 7 | `resetConversation()` 未使用で履歴無限成長 | セッション開始ごとに `Mutex` 内で呼ぶ |
| 8 | `stt.close()`/`ttsPlayer.close()` 未呼び出し | Runtime シングルトン化により意図的にプロセス生存。解放は `onTrimMemory` 経由 |
| 9(新) | 推論中に `cancel()` されても打ち切られない | `llm.cancelProcess()` を cancel() 冒頭で呼ぶ + `generation` チェックで late reply を破棄 |
| 10(新) | sherpa `Vad`/`OfflineRecognizer` は非スレッドセーフ | `SenseVoiceRecognizer` 内で `synchronized` 保護(§3.3) |
| 11(新) | STT/TTS の遅延初期化が初回発話でフレーム欠落を招く | `warmUp()` を `ensureInitialized()` から呼ぶ(§3.4) |

**状態マッピング**(サンプルの LISTENING/THINKING/SPEAKING → 既存 4 軸、無変更):

| ローカルイベント | ConversationSessionState への反映 | Voice Plate 表示 |
|---|---|---|
| 起動中(モデルロード含む) | `connection = CONNECTING` | WORKING |
| 待機(LISTENING) | `conversation = IDLE, audioOutput = IDLE` | LISTENING |
| STT 確定(3 文字以上) | `conversation = MODEL_PROCESSING, userTranscript = text` | THINKING |
| LLM 応答確定 | `assistantTranscript = reply, audioOutput = PLAYING`(TTS 開始) | SPEAKING |
| TTS 再生完了 | `audioOutput = IDLE, conversation = IDLE` | LISTENING |
| バージイン成立(200ms 連続発話) | `ttsPlayer.cancel()`、`audioOutput = IDLE`、`interruptionCount++` | LISTENING |
| モデル未配置 / .so 不在 / focus 拒否 / マイク失敗 | `connection = FAILED` → 即座に `onAutoTerminated` 経由でクリア | ERROR(一瞬) |

`totalTokens`/`totalCostUsd` は 0 のまま(Voice Plate は 0 なら行を出さない実装なので自然に消える)。
`_state` への書き込みは常に `MutableStateFlow.update {}` を使う(`.value = .value.copy()` は使わない —
`stt-worker`/watchdog/audio focus リスナーなど複数スレッドから触れるため CAS が必須)。

### 3.6 設定画面の拡張

- `RealtimeServerMode` に `LOCAL_AGENT` 追加。`brokerUrl`/`realtimeCallsUrl` の `when` には
  `LOCAL_AGENT -> ""`(未使用)を追加(exhaustive when がコンパイルを強制する 2 箇所)。
- `ServerSettingsActivity`: 読み書きの二値 if/else を `when` に変更、`R.id.mode_local_agent` ラジオ追加。
  LOCAL_AGENT 選択時はホスト入力 EditText を `isEnabled = false` に。
  保存時、LOCAL_AGENT かつ `LocalAgentRuntime.modelsAvailable()` が false なら Toast で警告
  (保存自体は許可 — モデルは後から push できる)。
  **`LocalAgentRuntime.modelsAvailable()` は companion static のみを呼ぶため、設定画面からの
  呼び出しでも sherpa の重量級インスタンスを構築しない**(§3.4 で保証)。
- `:app/build.gradle.kts` に `implementation(project(":localagent"))` を追加
  (`:via` 経由の `implementation` は `:app` に伝播しないため、`ServerSettingsActivity` から
  `LocalAgentRuntime` を直接参照するには明示的な依存が要る。既存の `:realtime` 直接依存
  — issue #43 のコメントに前例あり — と同じパターン)。
- `activity_server_settings.xml`: RadioButton 1 個追加。
- `strings.xml`: `server_settings_mode_local_agent`(例: `Local Voice Agent (on-device)`)追加。

### 3.7 `:localagent/build.gradle.kts`(確定形)

```kotlin
plugins { alias(libs.plugins.android.library) }

android {
    namespace = "com.example.voiceinteractionappsample.localagent"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true   // 保険。JVM テストは android.* API を直接呼ばない設計だが念のため
    }
}

dependencies {
    api(project(":session"))                    // ConversationSessionState / DisconnectReason / seam
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm.android)       // カタログに追加: com.google.ai.edge.litertlm:litertlm-android:0.16.0
    compileOnly(files("libs/sherpa-onnx-1.13.5.aar"))   // §2.4-1: ランタイム同梱は :app 側
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- `namespace` は VIA 系だが、**Kotlin ソースのうち `LocalAudioEngine.kt` のみ
  `package com.example.localvoiceagent`**(AGP は namespace 外パッケージのソースを許容する。JNI 契約優先)。
- `liblocal_audio_engine.so` → `localagent/src/main/jniLibs/arm64-v8a/`(library の AAR に同梱され :app に伝播)。
- `:app` に追加: `implementation(files("../localagent/libs/sherpa-onnx-1.13.5.aar"))` と
  `implementation(project(":localagent"))`(§3.6)。
- `:via` に追加: `implementation(project(":localagent"))`。
- `settings.gradle.kts` に `include(":localagent")`。
- サイズ最適化(任意): `:app` の packaging で `libsherpa-onnx-c-api.so`/`-cxx-api.so` を exclude(−4.7MiB)。
- RECORD_AUDIO は `:audio` のマニフェストで宣言済み。`:localagent` のマニフェストは空でよい。
  INTERNET 不要(LOCAL_AGENT パスはゼロネットワーク)。
- **JVM テストの射程**: sherpa クラスは `compileOnly` のため `:localagent` の `test`/`androidTest` 双方の
  実行時クラスパスに存在しない。`SenseVoiceRecognizer`/`SupertonicTts` を実際にロードする統合的な検証は
  `:app` の `androidTest`(sherpa AAR を `implementation` している側)で行う。`:localagent` 自身の JVM
  テストは `onUtterance`/`watchdogTick` など §3.3 のテスト容易性設計に従い、sherpa クラスに触れない
  範囲(断片フィルタ、バージイン判定、状態遷移)に絞る。

### 3.8 fetch スクリプト(サンプルから 5 本コピー、パス調整のみ)

`scripts/` に配置: `fetch_sherpa_onnx.sh`(→ `localagent/libs/`)、`fetch_local_audio_engine.sh`
(→ `localagent/src/main/jniLibs/arm64-v8a/`、sha256 `e0fa76f8…` 検証付き)、
`fetch_gemma.sh` / `fetch_stt_models.sh` / `fetch_supertonic.sh`(→ `models/` キャッシュ + `adb push`)。
`.gitignore` に `models/`, `*.aar`, `*.so`, `*.onnx`, `*.litertlm` を追加。
Phase 0 で仮置きしたこの 5 本は Phase 3 でもそのまま使うため**破棄しない**(Phase 0 の「使い捨て」は
統合コードのみを指す)。

---

## 4. モデル配置

| モデル | サイズ | デバイスパス |
|---|---|---|
| gemma-4-E2B-it.litertlm (HF litert-community, ungated) | 2.6GB | `/data/local/tmp/llm/` |
| sherpa-onnx-sense-voice-…-int8-2024-07-17 + silero_vad_v5.onnx | ~230MB | `/data/local/tmp/stt/` |
| sherpa-onnx-supertonic-3-tts-int8-2026-05-11 | 129MB | `/data/local/tmp/tts/` |

パス定数はサンプルの各エンジンにハードコードされたまま移植(fetch スクリプトと突合済み)。

**`/data/local/tmp` が読めるのは `untrusted_app`(非プラットフォーム署名アプリ)である前提に依存する。**
AOSP sepolicy を確認済み: `untrusted_app_all.te` が `shell_data_file` への `r_file_perms`(`map` 含む)
を許可しており、この許可は userdebug/eng に限定されず **user ビルド・実車ヘッドユニットでも有効**。
一方 `priv_app`/`system_app`(プラットフォーム署名や `/system/priv-app` 配置)には同等の許可がなく、
`system_app.te` には明示的な `neverallow` すらある。**開発中の `adb install` では問題ないが、
将来この VIA アプリを priv-app や プラットフォーム署名で配布する段階になった時点で読めなくなる。**
対策として、モデルルート解決を 1 関数に集約しておく:

```kotlin
fun modelRoot(ctx: Context): File =
    File("/data/local/tmp").takeIf { File(it, "llm").canRead() } ?: ctx.filesDir
```

v1 は前者(`/data/local/tmp`)のみ実装し、後者への切り替え(`filesDir` へのコピー導線)は配布形態が
決まった時点の課題として明記するに留める(§7 R12)。

`modelAvailable()` は現状 `canRead()` のみで、途中で切れた `adb push` を検出できない
(ファイルは存在するが壊れている場合、ネイティブ層で例外 → 未捕捉ならプロセス kill)。
v1 では許容し、既知の運用上の注意点として README に明記する(サイズ突合や sha256 サイドカーは
将来の改善候補)。

エミュレータでの実測容量は §9 を参照。

---

## 5. ツールコール設計(Phase 5)

**方針転換(調査による)**: 当初「プロンプトベースの JSON 出力 + パース」を想定していたが、
LiteRT-LM 0.16.0 に ネイティブツール API と制約付きデコードが存在することをバイナリ検査で確認済み。

- 第 1 候補: `ConversationConfig(tools = listOf(toolProvider), automaticToolCalling = false)` で
  `ToolCall` を受け取り、`DeviceTool` 契約へブリッジして既存 `DeviceToolExecutor` パイプライン
  (parse → required fields → policy → UX → execute)に流す。実行結果をツール応答として返し、
  モデルに最終発話を生成させる。`callId` はローカルで採番(UUID)。
- 第 2 候補(第 1 候補の API が期待どおり動かない場合): `ResponseFormat.json(schema)` による
  制約付き出力 + 自前ディスパッチ。
- どちらも `:tools` は無変更。`RealtimeToolBridge` に相当する `LocalToolBridge` を `:localagent` に新設。
- **Phase 5 冒頭に 1 日のスパイクを置く**: Gemma 4 E2B が supertonic/SenseVoice 経由の日本語発話
  「〜の動画を見せて」から `open_youtube_search(query)` を安定して発火できるかを 20 発話で計測。
  発火率・誤発火率の基準(例: 発火 ≥ 8 割、誤発火 0)を満たさなければ v1 スコープ外として切る。
  ツールコール中の状態は既存 enum の `TOOL_EXECUTING`(現在未使用)をここで初めて使う。

---

## 6. フェーズ計画

依存関係: P0 → P1(P2 統合)→ P3 → P4 → P5(独立性高)→ P6

### Phase 0: スパイク(使い捨てブランチ、統合コードのみ破棄。fetch スクリプトは残す)

- fetch スクリプト 5 本を仮置きし、`.aar`/`.so`/モデルを取得・push
- `:app` に仮組みで 3 ライブラリを追加し、AAOS(arm64 AVD)上の VIA プロセス内で:
  `LocalAudioEngine.smokeTest()` 成功 / SenseVoice 単発認識 / supertonic 単発合成 / Gemma 単発応答
- §2.4 のビルド罠(AAR in library、Google Maven、configuration-cache との相性)を実プロジェクトで確認
- **実測を取る**: VIA プロセスの `dumpsys meminfo` RSS(§9 の idle 実測 6.2GB 余剰と比較)、
  実際のモデル push 後の `/data` 空き容量、Gemma 応答レイテンシ・TTS RTF(実 SoC が入手できるまでは
  同じ arm64 AVD 上で構わないが、host CPU 実測である旨を記録に残す)
- **ゲート**: 4 項目の動作成功 + 実測値が §9 の見積り(RAM 余裕 ~1-2GB、storage 余裕 ~4.6GB)を
  大きく下回らないこと。下回った場合はモデル量子化や `onTrimMemory` 方針を Phase 3 前に見直す

### Phase 1: 設定拡張 + seam 抽出(統合。理由: 分離すると LOCAL_AGENT 選択時に PTT が不可解な
壊れ方をする中間状態が生まれ、Phase 1 単体の受け入れ基準が検証不能になるため)

- `RealtimeServerMode.LOCAL_AGENT` + `RealtimeServerSettings` の when 2 箇所
- `ServerSettingsActivity` + レイアウト + strings
- `VoiceSessionController` 新設(§3.1 の override 修正込み)、`ConversationController` に implements 追加
- `createController(): VoiceSessionController` 化。LOCAL_AGENT 分岐は `LocalAgentController` のスタブ
  (即 `FAILED` + 「未実装」)で可
- `onShow()` のコレクタリーク修正(Job 保持)
- **toggle-to-stop ガードの FAILED 許容修正**(`connection !in setOf(DISCONNECTED, FAILED)`)—
  既存 OpenAI パスの潜在バグも同時に直る
- **受け入れ**: OPENAI/LOCAL の既存動作に差分なし(既存 Live テスト緑)。LOCAL_AGENT を選んで PTT →
  ERROR プレートに「未実装」表示 → 次の PTT で再試行可能(2 度目が hide にならないことを確認)。
  設定の保存・復元が 3 モードで正しく動く

### Phase 3: `:localagent` 移植(中)

- モジュール新設、9 ファイル移植(§3.3 の修正点込み)、`LocalAgentRuntime` + `LocalAgentController` 新規実装
- `LocalAgentController` の JVM ユニットテスト: 断片フィルタ(3 文字ルール)とバージイン判定
  (4 連続ヒット)を `onUtterance`/`watchdogTick` に対して `kotlinx-coroutines-test` の仮想時間で検証。
  sherpa クラスに触れないことを確認(§3.7)
- ライフサイクル全体(起動〜会話〜バージイン〜停止〜再起動)は既存 `:session` androidTest の流儀を踏襲し
  `:app/androidTest` に置く(sherpa/litertlm の実ロードが必要なため)
- **受け入れ**: PTT → 挨拶表示 → 日本語会話 → バージイン → PTT 再押下で停止、が AAOS エミュレータで成立。
  Main スレッドがブロックされていないこと(`StrictMode` で検知するテストを 1 本追加)

### Phase 4: 統合仕上げ(中)

- タイムアウトポリシー適用(§8-1 の結論を反映)、`onAutoTerminated` → hide 動作確認
- モデル未配置・.so 不在(x86_64)・マイク権限拒否・focus 拒否 の 4 異常系で ERROR 表示とメッセージ、
  かつ次の PTT で再試行できることを確認(Phase 1 のガード修正の恩恵を LOCAL_AGENT でも確認)
- `onTrimMemory` の解放パス動作確認(バックグラウンド放置後、次回 PTT で再ロードされること)
- `DiagnosticsCollector.checkBackendReachable()` を mode 対応の 2 行修正に留める
  (`mode == OPENAI` の時だけ到達性チェック、それ以外は null)。個別の mode 表示フィールド追加は行わない
- 実 SoC(入手できれば)または同等 arm64 ボードでの性能再測定(§7 R13)
- **受け入れ**: `docs/acceptance-checklist.md` 相当のライフサイクル全パス + 異常系 4 種が意図どおり表示され、
  かつ「生音声を永続保存しない」(#18)を維持していることを確認

### Phase 5: ツールコール(中、独立)

- スパイク(§5)→ 合格なら `LocalToolBridge` 実装、`TOOL_EXECUTING` 状態接続、YouTube 検索 E2E
- **受け入れ**: 「〜の動画を見せて」→ Chrome で YouTube 検索結果が開き、モデルが結果を発話する

### Phase 6: テスト・ドキュメント(小)

- `docs/how-to-run.md` に LOCAL_AGENT 手順(fetch → push → AVD 要件 → 切替)を追記。§9 の実測値
  (実際の `/data` 容量・idle RAM)を「サンプルの README の 8GB/16GB」の**訂正版**として明記
- README のモジュール表・アーキテクチャ図更新、NOTICE 生成(サンプルの `generate_notices.sh` 流用)
- `third_party/` に local_audio_engine の VERSION/README(既存 libwebrtc README の流儀に合わせる)

---

## 7. リスク表(最終版)

| # | リスク | 影響 | 状態・対策 |
|---|---|---|---|
| R1 | ツールコールの発火精度(Gemma E2B) | 機能 | LiteRT-LM 0.16.0 のネイティブ API 確認済み。残るはモデル品質のみ → Phase 5 スパイクで判定、不合格なら v1 から切る |
| R2 | AAR-in-library ビルドエラー | ビルド | 確定済み・対策設計済み(§2.4-1、デコンパイルで裏取り済み)。Phase 0 で最終実証 |
| R3 | x86_64 AVD で .so 不在 | 開発環境 | 確認済み・無害: ABI split 未使用のため単一 APK に arm64/x64 両方が入り、x64 側は `.so` が単に欠けて `UnsatisfiedLinkError`→`loaded=false`→FAILED 表示になるだけ(インストール・起動は正常)。ABI split/AAB を導入する場合のみ再検討 |
| R4 | 初回 PTT の ~10 秒ロードが `Dispatchers.Main` 上で ANR を起こす | **重大** | §3.5 で `withContext(Dispatchers.Default)` 必須化。加えて Mutex + generation でキャンセル安全性も確保 |
| R5 | 実機 AEC(実音響経路)未検証 | 品質 | OpenAI モードと共通の既知未解決。Tests A–E を LOCAL_AGENT にも適用。AEC3 チューニングフックは .so 側に既設 |
| R6 | ストリーム遅延固定値(20ms 暫定) | 品質 | 実機で `--ei delay` 相当の調整手段を残す。実測化は ceiling 明記の将来課題 |
| R7 | メモリ(Gemma E2B 常駐時 数 GB) | 実機選定 | AAOS エミュレータで実測(§9): idle 時システムだけで ~1.7-2.0GB 消費、空き ~6.2GB。ワークロード見積り 3-5GB は収まる可能性が高いが余裕は数 GB 程度で「潤沢」ではない。Phase 0 で実プロセスの RSS を実測してこの見積りを更新すること |
| R8 | litertlm 推移的依存(gson/kotlin-reflect) | ビルド | coroutines は 1.9.0 で一致確認済み。kotlin-reflect 2.2.21 は AGP 9.3.1 の Kotlin(2.3.x 系)よりやや古く、厳密には version skew の可能性あり。Phase 0 で警告有無を確認、出れば `constraints{}` で固定 |
| R9 | 推論中に `cancel()` されても LLM 呼び出しが打ち切られず、`resetConversation()`/`close()` と競合して native ハンドルが use-after-free になりうる | **重大** | `Conversation.cancelProcess()`(0.16.0 に実在確認済み)を cancel() 冒頭で呼ぶ + `LlmEngine` 内 `Mutex` 直列化 + `generation` チェックで late reply を破棄(§3.4/§3.5) |
| R10 | CONNECTING 中の PTT 再押下で `start()`/`cancel()` が競合し、Voice Plate 非表示のままマイクが起動し続ける | 高 | `Mutex` + `AtomicInteger` generation 機構で解消(§3.5) |
| R11 | AAOS の `CarAudioService` は `VOICE_COMMAND`(=`USAGE_ASSISTANT`)保持中、ナビ音声・アラーム・通知を `INTERACTION_REJECT` する。当初案の最大セッション長 10 分はこれを 10 分間抑制しうる | **重大(車載安全)** | AOSP `FocusInteraction.java` で確認済み。§8-1 で結論: 最大セッション長は OpenAI モード(2分)と揃えるか、それ以下にする。加えて focus 取得結果の確認 + `OnAudioFocusChangeListener` を実装(§3.5) |
| R12 | `/data/local/tmp` は `untrusted_app` だから読める(sepolicy 確認済み)。priv-app/プラットフォーム署名配布になると `system_app` の `neverallow` で読めなくなる | 中(将来の配布形態次第) | `modelRoot()` の 1 関数に集約(§4)。v1 は `/data/local/tmp` のみ実装、`filesDir` 切替は配布方針確定後の課題として明記 |
| R13 | 性能実測(§2.1)はすべて host CPU(エミュレータ)上の値。実 SoC(車載ヘッドユニット相当)のスループットは未検証 | 中 | Phase 0/Phase 4 に実測ゲートを追加。悪化した場合の逃げ道: `Backend.GPU()`/`NPU()`(0.16.0 に存在)、`sendMessageAsync` ストリーミングで文単位 TTS 開始、TTS `numSteps` 削減 |
| R14 | `TtsPlayer`/`SenseVoiceRecognizer` はプロセス常駐だが状態はセッション寿命。セッション跨ぎで前回音声が再生される、sherpa `Vad` が非スレッドセーフで `reset()` と worker が競合する、遅延初期化の例外が未捕捉ならプロセスごと落ちる | 中 | `cancel()` 冒頭で `ttsPlayer.cancel()`(防御的)、`SenseVoiceRecognizer` に `synchronized` 保護、`warmUp()` + `try/catch(Throwable)`(§3.3/§3.5) |
| R15 | `FAILED` が toggle-to-stop ガードにラッチし、失敗直後の PTT が「停止」として消費され再試行に 2 回かかる。**既存 OpenAI パスにも同根のバグが潜在** | 中 | `:via` のガード条件を `connection !in setOf(DISCONNECTED, FAILED)` に修正(Phase 2/現 Phase1 統合分で実施、両パス共通で直る) |
| R16 | `RenderPipeline.stop()` が `join()` タイムアウト後も `destroy()` を呼ぶと、まだ native ハンドルを触っているスレッドと衝突しうる。`capture.start()`/`render.start()` の `Boolean` 戻り値を無視するとマイク権限拒否等が無症状で握りつぶされる | 低〜中 | タイムアウト時は `destroy()` をスキップ(リーク優先、§3.3)。両 `start()` の戻り値を必ず確認(§3.5) |
| R17 | 用意された AAOS エミュレータの実際の `/data` は config.ini の宣言(16GB)と異なり実測 10GB(空き 7.6GB)。RAM は宣言通り約 8GB | 低(実測により解消) | §9 に実測値を記録。モデル 3GB を差し引いても実測ベースで運用可能と確認済み |

---

## 8. 自動テストできる部分・人間にテストさせる部分

### 8.1 自動化できる(CI/スクリプトで実行可能、人間の判断不要)

| レベル | 対象 | 手法 |
|---|---|---|
| JVM ユニットテスト | 断片フィルタ(3 文字ルール)、バージイン判定(4 連続ヒット)、状態遷移(`ConversationSessionState` の軸の組み合わせ → `VoicePlateState` マッピング)、`DeviceToolExecutor`/`ToolCall` 周り(既存資産の再確認) | `:localagent` の JVM テスト(§3.3 のテスト容易性設計)。`kotlinx-coroutines-test` の仮想時間でバージイン watchdog を実時間待ちなしに検証 |
| ビルド健全性 | AAR-in-library パターンのコンパイル成否、Google Maven からの litertlm 解決、AGP built-in Kotlin と litertlm の Kotlin メタデータ互換性、`configuration-cache` との相性 | Phase 0 の CI ジョブ(1 回のビルド成功で足りる、実機不要) |
| 異常系(モデル欠如・.so 欠如) | `modelsAvailable()`/`engineLoaded()` が false のときに `FAILED` → 次PTTで再試行可能、になること | AVD 上の instrumented test(モデル未 push の状態で起動するだけなので実モデル不要、CI の arm64 エミュレータで回せる) |
| x86_64 での graceful degradation | インストール・起動が正常、LOCAL_AGENT 選択時に FAILED 表示になること | x86_64 AVD 上の instrumented test |
| ライフサイクル(起動→会話→バージイン→停止→再起動) | 既存 `:session` androidTest と同じパターンで `:app/androidTest` に配置。実モデルを CI 環境に用意できれば完全自動化可能 | `RealtimeLiveTestHarness` 相当のハーネスを local agent 用に用意。CI に arm64 AVD + fetch 済みモデル一式が要る(ストレージ・時間コストは要検討) |
| メモリ/ストレージの数値検証 | `dumpsys meminfo` の RSS、`df` の空き容量をしきい値と比較 | `adb shell` を呼ぶスクリプトで CI 化可能(§9 のベースラインを回帰基準に使える) |
| Main スレッドブロック検知 | `StrictMode.setThreadPolicy` で `start()`/`cancel()` 実行中の Main スレッドブロックを検出 | instrumented test 1 本(§6 Phase 3) |

### 8.2 人間の判断が必要(自動化になじまない、または自動化のコストが見合わない)

| 対象 | 理由 |
|---|---|
| 実車スピーカー→マイク音響経路での AEC(Tests A–E) | エミュレータには音響経路が存在しない。実車での「エコーが気にならないか」は最終的に人間の耳で判定するしかない(ERLE の数値自体はスクリプト化できるが、実車セットアップ・走行中ノイズ下での収録は人手が要る) |
| 会話の自然さ(LLM 応答の質、日本語の妥当性) | 主観評価。Gemma 4 E2B の応答が「短く話し言葉として自然か」は自動判定が困難 |
| TTS の声質・聞き取りやすさ | 主観評価 |
| バージインの「体感」応答性 | 200ms のしきい値が実際の会話でどう感じられるか(早すぎ/遅すぎ)は人間が使ってみないと分からない |
| 実 SoC でのレイテンシ・スループットの実用可否判定 | 数値(§7 R13)は自動測定できるが、「体感として実用に足るか」の最終判断は人間 |
| AAOS 実車でのナビ音声抑制の許容可否(R11) | セッション中ナビ音声が止まることが安全上・UX 上許容できるかは製品判断であり、テストで自動可決できない |
| 実機(非エミュレータ)での SELinux 到達性(R12 の実害有無) | 対象ヘッドユニットの署名方式(untrusted_app か priv-app か)によって挙動が変わるため、実機ごとに確認が要る |
| 走行中のツールコール可否(UX 制限) | `checkUxRestriction()` フックは実装できるが、「運転中に YouTube 検索を許可してよいか」はポリシー判断 |
| Voice Plate の実ディスプレイでの視認性 | AVD のレンダリングと実車ディスプレイ(輝度・視野角・解像度)は異なる |
| 長時間(数時間〜1日)运用でのメモリリーク・発熱 | Phase 0/4 の短時間実測では検出できない。実車での長時間ドライブサイクルでの確認が必要 |
| モデル配置作業そのもの(初回セットアップ) | `adb push` 3GB の実行、モデル取得の可用性(Hugging Face/GitHub Release の到達性)は自動テスト対象ではなく運用手順 |

---

## 9. AAOS エミュレータの容量実測(検証済み)

用意されていた AVD `Automotive_1408p_landscape`(`android-35-ext15`, `android-automotive`,
arm64-v8a, 4 vCPU, mic 入力有効)を実際に起動し、ゲスト内部から `adb shell` で実測した。

### 9.1 ストレージ

```
$ adb shell df -h /data
Filesystem       Size Used Avail Use% Mounted on
/dev/block/dm-41  10G 1.8G  7.6G  20% /data
```

**config.ini の `disk.dataPartition.size=16G` はイメージの上限値であり、実際にフォーマットされている
`/data` パーティションは 10GB、起動直後の空き容量は 7.6GB だった**(既に AVD 上のテスト用画像ファイル等
が 1.8GB 使用済み)。モデル 3 種の合計は約 2.96GB(Gemma 2.6GB + STT 230MB + TTS 129MB)。

**結論: 対応可能。** 7.6GB − 2.96GB ≈ **4.6GB の余裕**。APK インストール分(lean 構成で arm64 約
50-60MB)や Dalvik キャッシュの増分を差し引いても十分な margin がある。ただし「16GB 前提」で
見積もっていた当初の余裕(§7 旧リスク文言)よりは狭いため、Phase 0 で実際にモデルを push した後の
空き容量を再確認しておくこと(R17)。

### 9.2 メモリ

```
$ adb shell cat /proc/meminfo | head -5
MemTotal:        8129904 kB   (≈ 7.75 GiB、宣言通り)
MemFree:          3757932 kB
MemAvailable:     6246756 kB

$ adb shell dumpsys meminfo | grep -E "Total RAM|Free RAM|Used RAM"
Total RAM: 8,129,904K
 Free RAM: 6,335,465K(安定後)
 Used RAM: 1,732,812K(安定後)
```

起動直後、**VIA アプリも local voice agent も一切動かしていない状態で、AAOS システムだけで
約 1.7〜2.0GB を消費**している(内訳の上位: `com.google.android.carassistant:search` 376MB、
`system` 347MB、`com.google.android.gms` 複数プロセス、`com.android.car.carlauncher` 281MB、
`com.android.systemui` 267MB、`com.google.android.tts` 185MB、クラスタ表示・ラジオ・電話等の
車載サービス多数)。**この baseline はサンプル(`android-local-voice-agent`)が実際に E2E 検証に
使っていた非 automotive の AVD には存在しない負荷であり、AAOS 固有の上乗せである。**

差し引き、idle 時点での空き RAM は約 **6.2〜6.3GB**。local voice agent のワークロード見積り
(Gemma 4 E2B の推論時 RSS + onnxruntime + SenseVoice/Silero + supertonic、合計 3〜5GB 程度と推定
— サンプルのオリジナル計測では実測されていない値なので推定)は収まる可能性が高いが、
**「潤沢」と言えるほどの余裕ではない**。

**結論: 対応可能と判断するが、Phase 0 で VIA プロセス単体の実 RSS を `dumpsys meminfo
com.example.voiceinteractionappsample` 相当で実測し、この見積りを裏付けること。** 不足するようであれば
§3.4 の `onTrimMemory` 解放パスの発動閾値を早める、または Gemma のより小さい量子化バリアントへの
切り替えを検討する。

### 9.3 その他の確認事項

- ABI: `arm64-v8a`(`ro.product.cpu.abi`)。`liblocal_audio_engine.so` の唯一の対応 ABI と一致 — 問題なし
- `feature:android.hardware.type.automotive` を保持 — 正しく automotive イメージとして認識される
- `hw.audioInput=yes` によりホストマイクのパススルーが有効 — 既存 how-to-run の手順がそのまま使える
- ホスト側(この開発機)は RAM 36GB・空きディスク 83GB — エミュレータを動かす側の制約にはならない

---

## 10. 決定事項(2026-08-15 ユーザー確定)

1. **タイムアウト値**: **OpenAI モードと同一(idle 10 秒 / max 2 分)**。既存 `SessionTimeoutPolicy` の
   既定値をそのまま使う。R11(AAOS フォーカス抑制)への対処としても最短で安全。
2. **Phase 5 の合否基準**: **スパイク合格なら実装**。基準は 20 発話中 発火 ≥ 16 かつ誤発火 0。
   不合格なら v1 から除外して結果を報告する。
3. **挨拶文**: **テキスト表示のみ**(既存 OpenAI モードと同じ)。`GREETING_TEXT` 相当の文字列を
   LOCAL_AGENT でも Voice Plate に表示する。TTS では発話させない。
4. **モデル配置**: **v1 は `/data/local/tmp`(adb push)のみ**。`modelRoot()` 関数に集約しておき、
   `filesDir` 切替は配布方針確定後の課題とする。
