# AEC Device Profiles

19節のTest A〜E結果を記録する。合格値は実機評価前に固定しない（19節）— ここは観測事実の記録であり、
「合格」の判定そのものではない。

## Automotive_1408p_landscape (AVD, arm64-v8a, host: Apple Silicon Mac, CoreAudio)

設定: `AecMode.AUTO`、host mic access有効、Simulate Insert Headset ON / Virtual microphone attached。
2026-08-08 実施。

### Test A — assistant再生中・無発話でuser speechが誤検出されないこと

再生内容: "Count slowly from one to twenty" を20秒弱再生、15秒間イベント監視。

| 実行 | falseTriggerCount | totalEventCount |
|---|---|---|
| 1回目 | 0 | 65 |

`input_audio_buffer.speech_started` および role=user の `conversation.item.created` のいずれも検出されず。

### Test D — assistant音量を複数段階へ変更し誤VAD率を測定

`adb shell cmd media_session volume --stream 3 --set <level>` でSTREAM_MUSICを制御し、Test Aと同じ手順を実行。

| STREAM_MUSIC音量 (0-15) | falseTriggerCount | totalEventCount |
|---|---|---|
| 15 (max) | 0 | 65 |
| 8 (mid) | 0 | 53 |
| 2 (low) | 0 | 53 |

全音量段階で誤トリガー0件。

**注記**: assistant再生に実際に使われるaudio streamがSTREAM_MUSICと一致するかは未検証
（JavaAudioDeviceModuleの既定playoutストリームを確認していない）。この設定変更が実際に
assistant音量へ影響したかどうかは、ホスト側で実際に音量差を耳で確認していない限り厳密な保証がない。
音量操作そのものは実行されているが、「本当に効いていたか」は次回セッションで
`dumpsys audio` の再生ストリーム種別を確認して補強する。

### Test B — assistant再生中、通常音量の発話が認識されること
2026-08-08 実施（ユーザーが実際に約9秒間連続して発話）。

タイムライン（response.create送信からの経過ms）:

```
745ms  input_audio_buffer.speech_started
9888ms input_audio_buffer.speech_stopped
9889ms conversation.item.added (role=user)
```

**部分的に確認**: 約9秒間の連続発話が1つのuser conversation itemとしてcommitされることは
確認できた。ただし25秒の観測ウィンドウ内に文字起こし（`*input_audio_transcription*`系イベント）
は到達せず、内容が正しく認識されたかまでは今回の実行では確認できていない
（session configでtranscriptionが有効か、到達が遅いだけかは未切り分け）。

### Test C — assistant再生中の短い割り込みでresponseが中断されること
2026-08-08 実施、Test Bと同一セッション内で発生。**確認できた**:

```
265ms response.created
745ms input_audio_buffer.speech_started   (response.created から480ms後)
780ms response.done                        (speech_started から35ms後に終了)
```

response開始からわずか480msで発話検出、その35ms後にはresponseが完了（実際に鳴った音声は
14ms分のみ）。ほぼ即座の割り込みが実際に発生していることを確認。

**注記**: この実行では最初の応答が非常に短く終わったため（10秒未満）、「長い応答の途中で
割り込む」という本来のシナリオとしてはやや不完全 — response自体がまだ立ち上がったばかりの
段階での割り込みだった。より長く安定して再生している最中の割り込みは今後の課題として残す。

### Test E — 異なるspeaker route / mic route
**未実施**。現在の条件（Simulate Insert Headset ON）以外の比較対象が必要
（Extended Controls > Microphone で "Simulate Insert Headset" をOFFにした状態など）。

## 未確定事項（19節より）
AECの合格値そのものは、OEMハードウェア評価前に固定しない。上記はこのAVD/ホスト条件下での
観測結果であり、実機（Pixel Tablet、OEM HU等）での再評価が必要。
