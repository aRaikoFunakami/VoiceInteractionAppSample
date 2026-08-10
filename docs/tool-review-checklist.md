# DeviceTool レビューチェックリスト（14節）

新しい `DeviceTool` 実装、または `DeviceToolExecutor` まわりの変更をレビューする際に確認する。

- [ ] `execute()` 以外のどこにも、モデル出力（function call引数）から直接
      `CarPropertyManager` / `MediaSession` / Navigation API / `Intent` を呼ぶコードがない
- [ ] `execute()` は `DeviceToolExecutor` 経由でのみ呼ばれる（Parse -> Schema -> Policy -> UX
      を必ず通過してから実行される）
- [ ] `execute()` 内で発生した例外を catch して `ToolOutcomeType.SUCCESS` にしていない
      （12節: 例外を握りつぶして成功扱いにするのが最悪のパターン）
- [ ] `checkPolicy()` / `checkUxRestriction()` が `execute()` より先に評価される順序が
      変わっていない
- [ ] 新しいtoolのschemaに `additionalProperties: false` 相当の制約がある
      （任意のプロパティを無制限に受け付けない）
- [ ] `execute()` の中でAndroid `TextToSpeech` 等のローカル音声合成を使って
      「〜を開きます」等の先読み発話をしていない（15節）— ユーザーへの説明はRealtimeモデルに
      統一する。tool実行とRealtime assistant audioが同時に鳴る事故を防ぐ。
      2026-08時点でリポジトリ全体に `TextToSpeech` の使用箇所はゼロ（`grep -rn TextToSpeech`
      で確認済み、Issue #29）。
