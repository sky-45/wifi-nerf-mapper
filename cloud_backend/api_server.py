#!/usr/bin/env python3
"""
Reference Cloud Ingestion Server.
Receives batched Gzipped NDJSON telemetry via:
1. Mode 1: OCI Object Storage PAR / REST (PUT or POST /fleet/{device_id}/{env_id}/batch_{seq}.ndjson.gz)
2. Mode 2: Standard REST Ingestion API (POST /api/v1/telemetry/batches)
Validates idempotency, computes payload & battery metrics, and stores sessions partitioned by fleet device & environment.
"""

import gzip
import json
import os
import re
import time
from typing import Dict, Any, List, Optional
from http.server import HTTPServer, BaseHTTPRequestHandler

STORAGE_DIR = os.environ.get("STORAGE_DIR", "./ingested_batches")
os.makedirs(STORAGE_DIR, exist_ok=True)

class TelemetryBatchHandler(BaseHTTPRequestHandler):
    processed_batch_ids = set()

    def do_PUT(self):
        self._handle_telemetry_upload(is_put=True)

    def do_POST(self):
        self._handle_telemetry_upload(is_put=False)

    def _handle_telemetry_upload(self, is_put: bool):
        # Validate path: either /api/v1/telemetry/batches or an OCI PAR fleet path /fleet/{device_id}/{env_id}/batch_{seq}.ndjson.gz
        is_rest_api = (self.path == "/api/v1/telemetry/batches")
        fleet_match = re.search(r"/fleet/([^/]+)/([^/]+)/(batch_[^/]+\.ndjson\.gz)", self.path)

        if not is_rest_api and not fleet_match:
            self.send_response(404)
            self.end_headers()
            return

        batch_id = self.headers.get("X-Batch-Id", f"batch_{int(time.time()*1000)}")
        session_id = self.headers.get("X-Session-Id", "default")
        device_id = self.headers.get("X-Device-Id")
        env_id = self.headers.get("X-Environment-Id")
        dwell_state = self.headers.get("X-Dwell-State", "IN_TRANSIT")

        if fleet_match:
            url_dev, url_env, filename = fleet_match.groups()
            device_id = device_id or url_dev
            env_id = env_id or url_env

        content_encoding = self.headers.get("Content-Encoding", "").lower()
        content_length = int(self.headers.get("Content-Length", 0))

        raw_bytes = self.rfile.read(content_length)

        # Idempotency check
        if batch_id in self.processed_batch_ids:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"status": "duplicate_ignored", "batch_id": batch_id}).encode())
            return

        # Decompress if Gzipped
        if content_encoding == "gzip" or raw_bytes[:2] == b"\x1f\x8b":
            try:
                decompressed_bytes = gzip.decompress(raw_bytes)
            except Exception as e:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(json.dumps({"error": f"Invalid gzip: {e}"}).encode())
                return
        else:
            decompressed_bytes = raw_bytes

        # Parse NDJSON lines
        lines = decompressed_bytes.decode("utf-8").splitlines()
        events = []
        envelope = None

        for line in lines:
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
                if obj.get("event_type") == "batch_envelope":
                    envelope = obj
                else:
                    events.append(obj)
            except json.JSONDecodeError:
                continue

        if envelope:
            device_id = device_id or envelope.get("device_id")
            env_id = env_id or envelope.get("environment_id")
            dwell_state = envelope.get("dwell_state", dwell_state)

        device_id = device_id or "unknown_device"
        env_id = env_id or "env_default"

        self.processed_batch_ids.add(batch_id)

        # Store to disk under partitioned fleet/{device_id}/{env_id}/ folder
        destination_path = os.path.join(STORAGE_DIR, "fleet", device_id, env_id)
        os.makedirs(destination_path, exist_ok=True)
        batch_filename = f"batch_{int(time.time())}_{batch_id[:8]}.jsonl"

        with open(os.path.join(destination_path, batch_filename), "w", encoding="utf-8") as f:
            if envelope:
                f.write(json.dumps(envelope) + "\n")
            for ev in events:
                f.write(json.dumps(ev) + "\n")

        # Telemetry metrics
        raw_kb = len(decompressed_bytes) / 1024.0
        gz_kb = len(raw_bytes) / 1024.0
        ratio = (1.0 - (gz_kb / max(raw_kb, 0.001))) * 100.0
        method = "PUT (OCI PAR)" if is_put else "POST (REST)"

        print(f"[{method}] Device={device_id[:8]} Env={env_id} Dwell={dwell_state} "
              f"Batch={batch_id[:8]} Events={len(events)} Raw={raw_kb:.1f}KB Gz={gz_kb:.1f}KB ({ratio:.1f}% saved)")

        # Send response: 201 Created for OCI PUT, 202 Accepted for REST POST
        status_code = 201 if is_put else 202
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Device-Id", device_id)
        self.send_header("X-Environment-Id", env_id)
        self.end_headers()
        resp = {
            "status": "created" if is_put else "accepted",
            "batch_id": batch_id,
            "session_id": session_id,
            "device_id": device_id,
            "environment_id": env_id,
            "dwell_state": dwell_state,
            "events_ingested": len(events),
            "compressed_bytes": len(raw_bytes),
            "decompressed_bytes": len(decompressed_bytes)
        }
        self.wfile.write(json.dumps(resp).encode())

def run_server(port: int = 8000):
    server = HTTPServer(("0.0.0.0", port), TelemetryBatchHandler)
    print(f"[*] Telemetry Ingestion Server listening on http://0.0.0.0:{port}")
    print(f"    - Mode 1: PUT/POST /fleet/{{device_id}}/{{env_id}}/batch_{{seq}}.ndjson.gz (OCI PAR / Object Storage)")
    print(f"    - Mode 2: POST /api/v1/telemetry/batches (REST Ingestion)")
    server.serve_forever()

if __name__ == "__main__":
    run_server()
