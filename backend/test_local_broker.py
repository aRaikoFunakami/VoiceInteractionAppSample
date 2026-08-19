#!/usr/bin/env python3
"""Minimal self-check for local_broker._lan_ip. Run: python3 backend/test_local_broker.py"""
import socket

from local_broker import _lan_ip

ip = _lan_ip()
socket.inet_aton(ip)  # raises OSError if not a dotted-quad IPv4 address
print(f"OK: _lan_ip() -> {ip}")
