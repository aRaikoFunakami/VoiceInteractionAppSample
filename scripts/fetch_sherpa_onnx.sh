#!/usr/bin/env bash
# sherpa-onnx の Android AAR を取得して localagent/libs/ に配置する（バージョン・ハッシュ固定）。
# AAR は 47MB あるためリポジトリにはコミットしない（.gitignore 済み）。
# 注意: :localagent は compileOnly、ランタイム同梱は :app 側（docs/local-voice-agent-dev-plan.md §2.4-1）。
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION=1.13.5
SHA256=6419cd8bc983e0c4fab06067f0fe0313fdc0f7103818ac1e7a08d50787b7a82b
AAR=localagent/libs/sherpa-onnx-${VERSION}.aar
URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-${VERSION}.aar

if [ -f "$AAR" ] && echo "$SHA256  $AAR" | shasum -a 256 -c - >/dev/null 2>&1; then
  echo "sherpa-onnx: already present ($AAR)"
  exit 0
fi

mkdir -p "$(dirname "$AAR")"
curl -fL --retry 3 -o "$AAR" "$URL"
echo "$SHA256  $AAR" | shasum -a 256 -c -
echo "sherpa-onnx: OK ($AAR)"
