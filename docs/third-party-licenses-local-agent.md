# LOCAL_AGENT サードパーティライセンス一覧

LOCAL_AGENT モード(issue #48〜#50)が追加するコンポーネントの一覧。
OpenAI モード側の依存(stream-webrtc-android)は `third_party/libwebrtc/` を参照。

## ネイティブライブラリ(liblocal_audio_engine.so)

`third_party/local_audio_engine/` を参照(WebRTC BSD 3-Clause + 機械生成の依存一覧)。

## アプリ依存ライブラリ

| コンポーネント | バージョン | ライセンス |
|---|---|---|
| sherpa-onnx | 1.13.5 | Apache-2.0 |
| onnxruntime(sherpa-onnx AAR 同梱) | - | MIT |
| LiteRT-LM (`litertlm-android`) | 0.16.0 | Apache-2.0 |

## モデル(アプリ非同梱・adb push 方式)

| モデル | 配布元 | ライセンス |
|---|---|---|
| Gemma 4 E2B (`gemma-4-E2B-it.litertlm`) | Hugging Face `litert-community` | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) |
| SenseVoice int8 (ja) | sherpa-onnx GitHub Releases | Apache-2.0(モデルカード参照) |
| Silero VAD v5 | sherpa-onnx GitHub Releases | MIT |
| supertonic-3-ja int8 | sherpa-onnx GitHub Releases | 配布元のモデルカード参照 |

モデルは APK に同梱せず `scripts/fetch_*.sh` で開発者が取得・配置する(再配布はしない)。
Gemma を製品に組み込む場合は Gemma Terms of Use の確認が必要。
