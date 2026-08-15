#!/usr/bin/env bash
# STT モデル（SenseVoice ja 対応, int8）+ Silero VAD を取得し端末へ push する。
set -euo pipefail
cd "$(dirname "$0")/.."

ASR=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${ASR}.tar.bz2"
VAD=silero_vad_v5.onnx
VAD_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${VAD}"
DEVICE_DIR=/data/local/tmp/stt

mkdir -p models
if [ ! -d "models/$ASR" ]; then
  curl -fL --retry 3 -o "models/$ASR.tar.bz2" "$ASR_URL"
  tar -xjf "models/$ASR.tar.bz2" -C models
  rm "models/$ASR.tar.bz2"
fi
[ -f "models/$VAD" ] || curl -fL --retry 3 -o "models/$VAD" "$VAD_URL"

if adb get-state >/dev/null 2>&1; then
  adb shell mkdir -p "$DEVICE_DIR"
  adb push "models/$ASR" "$DEVICE_DIR/" >/dev/null
  adb push "models/$VAD" "$DEVICE_DIR/$VAD" >/dev/null
  echo "pushed to $DEVICE_DIR/"
else
  echo "no device connected; run again with a device to push"
fi
