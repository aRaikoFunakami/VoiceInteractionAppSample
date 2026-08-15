# 動かし方

このサンプルを実際にAAOS Emulator上で動かす手順。Session Brokerはスコープ外なので、
`backend/local_broker.py` をあなたのMac上で動かして代用する。

## 前提

- AAOS Automotive Emulator（`Automotive_1408p_landscape` 等）が起動していること
- エミュレータの Extended Controls > Microphone で **Enable Host Microphone Access** が ON
  になっていること（そうしないと実際にマイクの音が入らない — 詳細はこのセッションの
  会話ログ参照）
- `OPENAI_API_KEY` を環境変数として設定していること（**Androidアプリには一切渡らない** —
  ホスト側でBrokerだけが使う）

## 1. ローカルBrokerを起動する

```bash
export OPENAI_API_KEY=sk-...
python3 backend/local_broker.py
```

`local_broker listening on :8787` と出れば準備完了。**動かしている間はターミナルを開いたままにする。**

## 2. アプリをインストールする

```bash
./gradlew :app:installDebug
```

Android Studioの **Run** ボタンでも同じことができる。ただしRunは唯一のランチャー画面
（`DiagnosticsActivity`、診断画面）を自動起動するだけで、音声アシスタント本体は起動しない
——それは通常のActivityではなく `VoiceInteractionService` なので、Run/デバッグの対象には
ならない。起動方法はステップ6を参照。

## 3. マイク権限を付与する（AAOSは複数ユーザー — 全ユーザーに付与する）

```bash
adb shell pm list users
# 表示された全User IDに対して実行する（例: 0, 10）
adb shell pm grant --user 0  com.example.voiceinteractionappsample android.permission.RECORD_AUDIO
adb shell pm grant --user 10 com.example.voiceinteractionappsample android.permission.RECORD_AUDIO
adb shell am force-stop com.example.voiceinteractionappsample
```

（`am force-stop` が必要な理由: 既に起動済みのプロセスは権限を後から付与しても即座には
反映されないことがある — 実機検証で見つかった挙動。）

## 4. 既定のVoice Interaction Appとして登録する

**GUIから（実機検証済み・こちらでOK）**: Settings > Assistant & voice > 「Digital assistant
app」の**行のテキスト部分**をタップ → ピッカーで「VoiceInteractionAppSample」のラジオボタンを
タップ → **直後に出る確認ダイアログ（「The assistant will be able to read information
about apps...」）で必ずOKを押す**。ラジオボタンをタップしただけではまだ確定しない —
このダイアログでキャンセル/離脱すると選択前の状態に戻る。OKまで押せば
`voice_interaction_service`と`assistant`ロールの両方が正しく切り替わることを確認済み。
（この行の右端に出る歯車アイコンはこのアプリ自身の接続先サーバー設定に飛ぶ — 手順5参照。
Issue #43より前は`settingsActivity`未設定でGoogle Assistant自身の設定に飛んでいたが、
現在は無関係ではない。）

**⚠️ OKを押した後、画面を出入りすると「Digital assistant app: None」と表示されることが
ある** — これはCar Settings UI側の表示バグで、実体は壊れていない（実機検証済み:
`adb shell settings get secure voice_interaction_service` / `assistant` は両方とも正しく
このアプリを指したまま、`dumpsys voiceinteraction`のmComponent/mBoundも正常、実際に
`cmd voiceinteraction show`で起動もする）。サードパーティ製アシスタントアプリの名前を
このUIが解決できずNoneに落ちているだけと見られる。気にせず動作確認を続けてよい。

**adbから（同じ効果、`voice_interaction_service`のみ更新）**:

```bash
adb shell settings put secure voice_interaction_service \
  "com.example.voiceinteractionappsample/com.example.voiceinteractionappsample.via.VoiceInteractionServiceImpl"
```

**⚠️ エミュレータをコールドブートするとこの設定はGoogle Assistantに戻る**（アプリ自体や
RECORD_AUDIO権限は消えない — `voice_interaction_service`のsecure settingだけがリセット
される、実機検証で確認済み）。コールドブート後はGUIかadbのどちらかで選び直すこと。
Settings > Assistant & voice の「Voice input」行が消えていたらこれが原因。

## 5. 接続先サーバーを切り替える（OpenAI ⇄ ローカル、任意）

デフォルトはOpenAI Realtime（`backend/local_broker.py`向け）。`local_realtime_llm`のような
ローカルサーバーに向けたい場合、再ビルド不要で設定画面から切り替えられる（Issue #43）。

**GUIから（実機検証済み）**: 手順4で「Digital assistant app」にこのアプリを選択済みの状態
にした上で、**同じ行の右端に出る歯車アイコン**をタップ →「Realtime Server」画面が開く。
OpenAI Realtime / Local のどちらかを選び、Local選択時はローカルサーバーのホスト（例:
`10.0.2.2`、AVDからホストPCを指す予約アドレス）を入力してSAVE。

**adbから直接開く**（歯車を探さず一発で確認したいとき）:

```bash
adb shell am start -n com.example.voiceinteractionappsample/.ServerSettingsActivity
```

**⚠️ 歯車アイコンが出ない場合、まず端末に入っているAPKが最新かを疑う。** 歯車は
「`android:supportsAssist=true`」「`android:settingsActivity`が設定済み」「このアプリが
実際にDigital assistant appとして選択済み」の3条件が揃って初めて表示される — コードを
直しても、端末に入っているAPKが古い（再ビルド前の）ままだと出ない（実機で発見:
`ServerSettingsActivity`追加直後、再ビルドせず動作確認しようとして再現した）。疑わしい
ときは対象アプリのコンポーネント一覧に出ているか確認する:

```bash
adb shell dumpsys package com.example.voiceinteractionappsample | grep ServerSettingsActivity
```

何も出なければ古いAPKのまま。再ビルドして入れ直す:

```bash
./gradlew :app:assembleDebug -q
```

```bash
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

## 5-2. Local Voice Agent(完全オンデバイス)を使う(issue #46〜#50)

第 3 のモードとして、サーバーを一切使わずデバイス内だけで動く Local Voice Agent を選べる
(STT: SenseVoice / LLM: Gemma 4 E2B / TTS: supertonic-3-ja / 音響処理: WebRTC APM)。
OpenAI モードと違い API キーも Broker もネットワークも不要。

**初回セットアップ(モデル配置、約 3GB)**:

```bash
./scripts/fetch_sherpa_onnx.sh
```

```bash
./scripts/fetch_local_audio_engine.sh
```

```bash
./scripts/fetch_gemma.sh && ./scripts/fetch_stt_models.sh && ./scripts/fetch_supertonic.sh
```

- 前半 2 本はビルドに必要な AAR / .so を取得する。**最初の Gradle sync 前に実行しておく**こと
  (無いと `Null extracted folder for artifact` でビルドが落ちる)。
- 後半 3 本はモデルを `models/` にキャッシュし、adb 接続中のデバイスへ
  `/data/local/tmp/{llm,stt,tts}/` に push する(デバイス未接続なら再実行で push だけ行う)。
- Gemma は 2.6GB あるので初回ダウンロードは時間がかかる。

**AVD 要件(実測値)**: arm64-v8a の automotive イメージ、RAM 8GB。`/data` は config.ini の
宣言が 16GB でも実際は 10GB 程度しか確保されないことがある(実測: 10GB 中モデル配置後の
空き 4.9GB)。会話セッション中のアプリ実測 RSS は約 4GB。

**切り替え**: 手順 5 と同じ設定画面(歯車アイコン or `am start ...ServerSettingsActivity`)で
「Local Voice Agent (on-device)」を選んで SAVE。ホスト入力は不要(無効化される)。
モデル未配置のまま保存すると警告 Toast が出る(保存自体は可能、後から push すればよい)。

**会話**: 手順 6 と同じ(マイクアイコン)。初回 PTT はモデルロードで**数秒〜10 秒程度
WORKING 表示のまま待つ**(2 回目以降は即座)。挨拶「こんにちは、何か御用ですか」が表示されたら
話しかける。「猫の動画を見せて」のような依頼で YouTube 検索が開く(ツールコール、issue #50)。
タイムアウトは OpenAI モードと同じ(無音 10 秒 / 最大 2 分)で、自動終了時は Voice Plate も
自動で閉じる。

**⚠️ 運用上のはまりどころ(実機で確認済み)**:

- APK を入れ直すと既定アシスタント登録が Google Assistant に戻る(手順 4 をやり直す)。
- `connectedAndroidTest` を実行するとテスト後にアプリがアンインストール→再インストールされ、
  **アシスタント登録もサーバーモード設定も消える**。テスト後に手動確認する場合は
  手順 4〜5 の再設定が必要。
- x86_64 AVD では音響エンジン(.so)が無いため LOCAL_AGENT は ERROR 表示になる(仕様。
  `third_party/local_audio_engine/README.md` 参照)。
- 実マイクでの会話にはエミュレータの Extended Controls > Microphone で
  「Enable Host Microphone Access」等の有効化が必要(手順 6 の注意と同じ)。

## 6. 会話を始める

**Android Studio / エミュレータ画面から（推奨）**: エミュレータ画面右下、音量調整の右にある
**マイクアイコンをクリック**する。物理PTTボタンの代わりにこれがトリガーになっている
（実機検証済み）。マウス操作だけで完結し、adbコマンドは不要。

![マイクアイコンの位置](images/mic_button_location.png)

拡大するとこの位置（画面右下の角）:

![マイクアイコンの拡大](images/mic_button_closeup.png)

クリック前（グレー・非アクティブ）とクリック後（青・アクティブ、Voice Plateが
"LISTENING"を表示）の比較:

![クリック前後の比較](images/mic_button_before_after.png)

コマンドラインから同じことをする場合:

```bash
adb shell cmd voiceinteraction show
```

Voice Plateが左上に表示され、状態（LISTENING/THINKING/SPEAKING/WORKING/ERROR）が
実際のOpenAI Realtime接続の進行に応じて切り替わる。ホストのマイクに向かって話すと、
アシスタントの音声が返ってくる（実際にMacのスピーカーから聞こえる）。

「Queenのライブ動画を見たい」のようにお願いすると、`open_youtube_search` toolが呼ばれる。

**⚠️ 事前にChromeを手動でインストールしておくこと。** このAAOSイメージには標準でブラウザ
が無い（実車と同様の設計）。素の状態で`open_youtube_search`を呼ぶと、AAOS標準のLink
Viewer（QRコード表示、スマホ側に引き渡す仕組み）に着地する — これはこれで正しい・意図
された動作だが、実際にアプリ内でページを開く様子を確認したい場合は次の手順でChromeを
入れる：

```bash
adb install /path/to/ChromePublic.apk
adb shell pm enable --user 0  org.chromium.chrome
adb shell pm enable --user 10 org.chromium.chrome
```

初回のみ、Chromeの利用規約同意画面が出る（`am start -n org.chromium.chrome/com.google.android.apps.chrome.Main`
などで一度起動し、画面から同意する — この同意はユーザー自身が行うこと）。以降は
`open_youtube_search`が実際にChromeでYouTube検索結果を開く。

（実機で確認済み: AAOSは`ACTION_VIEW`の implicit intent 解決からChromeのような一般アプリ
を除外するため、`OpenYouTubeSearchTool`はコンポーネントを明示指定（`setClassName`）して
起動している。Chromeが入っていない環境では`NO_HANDLER`になるだけで、クラッシュはしない。）

## 終了するには（重要）

ステータスバーのマイクアイコンをもう一度タップする（Googleアシスタントと同じtoggle-to-stop）。
会話中に再タップすると`hide()`が呼ばれ、確実に終了する。コマンドラインからは:

```bash
adb shell cmd voiceinteraction hide
```

**⚠️ エミュレータの画面をスリープさせても会話は止まらない。** 課金を心配してスリープ
させるのは効果が無い（実際に確認済み — スリープ中もマイクは録音を続け、WebRTC接続も
生きたままだった）。必ず上記のいずれかの方法で明示的に終了させること。

## 7. 診断画面（任意）

```bash
adb shell am start -n com.example.voiceinteractionappsample/com.example.voiceinteractionappsample.diagnostics.DiagnosticsActivity
```

build fingerprint、登録中のVoiceInteractionService、AEC対応状況、libwebrtcバージョン、
バックエンド到達性などが一覧で見える。

## 既知の制約

- `backend/local_broker.py` は認証なしのループバック専用デモ。実運用のSession Brokerを
  作る場合は `docs/broker-contract.md` の認証方式決定が先。
- `onHide()` を会話の完全終了として扱う簡略実装（`CarVoiceInteractionSession` のkdoc参照）。
- AECの合否判定自体は未確定（`docs/aec-device-profiles.md` 参照、実車評価待ち）。
  LOCAL_AGENT モードの AEC(オンデバイス AEC3)も同じ Tests A–E の枠組みで実車評価する。
- LOCAL_AGENT の実マイク音声での会話・barge-in はエミュレータの音響経路の制約により
  自動テストできていない(発話注入による E2E は `LocalAgentE2eTest` で自動化済み)。
  実機・実音声での確認は人間による評価が必要(docs/local-voice-agent-dev-plan.md §8.2)。
