#!/usr/bin/env bash
# Gemma 4 E2B (LiteRT-LM 形式, 2.6GB) を取得し、接続中のエミュレータ/端末へ push する。
# モデルはゲートなし（HF 認証不要）。リポジトリにはコミットしない（.gitignore 済み）。
set -euo pipefail
cd "$(dirname "$0")/.."

MODEL=gemma-4-E2B-it.litertlm
URL="https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/${MODEL}"
DEVICE_DIR=/data/local/tmp/llm

if [ ! -f "models/$MODEL" ]; then
  mkdir -p models
  curl -fL --retry 3 -o "models/$MODEL" "$URL"
fi
ls -lh "models/$MODEL"

if adb get-state >/dev/null 2>&1; then
  adb shell mkdir -p "$DEVICE_DIR"
  adb push "models/$MODEL" "$DEVICE_DIR/$MODEL"
  adb shell chmod 644 "$DEVICE_DIR/$MODEL"
  echo "pushed to $DEVICE_DIR/$MODEL"
else
  echo "no device connected; run again with a device to push"
fi
