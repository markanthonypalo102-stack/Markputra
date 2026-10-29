"""Local regression fixture comparing the desktop and Android Python analysis paths.

Run from the project root with the desktop requirements installed:
  python tests/test_analysis_parity.py
The test is a deterministic synthetic chart fixture, not a live broker/phone test.
"""
from __future__ import annotations
import importlib.util
import os
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ANDROID_PYTHON = ROOT / "app" / "src" / "main" / "python"


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load module: {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def fixture() -> np.ndarray:
    image = np.zeros((480, 900, 3), dtype=np.uint8)
    image[:] = (26, 29, 34)
    price = 300
    for i in range(100):
        x = 20 + i * 8
        previous = price
        price += (3 if i % 7 < 4 else -4) + (1 if i % 2 == 0 else -1)
        y0 = max(30, min(430, 480 - int(previous)))
        y1 = max(30, min(430, 480 - int(price)))
        high = max(5, min(y0, y1) - 7)
        low = min(475, max(y0, y1) + 7)
        color = (0, 180, 0) if price >= previous else (0, 0, 190)
        cv2.line(image, (x + 2, high), (x + 2, low), color, 1)
        cv2.rectangle(image, (x, y0), (x + 4, y1), color, -1)
    return image


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="putra-parity-") as temp_dir:
        temp = Path(temp_dir)
        sys.path.insert(0, str(ANDROID_PYTHON))
        os.environ["PUTRA_TRADING_DB"] = str(temp / "desktop.db")
        desktop = load_module("putra_desktop_parity", ROOT / "putra.py")
        desktop.DB_PATH = temp / "desktop.db"
        desktop.DEFAULT_DB_PATH = desktop.DB_PATH
        desktop._read_current_price_tag_cached = lambda frame, candles: (None, "TEST OCR DISABLED")
        desktop.draw_chart_analysis = lambda image, *args, **kwargs: image

        os.environ["PUTRA_TRADING_DB"] = str(temp / "android.db")
        mobile = load_module("putra_mobile_parity", ANDROID_PYTHON / "putra.py")
        mobile.DB_PATH = temp / "android.db"
        mobile.DEFAULT_DB_PATH = mobile.DB_PATH
        mobile._read_current_price_tag_cached = lambda frame, candles: (None, "TEST OCR DISABLED")
        mobile.draw_chart_analysis = lambda image, *args, **kwargs: image

        frame = fixture()
        desktop_result = desktop.analyze_live_screen_frame(
            frame, {"available": False, "reason": "test"}, {"available": False, "reason": "test"}
        )
        mobile_result = mobile.analyze_live_screen_frame(
            frame, {"available": False, "reason": "test"}, {"available": False, "reason": "test"}
        )
        keys = (
            "candle_count", "green_count", "red_count", "direction", "confidence",
            "pattern_count", "confirmation_check_count", "confirmation_agreement_percent", "candidate_status",
        )
        failures = []
        for key in keys:
            same = desktop_result.get(key) == mobile_result.get(key)
            print(("PASS" if same else "FAIL") + f" | {key}: desktop={desktop_result.get(key)!r}; Android={mobile_result.get(key)!r}")
            if not same:
                failures.append(key)
        print(f"ANDROID ENGINE PARITY FIXTURE: {len(keys) - len(failures)}/{len(keys)} passed")
        return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
