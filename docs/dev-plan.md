# AAOS VIA + OpenAI Realtime WebRTC サンプルアプリ 開発計画（チケット化用）

前提: レビュー済みの実装計画（会話ログ参照）＋ 以下の確定事項に基づく。

## 確定事項
- WebRTCライブラリ: `io.getstream:stream-webrtc-android` のプレビルドAARを採用（ソースからの自前ビルドはしない）。
- モジュール構成: 最初から `:via :realtime :audio :tools :session :diagnostics` の6モジュールを作成する。
- Session Broker: 本リポジトリのスコープ外。Android側は固定/手動投入のモックephemeral credentialで開発し、Brokerとの契約（リクエスト/レスポンス形式）だけをインターフェースとして固定する。

## GitHub管理方針
- Milestone = Phase（Phase 0〜10）を作成する。
- 既存ラベルに加えて area ラベルを追加する: `area:via` `area:realtime` `area:audio` `area:tools` `area:session` `area:diagnostics` `area:backend-mock` `area:docs`
- 各Issueは「タイトル / ラベル / Milestone / 本文（背景・作業内容・受け入れ条件・依存Issue）」を持つ。

---

## Phase 0 — プロジェクト基盤
Milestone: `Phase 0: Baseline`

### 0-1. Kotlin対応をプロジェクトに追加する
- labels: `enhancement`, `area:docs`
- 背景: 現状 `:app` は Kotlinプラグイン未導入。計画全体がKotlin前提（data class等）。
- 作業: version catalogに`kotlin-android`プラグインとKotlinバージョンを追加、`app/build.gradle.kts`へ適用、既存の`.kt`テストファイルがコンパイル対象になることを確認。
- 受け入れ条件: `./gradlew build` が通り、既存`ExampleUnitTest.kt`/`ExampleInstrumentedTest.kt`がコンパイルされる。

### 0-2. 6モジュールのGradleスケルトンを作成する
- labels: `enhancement`, `area:via`, `area:realtime`, `area:audio`, `area:tools`, `area:session`, `area:diagnostics`
- 作業: `settings.gradle.kts`に6モジュールを追加。各モジュールは空のlibrary moduleとして作成し、`:app`から依存させる。依存方向は `:app → :via → {:session} , :session → {:realtime,:audio,:tools}, :diagnostics → 各モジュール参照`。
- 受け入れ条件: 全モジュールが空実装でビルド成功。循環依存がないこと。
- 依存: 0-1

### 0-3. third_party/libwebrtc とプレビルドAAR採用を記録する
- labels: `enhancement`, `area:realtime`
- 作業: `third_party/libwebrtc/README.md`に採用ライブラリ（`io.getstream:stream-webrtc-android`固定バージョン）、対応ABI（`arm64-v8a`, `x86_64`のみ、32bit除外）、ライセンス（Apache 2.0 + BSD由来）を記録。`:realtime`モジュールへ依存追加。
- 受け入れ条件: バージョンが固定されドキュメント化されている。HEAD追従構成でないこと。

---

## Phase 1 — VIA shell
Milestone: `Phase 1: VIA Shell`

### 1-1. VoiceInteractionService 最小実装
- labels: `area:via`
- 作業: 常駐初期化のみ行う`VoiceInteractionService`。PeerConnection/HTTP/OpenAI接続/音声処理/tool実行/UI状態機械を置かない。
- 受け入れ条件: サービスがROLE_ASSISTANTとして登録可能。

### 1-2. VoiceInteractionSessionService + CarVoiceInteractionSession 最小実装
- labels: `area:via`
- 作業: `CarVoiceInteractionSession`がVoice Plateと会話セッションのライフサイクルのみを持つ（Realtime接続は持たない）。
- 依存: 1-1

### 1-3. Voice Plate UI
- labels: `area:via`
- 作業: 表示状態は `Listening/Thinking/Speaking/Working/Error` のみ。マイクON/OFFの開発者向け表示はdiagnostic buildのみに限定。
- 依存: 1-2

### 1-4. AAOS Automotive Emulatorでの起動確認
- labels: `area:via`, `area:diagnostics`
- 作業: 既存の`Automotive_1408p_landscape` AVDで既定Voice Interaction Appとして登録し、PTT/TTTからVoice Plateが表示されることを確認。
- 受け入れ条件: 25節の受け入れ条件のうち「VIAが既定assistantとして起動できる」「PTT/TTTからVoice Plateが表示される」を満たす。
- 依存: 1-1, 1-2, 1-3

---

## Phase 2 — Google WebRTC単体
Milestone: `Phase 2: WebRTC Standalone`

### 2-1. PeerConnectionFactory 生成 (:realtime)
- labels: `area:realtime`
- 依存: 0-3

### 2-2. WebRtcAudioEngine / JavaAudioDeviceModule 生成 (:audio)
- labels: `area:audio`
- 作業: `MediaRecorder.AudioSource.VOICE_COMMUNICATION`を指定。AudioRecord/AudioTrack初期化エラーを取得しログ化。
- 依存: 2-1

### 2-3. AEC/NS capability診断
- labels: `area:diagnostics`, `area:audio`
- 作業: `isBuiltInAcousticEchoCancelerSupported()` / `isBuiltInNoiseSuppressorSupported()` を起動時に記録し診断画面へ表示。
- 依存: 2-2

---

## Phase 3 — OpenAI Realtime接続（Brokerはモック）
Milestone: `Phase 3: Realtime Connection`

### 3-1. Broker連携インターフェースの契約を固定する（実装はしない）
- labels: `area:backend-mock`, `area:docs`
- 作業: `POST /api/realtime/session` のリクエスト/レスポンス形式（`clientSecret`/`expiresAt`/`sessionConfigVersion`）をドキュメント化し、Android側は差し替え可能なインターフェース越しにこれを呼ぶ設計とする。
- 受け入れ条件: 実Brokerが後日実装されても Android側コード変更が契約の範囲で収まる。

### 3-2. モックcredentialプロバイダ
- labels: `area:realtime`
- 作業: 開発用に手動投入 or ローカル固定のephemeral client secretを返すモック実装（3-1のインターフェースを満たす）。**標準APIキーはAndroidに置かない**ことをこのモックでも守る（モックはあらかじめ発行されたephemeral secretを保持するだけ）。
- 依存: 3-1

### 3-3. RealtimeWebRtcClient — SDPネゴシエーション
- labels: `area:realtime`
- 作業: `createOffer → setLocalDescription → POST https://api.openai.com/v1/realtime/calls (Content-Type: application/sdp, Authorization: Bearer <ephemeral>) → setRemoteDescription`。実装直前に公式ドキュメントでエンドポイント/ヘッダを再確認する（旧`/v1/realtime/sessions`ではないことに注意）。
- 依存: 2-1, 3-2

### 3-4. DataChannel "oai-events" と session.update
- labels: `area:realtime`
- 作業: DataChannelオープン、`session.update`送信、event decoder/encoderの骨格（conversation/response/function call/error）。
- 依存: 3-3

### 3-5. clientSecretのログ出力禁止を機械的に担保する
- labels: `area:realtime`, `bug`
- 作業: ログ出力箇所のlintルール or 簡易ユニットテストで`clientSecret`文字列がログAPIに渡らないことを検証。
- 依存: 3-2

---

## Phase 4 — 双方向音声
Milestone: `Phase 4: Full Duplex Audio`

### 4-1. mic→OpenAI / OpenAI→speaker 疎通確認
- labels: `area:realtime`, `area:audio`
- 受け入れ条件: assistant再生中もmicrophone captureが継続する。mute/AudioRecord停止/sender無効化のいずれも実装に含まれないことをコードレビューで確認。
- 依存: 3-4, 2-2

### 4-2. 4系統state実装 (:session)
- labels: `area:session`
- 作業: `ConnectionState / AudioInputState / AudioOutputState / ConversationState` を実装。barge-in中の`CAPTURING`+`PLAYING`同時成立を正常系として扱うユニットテストを書く。
- 依存: 4-1

---

## Phase 5 — AEC
Milestone: `Phase 5: AEC`

### 5-1. AUTO/HARDWARE/WEBRTC 切替 (diagnostic buildのみ)
- labels: `area:audio`, `area:diagnostics`
- 依存: 2-3

### 5-2. AECテストA〜E実施とdevice profile記録
- labels: `area:diagnostics`
- 作業: 19節のTest A〜EをAutomotiveエミュレータ/実機で実施し、`device profile`形式（threshold, reason等）で記録するフォーマットを作る。
- 依存: 5-1

---

## Phase 6 — Barge-in
Milestone: `Phase 6: Barge-in`

### 6-1. RealtimeVadConfig 実装
- labels: `area:realtime`, `area:session`
- 作業: `threshold/prefixPaddingMs/silenceDurationMs`を設定から注入。`create_response=true`, `interrupt_response=true`。Semantic VADは対象外。
- 依存: 4-2

### 6-2. barge-in動作検証
- labels: `area:diagnostics`
- 受け入れ条件: assistant再生中にユーザーが割り込める。割り込み後に古いresponseが停止する。
- 依存: 6-1

---

## Phase 7 — VIA統合
Milestone: `Phase 7: VIA Integration`

### 7-1. ConversationControllerとCarVoiceInteractionSessionのライフサイクル接続
- labels: `area:via`, `area:session`
- 作業: 終了処理の順序（response cancel → DataChannel close → PeerConnection close → AudioDeviceModule release → audio focus release → Voice Plate hide）をコード化。`onHide()`と完全終了を区別する。
- 依存: 6-2

### 7-2. Cancel経路の網羅
- labels: `area:session`
- 受け入れ条件: 全conversation状態からcancelでき、microphone/remote trackのどちらかだけ残る失敗がない。
- 依存: 7-1

### 7-3. 異常系（network切断・reconnection）
- labels: `area:realtime`, `bug`
- 作業: 切断時にaudio captureを残さない。再接続のリトライ回数・バックオフ・`FAILED`への遷移条件を数値として定義する（レビューで指摘した未定義点）。
- 依存: 7-1

---

## Phase 8 — function calling基盤
Milestone: `Phase 8: Tool Calling Foundation`

### 8-1. DeviceToolExecutor パイプライン (:tools)
- labels: `area:tools`
- 作業: Parse → Schema validation → Local policy → UX restriction → Android実行 → function_call_output の一連をmockツールで動作確認。モデル出力からCarPropertyManager等へ直接アクセスするコードを禁止するレビューチェックリストを添付。
- 依存: 3-4

### 8-2. call ID往復とモデル応答再開
- labels: `area:tools`, `area:realtime`
- 依存: 8-1

---

## Phase 9 — YouTube tool
Milestone: `Phase 9: YouTube Tool`

### 9-1. open_youtube_search スキーマ登録
- labels: `area:tools`
- 依存: 8-2

### 9-2. ACTION_VIEW実行とURLエンコード
- labels: `area:tools`
- 受け入れ条件: queryはURI componentとして安全にencode（文字列連結禁止）。handler確認をIntent実行前に行う。結果は`OPENED/NO_HANDLER/INVALID_ARGUMENT/NOT_ALLOWED/FAILED`のいずれか。例外catchを`OPENED`にしない。**ユニットテストで例外時に`OPENED`を返さないことを検証する。**
- 依存: 9-1

### 9-3. ローカルTTS先読み発話の禁止確認
- labels: `area:tools`
- 受け入れ条件: tool実行前にAndroid TTSで説明しない。ユーザーへの説明はRealtimeモデルに統一。
- 依存: 9-2

---

## Phase 10 — 異常系・受け入れ確認
Milestone: `Phase 10: Hardening & Acceptance`

### 10-1. AAOS異常系一式
- labels: `bug`, `area:via`
- 対象: handlerなし / networkなし / credential失敗 / WebRTC失敗 / microphone permission拒否 / audio device初期化失敗 / 走行中UX restriction。
- 依存: 7-3, 9-3

### 10-2. 起動時診断画面の完成
- labels: `area:diagnostics`
- 作業: 21節の全項目（fingerprint, ROLE_ASSISTANT, audio device, AEC, libwebrtc commit, backend reachability, PeerConnection/ICE state, browser handler等）を実装。
- 依存: 10-1

### 10-3. 受け入れ条件（25節）E2Eチェックリスト実施
- labels: `documentation`
- 作業: 25節の全項目をチェックリスト化し、実機/エミュレータで消し込む。
- 依存: 10-2

---

## Issue数サマリ
Phase 0: 3 / Phase 1: 4 / Phase 2: 3 / Phase 3: 5 / Phase 4: 2 / Phase 5: 2 / Phase 6: 2 / Phase 7: 3 / Phase 8: 2 / Phase 9: 3 / Phase 10: 3
**合計 32 Issue**
