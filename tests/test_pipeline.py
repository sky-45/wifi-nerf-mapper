import unittest
import math
import gzip
import json
import os
import sys
import hashlib
import io
from urllib.request import Request, urlopen
from http.server import HTTPServer
import threading

sys.path.insert(0, "/home/antigravity/wifi_env_mapper")

from reconstruction.pipeline import (
    spatial_wavelength_binning,
    extract_multiband_wall_markers,
    extract_rtt_wall_reflections,
    NeRFMap2DSolver,
    generate_synthetic_mapping_session
)
from cloud_backend.api_server import TelemetryBatchHandler

class TestWifiMapperPipeline(unittest.TestCase):

    def setUp(self):
        self.device_id = "test-device-uuid-1234"
        self.env_id = "env_89abcdef01234567"
        self.events = generate_synthetic_mapping_session(
            duration_s=30.0,
            device_id=self.device_id,
            environment_id=self.env_id,
            dwell_state="CAMPING"
        )

    def test_events_generation(self):
        self.assertGreater(len(self.events), 50)
        types = {e["event_type"] for e in self.events}
        self.assertIn("mag_baro_pdr", types)
        self.assertIn("wifi_scan_batch", types)
        self.assertIn("connected_probe", types)
        self.assertIn("wifi_rtt", types)

    def test_device_and_environment_metadata(self):
        """Verifies device_id, environment_id, and dwell_state are stamped on all events."""
        for ev in self.events:
            self.assertEqual(ev.get("device_id"), self.device_id)
            self.assertEqual(ev.get("environment_id"), self.env_id)
            self.assertEqual(ev.get("dwell_state"), "CAMPING")

    def test_jaccard_similarity_and_environment_hash(self):
        """Verifies top-8 BSSID Jaccard clustering and canonical SHA-256 environment hashing."""
        cluster_a = {"AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:04"}
        cluster_b = {"AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:99"}
        cluster_c = {"11:22:33:44:55:01", "11:22:33:44:55:02", "11:22:33:44:55:03", "11:22:33:44:55:04"}

        # Jaccard A & B: 3 intersection / 5 union = 0.60 >= 0.50 (same environment)
        j_ab = len(cluster_a.intersection(cluster_b)) / len(cluster_a.union(cluster_b))
        self.assertAlmostEqual(j_ab, 0.60, places=2)

        # Jaccard A & C: 0 intersection / 8 union = 0.0 (transit / different environment)
        j_ac = len(cluster_a.intersection(cluster_c)) / len(cluster_a.union(cluster_c))
        self.assertEqual(j_ac, 0.0)

        # SHA-256 Canonical Hashing check
        canonical_str = "|".join(sorted(cluster_a))
        sha = hashlib.sha256(canonical_str.encode("utf-8")).hexdigest()
        env_id = f"env_{sha[:16]}"
        self.assertTrue(env_id.startswith("env_"))
        self.assertEqual(len(env_id), 20)

    def test_gzip_compression_budget(self):
        raw_lines = "\n".join(json.dumps(e) for e in self.events).encode("utf-8")
        compressed = gzip.compress(raw_lines)
        reduction = (1.0 - (len(compressed) / len(raw_lines))) * 100.0
        # Gzip should achieve at least 80% compression on repetitive JSON telemetry
        self.assertGreater(reduction, 80.0)

    def test_batch_envelope_ndjson_structure(self):
        """Validates BatchEnvelope NDJSON header and event streaming."""
        envelope = {
            "event_type": "batch_envelope",
            "timestamp_ns": 1700000000000000000,
            "session_id": "test-session-uuid",
            "device_id": self.device_id,
            "environment_id": self.env_id,
            "dwell_state": "CAMPING",
            "batch_seq": 42,
            "start_time_ns": self.events[0]["timestamp_ns"],
            "end_time_ns": self.events[-1]["timestamp_ns"],
            "event_count": len(self.events),
            "battery_pct": 88,
            "battery_current_ua": -180000,
            "step_count_delta": 45
        }

        all_lines = [json.dumps(envelope)] + [json.dumps(e) for e in self.events]
        ndjson_bytes = "\n".join(all_lines).encode("utf-8")
        gz_bytes = gzip.compress(ndjson_bytes)

        # Decompress and verify
        decompressed = gzip.decompress(gz_bytes).decode("utf-8").splitlines()
        first_line = json.loads(decompressed[0])
        self.assertEqual(first_line["event_type"], "batch_envelope")
        self.assertEqual(first_line["device_id"], self.device_id)
        self.assertEqual(first_line["environment_id"], self.env_id)
        self.assertEqual(first_line["dwell_state"], "CAMPING")
        self.assertEqual(first_line["batch_seq"], 42)
        self.assertEqual(len(decompressed), len(self.events) + 1)

    def test_spatial_wavelength_binning(self):
        bins = spatial_wavelength_binning(self.events, grid_size_m=0.10)
        self.assertGreater(len(bins), 10)
        for (bx, by, bssid), rssi in bins.items():
            self.assertIsInstance(bx, int)
            self.assertIsInstance(by, int)
            self.assertLess(rssi, 0.0) # RSSI is negative dBm

    def test_multiband_wall_markers(self):
        markers = extract_multiband_wall_markers(self.events, excess_threshold_db=2.5)
        self.assertTrue(len(markers) > 0)
        for px, py, loss in markers:
            self.assertGreaterEqual(loss, 2.5)

    def test_nerfmap_solver(self):
        solver = NeRFMap2DSolver(room_width_m=6.0, room_height_m=4.0, resolution_m=0.20)
        self.assertEqual(solver.grid.shape, (20, 30))
        cells = solver.march_ray_voxels(0.0, 0.0, 2.0, 2.0)
        self.assertGreater(len(cells), 5)

    def test_cloud_server_rest_and_oci_par_modes(self):
        """Tests local Ingestion Server with Mode 1 (OCI PAR PUT) and Mode 2 (REST POST)."""
        server = HTTPServer(("127.0.0.1", 0), TelemetryBatchHandler)
        port = server.server_address[1]
        server_thread = threading.Thread(target=server.serve_forever)
        server_thread.daemon = True
        server_thread.start()

        try:
            sample_payload = gzip.compress(b'{"event_type":"mag_baro_pdr","timestamp_ns":100}\n')

            # Test Mode 2: Standard REST Ingestion POST
            req_post = Request(
                f"http://127.0.0.1:{port}/api/v1/telemetry/batches",
                data=sample_payload,
                headers={
                    "Content-Type": "application/x-ndjson",
                    "Content-Encoding": "gzip",
                    "X-Batch-Id": "test_batch_post_1",
                    "X-Session-Id": "sess_001",
                    "X-Device-Id": self.device_id,
                    "X-Environment-Id": self.env_id,
                    "X-Dwell-State": "IN_TRANSIT"
                },
                method="POST"
            )
            with urlopen(req_post, timeout=5) as response:
                self.assertEqual(response.status, 202)
                resp_json = json.loads(response.read().decode())
                self.assertEqual(resp_json["status"], "accepted")
                self.assertEqual(resp_json["device_id"], self.device_id)

            # Test Mode 1: OCI Object Storage PAR PUT
            oci_path = f"/fleet/{self.device_id}/{self.env_id}/batch_1.ndjson.gz"
            req_put = Request(
                f"http://127.0.0.1:{port}{oci_path}",
                data=sample_payload,
                headers={
                    "Content-Type": "application/x-ndjson",
                    "Content-Encoding": "gzip",
                    "X-Batch-Id": "test_batch_put_1",
                    "X-Session-Id": "sess_002",
                    "X-Device-Id": self.device_id,
                    "X-Environment-Id": self.env_id,
                    "X-Dwell-State": "CAMPING"
                },
                method="PUT"
            )
            with urlopen(req_put, timeout=5) as response:
                self.assertEqual(response.status, 201)
                resp_json = json.loads(response.read().decode())
                self.assertEqual(resp_json["status"], "created")
                self.assertEqual(resp_json["dwell_state"], "CAMPING")
        finally:
            server.shutdown()
            server.server_close()

if __name__ == "__main__":
    unittest.main()
