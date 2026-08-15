#!/usr/bin/env bash
# supertonic-3 TTS モデル（int8, 129MB, 日本語対応）を取得し端末へ push する。
set -euo pipefail
cd "$(dirname "$0")/.."

NAME=sherpa-onnx-supertonic-3-tts-int8-2026-05-11
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${NAME}.tar.bz2"
DEVICE_DIR=/data/local/tmp/tts

if [ ! -d "models/$NAME" ]; then
  mkdir -p models
  curl -fL --retry 3 -o "models/$NAME.tar.bz2" "$URL"
  tar -xjf "models/$NAME.tar.bz2" -C models
  rm "models/$NAME.tar.bz2"
fi
ls "models/$NAME" | head -5

if adb get-state >/dev/null 2>&1; then
  adb shell mkdir -p "$DEVICE_DIR"
  adb push "models/$NAME" "$DEVICE_DIR/" >/dev/null
  echo "pushed to $DEVICE_DIR/$NAME"
else
  echo "no device connected; run again with a device to push"
fi
