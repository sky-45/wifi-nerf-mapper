import unittest
import math
import gzip
import json
import os
import sys

sys.path.insert(0, "/home/antigravity/wifi_env_mapper")

from reconstruction.pipeline import (
    spatial_wavelength_binning,
    extract_multiband_wall_markers,
    extract_rtt_wall_reflections,
    NeRFMap2DSolver,
    generate_synthetic_mapping_session
)

class TestWifiMapperPipeline(unittest.TestCase):

    def setUp(self):
        self.events = generate_synthetic_mapping_session(duration_s=30.0)

    def test_events_generation(self):
        self.assertGreater(len(self.events), 50)
        types = {e["event_type"] for e in self.events}
        self.assertIn("mag_baro_pdr", types)
        self.assertIn("wifi_scan_batch", types)
        self.assertIn("connected_probe", types)
        self.assertIn("wifi_rtt", types)

    def test_gzip_compression_budget(self):
        raw_lines = "\n".join(json.dumps(e) for e in self.events).encode("utf-8")
        compressed = gzip.compress(raw_lines)
        reduction = (1.0 - (len(compressed) / len(raw_lines))) * 100.0
        # Gzip should achieve at least 80% compression on repetitive JSON telemetry
        self.assertGreater(reduction, 80.0)

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

if __name__ == "__main__":
    unittest.main()
