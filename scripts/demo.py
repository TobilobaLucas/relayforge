"""Exercise retries, idempotency, dead letters, and replay against the Compose stack."""

import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

API = os.environ.get("RELAY_API_URL", "http://localhost:8080")
RECEIVER = os.environ.get("RELAY_RECEIVER_URL", "http://localhost:9000")
RECEIVER_ENDPOINT = os.environ.get("RELAY_RECEIVER_ENDPOINT", "http://receiver:9000")


def request(method, url, token=None, body=None, key=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["Idempotency-Key"] = key
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {url}: HTTP {error.code}: {error.read().decode()}") from error


def wait_for_delivery(token, event_id, expected):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        page = request("GET", f"{API}/api/v1/deliveries?eventId={event_id}", token)
        for delivery in page["items"]:
            if delivery["status"] == expected:
                return delivery
        time.sleep(0.25)
    raise AssertionError(f"Delivery did not reach {expected} within 60 seconds")


def main():
    token = os.environ.get("RELAY_ADMIN_TOKEN")
    if not token:
        env_file = Path(__file__).resolve().parents[1] / ".env"
        values = dict(line.split("=", 1) for line in env_file.read_text().splitlines() if line and not line.startswith("#"))
        token = values["RELAY_ADMIN_TOKEN"]
    run = uuid.uuid4().hex[:10]
    project = request("POST", API + "/api/v1/admin/projects", token, {"name": "Demo " + run})
    key = project["apiKey"]
    print("Created a project with an isolated API key.")
    for mode in ("flaky", "reject"):
        path = "/hooks/" + mode + "-" + run
        endpoint = request("POST", API + "/api/v1/endpoints", key, {
            "url": RECEIVER_ENDPOINT + path, "description": mode + " receiver", "eventTypes": ["demo." + mode],
        })
        request("POST", RECEIVER + "/configure", body={"path": path, "secret": endpoint["signingSecret"], "mode": mode})
        event_body = {"type": "demo." + mode, "data": {"message": "Hello from RelayForge", "run": run}}
        event = request("POST", API + "/api/v1/events", key, event_body, key="demo-" + mode)
        duplicate = request("POST", API + "/api/v1/events", key, event_body, key="demo-" + mode)
        assert duplicate["duplicate"] and duplicate["eventId"] == event["eventId"]
        print(f"{mode}: repeated submission returned the same event.")
        delivery = wait_for_delivery(key, event["eventId"], "SUCCEEDED" if mode == "flaky" else "DEAD")
        if mode == "flaky":
            assert delivery["attemptCount"] == 3
            print("flaky: receiver failed twice, then succeeded on attempt 3.")
        else:
            assert delivery["attemptCount"] == 1
            request("POST", RECEIVER + "/configure", body={"path": path, "secret": endpoint["signingSecret"], "mode": "ok"})
            request("POST", f"{API}/api/v1/deliveries/{delivery['id']}/replay", key)
            recovered = wait_for_delivery(key, event["eventId"], "SUCCEEDED")
            assert recovered["replayCount"] == 1 and recovered["attemptCount"] == 2
            print("reject: permanent failure entered dead letters; replay succeeded after fixing the receiver.")
        attempts = request("GET", f"{API}/api/v1/deliveries/{delivery['id']}/attempts", key)
        print("  history: " + ", ".join(f"{a['attemptNumber']}:{a['outcome']}" for a in attempts))
    print("Demo passed. Inspect signature verification with: docker compose logs receiver")


if __name__ == "__main__":
    main()
