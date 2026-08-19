## UI開発方針

- Android UIアプリは特別な理由がない限り **Kotlin + Jetpack Compose + Material 3** で実装する。
  XML View / Java / Material 2 は既存コードの改修時のみ許容し、新規実装では避ける。
- 文言・リソースは最初から多言語対応を前提に設計する。
  - ハードコードした文字列を使わず `strings.xml` にリソース化する
  - `res/values/strings.xml`（デフォルト）に加え、必要な言語ごとに `res/values-<lang>/strings.xml` を用意する
  - 日付・数値・複数形などロケール依存の表示は `Locale` / ICU の仕組みに委ねる
