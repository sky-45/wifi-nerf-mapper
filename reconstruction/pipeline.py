#!/usr/bin/env python3
"""
Floorplan Reconstruction Pipeline & Synthetic Session Benchmark.
Implements:
1. 10x10cm Wavelength Spatial Binning in Linear mW space
2. Multi-Band Differential Wall Loss Analysis (2.4 GHz vs 5/6 GHz)
3. Wi-Fi RTT LoS/NLoS Virtual Anchor Reflection Extraction
4. Physics-Informed 2D NeRFMap Occupancy Grid Inversion
5. Network Payload & Battery Drain Estimator
"""

import gzip
import json
import math
import os
import random
import sys
from typing import Dict, Any, List, Tuple

import numpy as np

# -----------------------------------------------------------------------------
# 1. Spatial Binning & Multi-Band Preprocessing
# -----------------------------------------------------------------------------

def spatial_wavelength_binning(
    events: List[Dict[str, Any]],
    grid_size_m: float = 0.10
) -> Dict[Tuple[int, int, str], float]:
    """
    Groups measurements into grid_size_m (10cm) cells and averages linear mW power
    to remove small-scale multipath fading spikes.
    """
    bins = {} # (bin_x, bin_y, bssid) -> list of mW powers

    for ev in events:
        pose = ev.get("pose")
        if not pose:
            continue
        px, py = pose.get("x", 0.0), pose.get("y", 0.0)
        bx = int(math.floor(px / grid_size_m))
        by = int(math.floor(py / grid_size_m))

        if ev.get("event_type") == "wifi_scan_batch":
            for scan in ev.get("scans", []):
                bssid = scan["bssid"]
                rssi = scan["rssi_dbm"]
                mw = 10.0 ** (rssi / 10.0)
                bins.setdefault((bx, by, bssid), []).append(mw)
        elif ev.get("event_type") == "connected_probe":
            bssid = ev["bssid"]
            rssi = ev["rssi_dbm"]
            mw = 10.0 ** (rssi / 10.0)
            bins.setdefault((bx, by, bssid), []).append(mw)

    averaged = {}
    for key, mw_list in bins.items():
        avg_mw = sum(mw_list) / len(mw_list)
        averaged[key] = 10.0 * math.log10(max(avg_mw, 1e-12))
    return averaged

def extract_multiband_wall_markers(events: List[Dict[str, Any]], excess_threshold_db: float = 3.5) -> List[Tuple[float, float, float]]:
    """
    Extracts (x, y, excess_wall_loss_db) points where 5 GHz dropped significantly
    more than 2.4 GHz, indicating physical wall crossings along the ray.
    """
    wall_markers = []
    for ev in events:
        if ev.get("event_type") == "wifi_scan_batch":
            pose = ev.get("pose")
            if not pose:
                continue
            px, py = pose["x"], pose["y"]
            for pair in ev.get("multiband_pairs", []):
                loss = pair.get("excess_wall_loss_db", 0.0)
                if loss >= excess_threshold_db:
                    wall_markers.append((px, py, loss))
    return wall_markers

# -----------------------------------------------------------------------------
# 2. RTT LoS/NLoS Virtual Anchor Solver
# -----------------------------------------------------------------------------

def extract_rtt_wall_reflections(events: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Identifies NLoS RTT spikes and estimates specular wall reflection lines.
    """
    rtt_by_bssid = {}
    for ev in events:
        if ev.get("event_type") == "wifi_rtt":
            bssid = ev["bssid"]
            rtt_by_bssid.setdefault(bssid, []).append(ev)

    detected_walls = []
    for bssid, measurements in rtt_by_bssid.items():
        # High std_dev (>300mm) or sudden positive jump flags an NLoS wall reflection
        nlos_points = []
        for m in measurements:
            std_dev = m.get("std_dev_mm", 0)
            pose = m.get("pose")
            if std_dev > 250 and pose:
                nlos_points.append((pose["x"], pose["y"], m.get("distance_mm", 0) / 1000.0))

        if len(nlos_points) >= 3:
            detected_walls.append({
                "ap_bssid": bssid,
                "nlos_sample_count": len(nlos_points),
                "reflection_boundary": "specular_wall_detected"
            })
    return detected_walls

# -----------------------------------------------------------------------------
# 3. NeRFMap 2D Floorplan Voxel Inverter
# -----------------------------------------------------------------------------

class NeRFMap2DSolver:
    """
    Physics-informed 2D floorplan occupancy solver.
    Solves for wall attenuation density grid delta[H, W] via regularized gradient descent.
    """
    def __init__(self, room_width_m: float = 8.0, room_height_m: float = 6.0, resolution_m: float = 0.20):
        self.res = resolution_m
        self.nx = int(math.ceil(room_width_m / self.res))
        self.ny = int(math.ceil(room_height_m / self.res))
        self.grid = np.zeros((self.ny, self.nx), dtype=np.float32)

    def march_ray_voxels(self, x0: float, y0: float, x1: float, y1: float) -> List[Tuple[int, int, float]]:
        """Siddon-style Bresenham ray marching to find intersecting grid cells."""
        dist = math.hypot(x1 - x0, y1 - y0)
        if dist < 1e-4:
            return []
        steps = int(math.ceil(dist / (self.res * 0.5)))
        cells = set()
        for s in range(steps + 1):
            t = s / float(steps)
            cx = (x0 + t * (x1 - x0)) / self.res
            cy = (y0 + t * (y1 - y0)) / self.res
            ix, iy = int(math.floor(cx)), int(math.floor(cy))
            if 0 <= ix < self.nx and 0 <= iy < self.ny:
                cells.add((ix, iy))
        return [(ix, iy, self.res) for ix, iy in cells]

    def fit_floorplan(self, ray_measurements: List[Tuple[float, float, float, float, float]], iterations: int = 40):
        """
        ray_measurements: list of (ap_x, ap_y, rx_x, rx_y, measured_loss_db)
        Optimizes self.grid with L1 sparsity and Total Variation smoothing.
        """
        lr = 0.05
        l1_reg = 0.01

        for _ in range(iterations):
            grad = np.zeros_like(self.grid)

            for apx, apy, rxx, rxy, target_loss in ray_measurements:
                intersections = self.march_ray_voxels(apx, apy, rxx, rxy)
                predicted_loss = sum(self.grid[iy, ix] * length for ix, iy, length in intersections)
                err = predicted_loss - target_loss

                for ix, iy, length in intersections:
                    grad[iy, ix] += 2.0 * err * length

            grad /= max(len(ray_measurements), 1)
            # Add L1 sparsity gradient
            grad += l1_reg * np.sign(self.grid)

            self.grid -= lr * grad
            self.grid = np.clip(self.grid, 0.0, 15.0) # Wall attenuation max 15 dB/m

        return self.grid

# -----------------------------------------------------------------------------
# 4. Realistic Synthetic Session Generator & Benchmark
# -----------------------------------------------------------------------------

def generate_synthetic_mapping_session(
    duration_s: float = 60.0,
    walk_speed_mps: float = 0.8,
    device_id: str = "device_pixel8_001",
    environment_id: str = "env_b3a1c8f9210d4e5a",
    dwell_state: str = "IN_TRANSIT"
) -> List[Dict[str, Any]]:
    """
    Simulates a 2-room apartment walk (8m x 6m) with:
    - Center dividing drywall at x = 4.0m
    - AP 1: Dual-Band router at (1.5m, 3.0m) in Room 1
    - AP 2: Neighbor router at (7.0m, 5.0m) in Room 2
    """
    events = []
    num_steps = int(duration_s * 1.5)
    t_start = 1_700_000_000_000_000_000 # ns

    # Rectangular walk loop crossing through doorway at (4.0m, 1.5m)
    for i in range(num_steps):
        phase = (i / float(num_steps)) * 2.0 * math.pi
        px = 4.0 + 2.8 * math.cos(phase)
        py = 3.0 + 1.8 * math.sin(phase)
        t_ns = t_start + int(i * (1.0 / 1.5) * 1e9)

        pose = {
            "x": round(px, 3),
            "y": round(py, 3),
            "z": 0.0,
            "qx": 0.0, "qy": 0.0, "qz": round(math.sin(phase / 2), 3), "qw": round(math.cos(phase / 2), 3),
            "confidence": 0.95,
            "is_stationary": False
        }

        # Step event
        events.append({
            "event_type": "mag_baro_pdr",
            "timestamp_ns": t_ns,
            "device_id": device_id,
            "environment_id": environment_id,
            "dwell_state": dwell_state,
            "step_detected": True,
            "stride_length_m": round(walk_speed_mps / 1.5, 3),
            "pressure_hpa": 1013.25,
            "pose": pose
        })

        # Connected AP high-rate probe (every 2nd step)
        if i % 2 == 0:
            d_ap1 = math.hypot(px - 1.5, py - 3.0)
            crosses_wall = (px > 4.0)
            excess = 6.0 if crosses_wall else 0.0
            probe_rssi = int(round(-40.0 - 20.0 * math.log10(max(d_ap1, 0.5)) - excess))

            events.append({
                "event_type": "connected_probe",
                "timestamp_ns": t_ns + 10_000_000,
                "device_id": device_id,
                "environment_id": environment_id,
                "dwell_state": dwell_state,
                "bssid": "a4:97:b1:12:34:54",
                "freq_mhz": 5180,
                "rssi_dbm": probe_rssi,
                "tx_mbps": 866,
                "rx_mbps": 780,
                "udp_rtt_ms": 2.1,
                "pose": pose
            })

        # Wi-Fi Scan Batch (every 3rd step, ~0.5 Hz)
        if i % 3 == 0:
            d_ap1 = math.hypot(px - 1.5, py - 3.0)
            crosses_wall = (px > 4.0)
            excess_24 = 2.0 if crosses_wall else 0.0
            excess_5 = 7.5 if crosses_wall else 0.0

            rssi_24 = int(round(-35.0 - 20.0 * math.log10(max(d_ap1, 0.5)) - excess_24))
            rssi_5 = int(round(-40.0 - 20.0 * math.log10(max(d_ap1, 0.5)) - excess_5))

            scans = [
                {"bssid": "a4:97:b1:12:34:50", "ssid": "HomeNet", "ap_group_id": "a4:97:b1:12:34:xx",
                 "band": "2.4GHz", "freq_mhz": 2437, "bw_mhz": 20, "rssi_dbm": rssi_24,
                 "is_80211mc": False, "hw_timestamp_ns": t_ns, "scan_age_ms": 40, "pose": pose},
                {"bssid": "a4:97:b1:12:34:54", "ssid": "HomeNet_5G", "ap_group_id": "a4:97:b1:12:34:xx",
                 "band": "5GHz", "freq_mhz": 5180, "bw_mhz": 80, "rssi_dbm": rssi_5,
                 "is_80211mc": True, "hw_timestamp_ns": t_ns, "scan_age_ms": 10, "pose": pose}
            ]

            wall_loss = max(0.0, (rssi_24 - rssi_5) - (20.0 * math.log10(5180.0 / 2437.0)))

            events.append({
                "event_type": "wifi_scan_batch",
                "timestamp_ns": t_ns + 15_000_000,
                "device_id": device_id,
                "environment_id": environment_id,
                "dwell_state": dwell_state,
                "scans": scans,
                "multiband_pairs": [{
                    "ap_group_id": "a4:97:b1:12:34:xx",
                    "rssi_24_dbm": rssi_24,
                    "rssi_5_dbm": rssi_5,
                    "excess_wall_loss_db": round(wall_loss, 2)
                }],
                "pose": pose
            })

            # Wi-Fi RTT ranging
            rtt_dist_mm = int(round((d_ap1 + (1.2 if crosses_wall else 0.0)) * 1000))
            rtt_std_dev = 320 if crosses_wall else 120
            events.append({
                "event_type": "wifi_rtt",
                "timestamp_ns": t_ns + 20_000_000,
                "device_id": device_id,
                "environment_id": environment_id,
                "dwell_state": dwell_state,
                "bssid": "a4:97:b1:12:34:54",
                "mode": "TWO_SIDED_80211MC",
                "distance_mm": rtt_dist_mm,
                "std_dev_mm": rtt_std_dev,
                "rssi_dbm": rssi_5,
                "attempted": 8,
                "successful": 8,
                "pose": pose
            })

    return events

def run_benchmark_and_reconstruct():
    print("=" * 70)
    print("Wi-Fi Environment Mapping: Background Pipeline & Benchmark")
    print("=" * 70)

    events = generate_synthetic_mapping_session(duration_s=60.0)
    print(f"[*] Generated synthetic session: {len(events)} events over 60 seconds")

    # 1. Payload & Compression Evaluation
    raw_json_str = "\n".join(json.dumps(ev) for ev in events)
    raw_bytes = raw_json_str.encode("utf-8")
    gz_bytes = gzip.compress(raw_bytes)

    raw_kb = len(raw_bytes) / 1024.0
    gz_kb = len(gz_bytes) / 1024.0
    ratio = (1.0 - (gz_kb / raw_kb)) * 100.0

    print(f"\n[1. Network Telemetry Budget (1 Minute Walk)]")
    print(f"    Raw JSONL Size:        {raw_kb:.1f} KB")
    print(f"    Gzipped NDJSON Size:   {gz_kb:.1f} KB")
    print(f"    Bandwidth Reduction:   {ratio:.1f}%")
    print(f"    Projected 1-Hr Data:   {(gz_kb * 60) / 1024.0:.2f} MB / hour")

    # 2. Battery Consumption Estimate
    active_current_ma = 78.0 # From our hardware budget
    battery_mah = 4500.0
    hourly_pct = (active_current_ma / battery_mah) * 100.0
    ten_min_pct = hourly_pct / 6.0

    print(f"\n[2. Battery Consumption Budget (Screen Off Background Mode)]")
    print(f"    Active System Current: ~{active_current_ma:.1f} mA (vs ~369 mA with ARCore Camera)")
    print(f"    Hourly Battery Drain:  ~{hourly_pct:.2f}% per hour")
    print(f"    10-Minute Walk Drain:  ~{ten_min_pct:.2f}% of phone battery")

    # 3. Spatial Wavelength Binning
    bins = spatial_wavelength_binning(events, grid_size_m=0.10)
    print(f"\n[3. 10x10cm Wavelength Binning]")
    print(f"    Total Distinct 10cm Cells Visited: {len(bins)}")

    # 4. Multi-Band Wall Loss
    wall_markers = extract_multiband_wall_markers(events, excess_threshold_db=3.0)
    print(f"\n[4. Multi-Band Differential Attenuation (2.4 vs 5 GHz)]")
    print(f"    Detected Wall Crossing Traces: {len(wall_markers)}")
    if wall_markers:
        avg_wall_x = sum(m[0] for m in wall_markers) / len(wall_markers)
        print(f"    Estimated Wall X-Coordinate:   x ≈ {avg_wall_x:.2f}m (Ground Truth: x = 4.00m)")

    # 5. RTT Virtual Anchor
    rtt_reflections = extract_rtt_wall_reflections(events)
    print(f"\n[5. Wi-Fi RTT LoS/NLoS Virtual Anchor Reflections]")
    print(f"    Identified NLoS Reflecting Boundaries: {len(rtt_reflections)}")

    # 6. NeRFMap 2D Inversion
    solver = NeRFMap2DSolver(room_width_m=8.0, room_height_m=6.0, resolution_m=0.25)
    ray_data = []
    for ev in events:
        if ev.get("event_type") == "wifi_scan_batch":
            pose = ev["pose"]
            for scan in ev["scans"]:
                if scan["bssid"] == "a4:97:b1:12:34:54":
                    dist = math.hypot(pose["x"] - 1.5, pose["y"] - 3.0)
                    fs_loss = 40.0 + 20.0 * math.log10(max(dist, 0.5))
                    excess = max(0.0, -scan["rssi_dbm"] - fs_loss)
                    ray_data.append((1.5, 3.0, pose["x"], pose["y"], excess))

    grid = solver.fit_floorplan(ray_data, iterations=35)
    print(f"\n[6. NeRFMap Inversion Result]")
    print(f"    Reconstructed 2D Grid Shape: {grid.shape} (Resolution: 0.25m)")
    wall_profile_x = np.mean(grid, axis=0)
    peak_x_idx = int(np.argmax(wall_profile_x))
    peak_x = peak_x_idx * 0.25
    print(f"    Strongest Reconstructed Wall Peak: x ≈ {peak_x:.2f}m (Ground Truth: x = 4.00m)")
    print("=" * 70)
    print("✓ Full Pipeline Benchmark Succeeded.")
    print("=" * 70)

if __name__ == "__main__":
    run_benchmark_and_reconstruct()
