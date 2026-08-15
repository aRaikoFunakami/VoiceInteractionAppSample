#!/usr/bin/env bash
# WebRTC ローカル音響処理エンジン (.so) を取得して localagent/src/main/jniLibs/arm64-v8a/ に配置する。
# ソースは aRaikoFunakami/libwebrtc の local_audio/。ビルドには WebRTC 全体のチェックアウト
# （x86_64 Linux ホスト、~17GB）が必要なため、ビルド済み .so を GitHub Releases から取得する。
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=local-audio-7300
SHA256=e0fa76f87b4756dc7c448d2a11355e6b024a393b8e996ec9ba72bf3c03a5eb60
SO=localagent/src/main/jniLibs/arm64-v8a/liblocal_audio_engine.so
URL=https://github.com/aRaikoFunakami/libwebrtc/releases/download/${VERSION}/liblocal_audio_engine.so

if [ -f "$SO" ] && echo "$SHA256  $SO" | shasum -a 256 -c - >/dev/null 2>&1; then
  echo "local_audio_engine: already present ($SO)"
  exit 0
fi

mkdir -p "$(dirname "$SO")"
curl -fL --retry 3 -o "$SO" "$URL"
echo "$SHA256  $SO" | shasum -a 256 -c -
echo "local_audio_engine: OK ($SO)"
