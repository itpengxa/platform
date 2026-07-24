#!/usr/bin/env python3
"""
GEO 验证工具 — 前端静态服务 + API 代理

页面: http://localhost:8899/
API 代理: /api/geo/* → http://localhost:8088/api/geo/*
无需 CORS 配置
"""

import json
import os
import urllib.request
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKEND = os.environ.get("BACKEND_URL", "http://localhost:8088")
PORT = int(os.environ.get("PORT", 8899))


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        # Proxy API requests to Spring Boot backend
        if path.startswith("/api/"):
            return self.proxy_to_backend()

        # Serve static files
        return self.serve_static()

    def do_OPTIONS(self):
        """Handle CORS preflight (though proxy eliminates need, keep for safety)."""
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def proxy_to_backend(self):
        """Forward request to Spring Boot backend and relay response."""
        backend_url = BACKEND + self.path
        try:
            req = urllib.request.Request(backend_url)
            req.add_header("Accept", "application/json")
            with urllib.request.urlopen(req, timeout=30) as resp:
                body = resp.read()
                ct = resp.headers.get("Content-Type", "application/json")

            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Type", ct)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except urllib.error.HTTPError as e:
            body = e.read()
            self.send_response(e.code)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        except urllib.error.URLError as e:
            self.send_response(502)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            err = json.dumps({"error": f"后端连接失败: {e.reason}", "hint": "请确认 Spring Boot 服务在 " + BACKEND + " 上运行中"}).encode()
            self.wfile.write(err)

    def serve_static(self):
        path = self.path.lstrip("/")
        if not path or path == "":
            path = "geo-validator.html"
        filepath = ROOT / path
        if not filepath.exists() or not filepath.is_file():
            self.send_response(404)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"Not found")
            return
        content = filepath.read_bytes()
        ct = "text/html; charset=utf-8"
        if path.endswith(".js"):
            ct = "application/javascript; charset=utf-8"
        elif path.endswith(".css"):
            ct = "text/css; charset=utf-8"
        elif path.endswith(".png"):
            ct = "image/png"
        elif path.endswith(".svg"):
            ct = "image/svg+xml"
        self.send_response(200)
        self.send_header("Content-Type", ct)
        self.end_headers()
        self.wfile.write(content)

    def log_message(self, fmt, *args):
        msg = fmt % args
        if "200" in msg and "/api/" in msg:
            print(f"  ✅ {msg}")
        elif "/api/" in msg:
            print(f"  ❌ {msg}")
        elif msg:
            print(f"  {msg}")


def main():
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"{'='*55}")
    print(f"  🌍 GEO 验证工具")
    print(f"{'='*55}")
    print(f"  页面:     http://localhost:{PORT}/")
    print(f"  API 代理 → {BACKEND}/api/geo/v1/...")
    print(f"  状态:     ✅ 运行中")
    print(f"  关闭:     Ctrl+C")
    print(f"{'='*55}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n关闭服务器")
        server.server_close()


if __name__ == "__main__":
    main()
