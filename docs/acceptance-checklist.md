# 受け入れ条件チェックリスト（25節）

Issue #32 (10-3)。元の実装計画25節の全項目を、今回のセッションで実際に検証した内容と対応付ける。
「✅ 実証済み」は実機/実APIでの検証があるもの。「⚠️」は部分的、または別の形で保証しているもの。
「❌ 未実施」は本セッションでは着手していないもの。

| # | 受け入れ条件 | 状態 | 根拠 |
|---|---|---|---|
| 1 | VIAが既定assistantとして起動できる | ✅ | Issue #7: `dumpsys voiceinteraction`でmBound=true確認 |
| 2 | PTT/TTTからVoice Plateが表示される | ✅ | Issue #7: `cmd voiceinteraction show`でVoicePlateViewが"LISTENING"を描画、スクリーンショット確認 |
| 3 | OpenAI標準APIキーがAPKに存在しない | ✅ | 設計上MockRealtimeCredentialProviderはephemeral secretのみ保持。全リポジトリgrepでAPIキー埋め込みなし確認 |
| 4 | AndroidからOpenAI RealtimeへWebRTC接続できる | ✅ | Issue #13: 実API相手にSDP交換、SignalingState.STABLE到達 |
| 5 | ユーザー音声がWebRTC audio trackで送信される | ✅ | Issue #16: WebRTC統計でoutbound-rtp bytesSent > 0 |
| 6 | assistant audioがWebRTC remote audioとして再生される | ✅ | Issue #16 + フォローアップ: 統計上のbytesReceived > 0に加え、ユーザーが実際に音声を聴取して確認 |
| 7 | assistant再生中にもmicrophone captureが継続する | ✅ | Issue #16: 双方向のbytesSent/bytesReceivedが同一ウィンドウ内で同時に確認された |
| 8 | assistant自身の音声でuser turnを連続生成しない | ✅ | Issue #19 Test A/D: 誤VADトリガー0件（音量3段階、route 2条件） |
| 9 | assistant再生中にユーザーが割り込める | ✅ | Issue #21: 実発話でinput_audio_buffer.speech_started検出を確認 |
| 10 | 割り込み後に古いassistant responseが停止する | ✅ | Issue #21: speech_started後にresponse.doneが追従することをassertionで確認 |
| 11 | 「Queenのライブ動画を見たい」でopen_youtube_searchが呼ばれる | ✅ | Issue #32: テキスト注入によるシミュレーションで実APIから確認（実音声認識自体は#9/#10で別途確認済み） |
| 12 | 「Queenについて教えて」ではtoolが呼ばれない | ✅ | Issue #32: 同上、informationalな問いではtool未呼び出しを確認 |
| 13 | YouTube検索queryが正しくURL encodeされる | ✅ | Issue #28: 空白/非ASCII/URL構造を壊す入力に対するユニットテスト |
| 14 | Intent handlerがない端末でクラッシュしない | ✅ | Issue #28: 実機でblank queryおよび通常queryの両方でクラッシュなし確認 |
| 15 | ACTION_VIEW失敗をtool成功として返さない | ✅ | Issue #28: ActivityNotFoundExceptionをNO_HANDLERにマップ（OPENEDにはならない）実装+テスト |
| 16 | network disconnect後にmicrophoneが残らない | ⚠️ | Issue #24: ICE FAILED/DISCONNECTED枯渇時にcancel()と同じ完全クリーンアップが走る配線・ユニットテストは実施。**実際のネットワーク切断によるライブ試験は未実施**（エミュレータに clean disconnect コマンドがない） |
| 17 | conversation終了後にAudioDeviceModule、PeerConnection、DataChannelがreleaseされる | ✅ | Issue #22/#23: 実際にCONNECTED状態からcancel()を呼び、全リソース解放とidempotencyを実機で確認 |
| 18 | 生音声を永続保存しない | ✅ | 全リポジトリgrep（FileOutputStream/.wav/MediaRecorder等）で該当コードなしを確認 |
| 19 | libwebrtc commitが再現可能な形で記録されている | ⚠️ | third_party/libwebrtc/README.mdに記録。**ただし採用したプレビルドライブラリ(stream-webrtc-android)は正確なWebRTC commit SHAを公開していない** — milestone(m125.x)までの記録に留まる。この制約は README.md に正直に明記済み |

## 集計
- ✅ 実証済み: 17 / 19
- ⚠️ 部分的: 2 / 19（network切断のライブ試験、libwebrtc commitの正確な記録）
- ❌ 未実施: 0 / 19

## 本セッションで一貫して確認できなかった／スコープ外にした項目（26節と関連）
- Test E以外のAEC合否判定そのもの（19節の通り、実車評価前に固定しない）
- 走行中UX restriction（`DeviceTool.checkUxRestriction()` のフックのみ存在、`CarUxRestrictionsManager` 連携は未実装）
- Session Brokerの実装（本リポジトリのスコープ外、契約のみ固定）
