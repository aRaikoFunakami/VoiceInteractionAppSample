# liblocal_audio_engine.so (WebRTC APM ラッパー)

LOCAL_AGENT モード(issue #48)の音響前処理エンジン。WebRTC の Audio Processing Module
(AEC3 + NS + AGC2)だけを切り出した単体 `.so` で、PeerConnection・ネットワークスタックは
含まない。ソースは [aRaikoFunakami/libwebrtc](https://github.com/aRaikoFunakami/libwebrtc)
の `local-audio` ブランチ `local_audio/` ディレクトリ(upstream への変更はルート BUILD.gn の
2 行のみ)。

## 取得方法

バイナリはリポジトリにコミットしない。`scripts/fetch_local_audio_engine.sh` が GitHub
Releases から取得し、sha256 を検証して `localagent/src/main/jniLibs/arm64-v8a/` に配置する。
バージョン・ハッシュは [VERSION](VERSION) に固定してある。

## 制約(重要)

- **arm64-v8a のみ**。x86_64 AVD では `UnsatisfiedLinkError` → LOCAL_AGENT は FAILED 表示に
  graceful degradation する(アプリ自体は正常動作)。x86_64 が必要になったら fork 側の
  Docker ツールチェーンで `target_cpu="x64"` を追加ビルドする。
- **JNI バインド先クラスパスがバイナリにハードコード**されている
  (`com/example/localvoiceagent/LocalAudioEngine`)。`localagent` モジュール内の
  `LocalAudioEngine.kt` はこの FQCN を維持しなければならない(パッケージ名を変えると
  無音で失敗する)。変えたい場合は `.so` の再ビルドが必要。

## ライセンス

- WebRTC 本体: [webrtc-LICENSE](webrtc-LICENSE)(BSD 3-Clause)
- 静的リンクされる依存の一覧(機械生成、fork の `scripts/generate_notices.sh` =
  WebRTC `tools_webrtc/libs/generate_licenses.py` による):
  [webrtc_third_party_licenses.md](webrtc_third_party_licenses.md)
- revision 更新時は fork 側で NOTICE を再生成し、本ディレクトリのファイルを差し替えること
