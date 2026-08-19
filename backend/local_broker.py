#!/usr/bin/env python3
"""
Session Broker本体はスコープ外（docs/broker-contract.md）— これはAVDで実際に動かして
見るための最小ローカル代替。標準OpenAI APIキーはこのスクリプトを実行するホスト側だけが持ち、
Androidアプリには一切渡さない（渡すのはOpenAIから発行された短命ephemeral secretのみ）。

使い方:
    export OPENAI_API_KEY=sk-...
    python3 backend/local_broker.py

AAOS Emulator からは http://10.0.2.2:8787/api/realtime/session でアクセスできる
（10.0.2.2はAndroid EmulatorがホストPCのlocalhostを指すための予約アドレス）。
実機の場合は10.0.2.2が使えないため、起動時に表示するLAN IPをRealtime Server設定画面の
ホスト欄に入力すること。

ponytail: 認証なし、ローカルループバック専用のデモ実装。実Brokerを作る場合は
docs/broker-contract.md の「認証（未確定・要決定）」を先に決めること。
"""
import json
import os
import socket
import urllib.error
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8787
MODEL = "gpt-realtime-2.1"


def _lan_ip() -> str:
    """LANに出ていく際に使われるIP。実機の設定画面に入力するホストはこれ。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # UDPなので実際にパケットは飛ばない
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class BrokerHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/api/realtime/session":
            self.send_error(404)
            return

        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            self.send_error(500, "OPENAI_API_KEY not set on broker host")
            return

        body = json.dumps({"session": {"type": "realtime", "model": MODEL}}).encode("utf-8")
        req = urllib.request.Request(
            "https://api.openai.com/v1/realtime/client_secrets",
            data=body,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                openai_response = json.loads(resp.read())
        except urllib.error.HTTPError as e:
            self.send_error(502, f"OpenAI client_secrets request failed: {e.read().decode()}")
            return

        # OpenAI自身のレスポンスは value/expires_at(unix秒) — このBrokerの契約
        # (clientSecret/expiresAt ISO-8601/sessionConfigVersion) へ変換する。
        expires_at = datetime.fromtimestamp(openai_response["expires_at"], tz=timezone.utc)
        payload = json.dumps({
            "clientSecret": openai_response["value"],
            "expiresAt": expires_at.isoformat(),
            "sessionConfigVersion": MODEL,
        }).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        print(f"[local_broker] {self.address_string()} {format % args}")


if __name__ == "__main__":
    if not os.environ.get("OPENAI_API_KEY"):
        print("ERROR: set OPENAI_API_KEY before running this.")
        raise SystemExit(1)
    lan_ip = _lan_ip()
    print(f"local_broker listening on :{PORT}")
    print(f"  AVD:          http://10.0.2.2:{PORT}")
    print(f"  Real device:  http://{lan_ip}:{PORT}  (enter {lan_ip} as the host in Realtime Server settings)")
    HTTPServer(("0.0.0.0", PORT), BrokerHandler).serve_forever()
