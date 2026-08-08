# Session Broker 連携契約

Session Broker本体の実装は本リポジトリのスコープ外（[dev-plan.md](./dev-plan.md) 確定事項）。
このドキュメントは Android側が依存する契約だけを固定する。

## エンドポイント

```
POST /api/realtime/session
```

Broker が標準OpenAI APIキーを保持し、OpenAI `POST /v1/realtime/client_secrets` を叩いて
短命credentialを取得し、Androidへ最小限の情報だけを返す。

## レスポンス

```json
{
  "clientSecret": "...",
  "expiresAt": "...",
  "sessionConfigVersion": "..."
}
```

- `clientSecret`: OpenAI Realtime SDP交換（`POST https://api.openai.com/v1/realtime/calls`）で
  `Authorization: Bearer <clientSecret>` として使う短命credential。**ログに出さない**（3-5節）。
- `expiresAt`: ISO-8601。期限切れcredentialを再利用しない（5節）。
- `sessionConfigVersion`: Broker側で使ったセッション設定（モデルID等）の識別子。Androidは
  OpenAIモデルIDをハードコードしないため、これで「今どの設定に接続しているか」を追跡する。

## Android側インターフェース

`:realtime` の `RealtimeCredentialProvider`（[3-2](https://github.com/aRaikoFunakami/VoiceInteractionAppSample/issues/12)）が
この契約を表す。実Brokerが実装されたら `RealtimeCredentialProvider` の別実装を追加するだけで
差し替えられる — `RealtimeWebRtcClient`（3-3）側の変更は不要な設計とする。

## 認証（未確定・要決定）

このレビューで指摘済みの通り、Android → Broker 間の認証方式（デバイス証明書 / mTLS /
アプリ署名検証など）は未確定。**実Brokerを実装する際は認証方式を決めてから着手すること**
— これを決めずに `POST /api/realtime/session` を無認証で公開すると、誰でもBroker経由で
無制限にOpenAI credentialを発行できてしまう。
