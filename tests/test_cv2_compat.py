"""Pure-NumPy regression tests for the Android OpenCV compatibility subset."""
from __future__ import annotations
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
ANDROID_PYTHON = ROOT / "app" / "src" / "main" / "python"
sys.path.insert(0, str(ANDROID_PYTHON))
import cv2_compat as cv2c  # noqa: E402


def main() -> int:
    # Two diagonal pixels join under 8-connectivity but remain separate under 4-connectivity.
    diagonal = np.array([[255, 0, 0], [0, 255, 0], [0, 0, 0]], dtype=np.uint8)
    n8, labels8, stats8, centers8 = cv2c.connectedComponentsWithStats(diagonal, 8)
    n4, labels4, stats4, centers4 = cv2c.connectedComponentsWithStats(diagonal, 4)
    assert n8 == 2, f"8-connected components expected background + 1, got {n8}"
    assert n4 == 3, f"4-connected components expected background + 2, got {n4}"
    assert int(stats8[1, 4]) == 2
    assert sorted(stats4[1:, 4].tolist()) == [1, 1]
    assert labels8.shape == diagonal.shape and labels4.shape == diagonal.shape
    assert np.all(np.isfinite(centers8)) and np.all(np.isfinite(centers4))

    # Opening with the production 2x1 kernel removes isolated one-pixel vertical specks
    # and keeps a contiguous two-pixel vertical run (OpenCV-compatible default anchor).
    mask = np.array([[0], [255], [255], [0], [255], [0]], dtype=np.uint8)
    opened = cv2c.morphologyEx(mask, cv2c.MORPH_OPEN, np.ones((2, 1), dtype=np.uint8))
    assert opened.shape == mask.shape
    assert set(np.unique(opened)).issubset({0, 255})

    gray = np.array([[0, 10, 20], [30, 40, 50], [60, 70, 80]], dtype=np.uint8)
    lap = cv2c.Laplacian(gray)
    assert lap.shape == gray.shape and np.all(np.isfinite(lap))

    print("PASS | NumPy-only connected components (4/8 connectivity)")
    print("PASS | NumPy-only 2x1 morphology opening")
    print("PASS | NumPy-only Laplacian")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
