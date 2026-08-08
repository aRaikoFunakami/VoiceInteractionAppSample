# libwebrtc — 採用方針

## 決定

Google WebRTC をソースから自前ビルドせず、`io.getstream:stream-webrtc-android` の
プレビルドAARを固定バージョンで採用する（[dev-plan.md](../../docs/dev-plan.md) Phase 0 決定事項）。

理由: ソースからのビルドは depot_tools + `gclient sync`（数GBのフェッチ）+ ninja が必要で、
ABIごとに数十分〜数時間かかる。本サンプルは技術評価が目的であり、ビルドインフラの構築自体を
目的としない。

## 固定バージョン

[VERSION](./VERSION) を参照。

- ライブラリ: `io.getstream:stream-webrtc-android:1.3.10`
- 対応ABI: `arm64-v8a`, `x86_64`（32bit は含めない。要求が確認されるまで追加しない）
- ライセンス: Stream側の追加コードは Apache 2.0。WebRTC本体はBSD由来。

## 既知の制約（正直に書く）

`stream-webrtc-android` のリリースノートは、取り込んだ Google WebRTC の正確な commit SHA を
公開していない。リリースノート上で確認できるのは WebRTC の milestone（例: `m125.x`）までで、
このリポジトリの他の依存関係のように commit SHA 単位で再現性を主張することはできない。

再現性が必要になった場合（実機AEC評価の結果を特定のWebRTC実装に紐づけて記録する必要が出た場合など）は、
以下のいずれかを検討する。

1. `stream-webrtc-android` の当該バージョンのタグ付きソースを直接参照し、vendor した WebRTC のcommitを
   ライブラリのビルドスクリプトから特定する。
2. Google WebRTC を自前ビルドする構成に切り替える（当初計画の代替案）。

現時点ではサンプルアプリの技術評価という目的に対して、ライブラリバージョン単位の固定で十分と判断する。

## HEAD追従の禁止

CIごとに最新版を取得する構成は禁止する。バージョンを上げる場合は本ファイルと `VERSION` を
更新するPRを個別に作る。
