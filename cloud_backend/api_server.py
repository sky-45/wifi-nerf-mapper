#!/usr/bin/env python3
"""
Reference Cloud Ingestion REST API Server.
Receives batched Gzipped NDJSON telemetry (POST /api/v1/telemetry/batches),
validates idempotency, computes payload & battery metrics, and stores sessions.
"""

import gzip
import json
import os
import time
from typing import Dict, Any, List
from http.server import HTTPServer, BaseHTTPRequestHandler

STORAGE_DIR = os.environ.get("STORAGE_DIR", "./ingested_batches")
os.makedirs(STORAGE_DIR, exist_ok=True)

class TelemetryBatchHandler(BaseHTTPRequestHandler):
    processed_batch_ids = set()

    def do_POST(self):
        if self.path != "/api/v1/telemetry/batches":
            self.send_response(404)
            self.end_headers()
            return

        batch_id = self.headers.get("X-Batch-Id", "unknown")
        session_id = self.headers.get("X-Session-Id", "default")
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
        if content_encoding == "gzip":
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

        self.processed_batch_ids.add(batch_id)

        # Store to disk under session folder
        session_path = os.path.join(STORAGE_DIR, session_id)
        os.makedirs(session_path, exist_ok=True)
        batch_filename = f"batch_{int(time.time())}_{batch_id[:8]}.jsonl"
        with open(os.path.join(session_path, batch_filename), "w", encoding="utf-8") as f:
            for ev in events:
                f.write(json.dumps(ev) + "\n")

        # Telemetry metrics
        raw_kb = len(decompressed_bytes) / 1024.0
        gz_kb = len(raw_bytes) / 1024.0
        ratio = (1.0 - (gz_kb / max(raw_kb, 0.001))) * 100.0

        print(f"[API INGEST] Session={session_id[:8]} Batch={batch_id[:8]} "
              f"Events={len(events)} Raw={raw_kb:.1f}KB Gz={gz_kb:.1f}KB ({ratio:.1f}% saved)")

        # Send response
        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        resp = {
            "status": "accepted",
            "batch_id": batch_id,
            "session_id": session_id,
            "events_ingested": len(events),
            "compressed_bytes": len(raw_bytes),
            "decompressed_bytes": len(decompressed_bytes)
        }
        self.wfile.write(json.dumps(resp).encode())

def run_server(port: int = 8000):
    server = HTTPServer(("0.0.0.0", port), TelemetryBatchHandler)
    print(f"[*] Telemetry Ingestion Server listening on http://0.0.0.0:{port}/api/v1/telemetry/batches")
    server.serve_forever()

if __name__ == "__main__":
    run_server()
