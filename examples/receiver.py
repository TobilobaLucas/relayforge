"""A local demonstration receiver. Uses only the Python standard library.

Configuration is deliberately unauthenticated for the loopback-only demo.
Do not expose this receiver as a public service.
"""

import hashlib
import hmac
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

routes = {}
lock = threading.Lock()


class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # The default logger would print request paths; use our structured log.

    def reply(self, status, value=None):
        body = json.dumps(value or {}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"status": "UP"})
        else:
            self.reply(404)

    def do_POST(self):
        self.connection.settimeout(15)
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 73728:
                return self.reply(413)
            raw = self.rfile.read(length)
            if self.path == "/configure":
                config = json.loads(raw)
                if config["mode"] not in {"ok", "flaky", "reject"} or not config["path"].startswith("/hooks/"):
                    return self.reply(400)
                with lock:
                    previous = routes.get(config["path"], {})
                    routes[config["path"]] = {
                        "secret": config["secret"], "mode": config["mode"],
                        "attempts": previous.get("attempts", 0), "processed": previous.get("processed", set()),
                    }
                return self.reply(200, {"configured": True})

            with lock:
                config = routes.get(self.path)
                if config is None:
                    return self.reply(404)
                parts = dict(part.split("=", 1) for part in self.headers.get("Webhook-Signature", "").split(","))
                timestamp = int(parts["t"])
                delivery_id = self.headers["Webhook-Id"]
                message = f"{timestamp}.{delivery_id}.".encode() + raw
                expected = hmac.new(config["secret"].encode(), message, hashlib.sha256).hexdigest()
                verified = abs(time.time() - timestamp) <= 300 and hmac.compare_digest(expected, parts["v1"])
                if not verified:
                    return self.reply(401, {"error": "invalid signature"})
                config["attempts"] += 1
                duplicate = delivery_id in config["processed"]
                status = 204 if duplicate or config["mode"] == "ok" else 400 if config["mode"] == "reject" else 503 if config["attempts"] <= 2 else 204
                if status == 204:
                    config["processed"].add(delivery_id)
                print(json.dumps({"deliveryId": delivery_id, "signatureVerified": True, "status": status, "duplicate": duplicate}), flush=True)
            if status == 204:
                self.send_response(204)
                self.end_headers()
            else:
                self.reply(status)
        except (ValueError, KeyError, TypeError):
            self.reply(400)


if __name__ == "__main__":
    print('Demo receiver listening on :9000', flush=True)
    ThreadingHTTPServer(("0.0.0.0", 9000), Receiver).serve_forever()
