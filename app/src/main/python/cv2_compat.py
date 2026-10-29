"""Small NumPy-only compatibility layer for the OpenCV calls on PUTRA's live-analysis path.

This module intentionally avoids SciPy and native OpenCV wheels, neither of which is available
for every Chaquopy Android ABI/Python combination. It implements only the operations called by
PUTRA's selected-frame analyzer. Candle thresholds and scoring logic remain in putra.py.
"""
from __future__ import annotations

import numpy as np

COLOR_BGR2GRAY = 6
COLOR_BGR2HSV = 40
MORPH_OPEN = 2
CV_64F = 6


def cvtColor(image, code):
    arr = np.asarray(image)
    if code == COLOR_BGR2GRAY:
        if arr.ndim == 2:
            return arr.copy()
        b = arr[..., 0].astype(np.float32)
        g = arr[..., 1].astype(np.float32)
        r = arr[..., 2].astype(np.float32)
        return np.clip(np.rint(0.114 * b + 0.587 * g + 0.299 * r), 0, 255).astype(np.uint8)
    if code == COLOR_BGR2HSV:
        bgr = arr[..., :3].astype(np.float32) / 255.0
        b, g, r = bgr[..., 0], bgr[..., 1], bgr[..., 2]
        mx = np.maximum(np.maximum(r, g), b)
        mn = np.minimum(np.minimum(r, g), b)
        delta = mx - mn
        hue = np.zeros_like(mx)
        nonzero = delta > 1e-12
        mask = nonzero & (mx == r)
        hue[mask] = ((g[mask] - b[mask]) / delta[mask]) % 6.0
        mask = nonzero & (mx == g)
        hue[mask] = (b[mask] - r[mask]) / delta[mask] + 2.0
        mask = nonzero & (mx == b)
        hue[mask] = (r[mask] - g[mask]) / delta[mask] + 4.0
        hue = hue * 30.0
        saturation = np.zeros_like(mx)
        valid = mx > 1e-12
        saturation[valid] = delta[valid] / mx[valid] * 255.0
        value = mx * 255.0
        return np.stack((np.clip(np.rint(hue), 0, 180),
                         np.clip(np.rint(saturation), 0, 255),
                         np.clip(np.rint(value), 0, 255)), axis=-1).astype(np.uint8)
    raise NotImplementedError(f"cv2_compat.cvtColor code {code} is not supported")


def inRange(image, lower, upper):
    arr = np.asarray(image)
    lo = np.asarray(lower, dtype=arr.dtype).reshape((1,) * (arr.ndim - 1) + (-1,))
    hi = np.asarray(upper, dtype=arr.dtype).reshape((1,) * (arr.ndim - 1) + (-1,))
    if arr.ndim > 2:
        ok = np.all((arr >= lo) & (arr <= hi), axis=-1)
    else:
        ok = (arr >= lo.reshape(-1)[0]) & (arr <= hi.reshape(-1)[0])
    return ok.astype(np.uint8) * 255


def bitwise_or(a, b):
    return np.bitwise_or(np.asarray(a), np.asarray(b))


def countNonZero(a):
    return int(np.count_nonzero(a))


def _shift_bool(array: np.ndarray, dy: int, dx: int, outside: bool = False) -> np.ndarray:
    """Return output[y,x] = array[y+dy,x+dx], with a configurable border value."""
    height, width = array.shape[:2]
    out = np.full((height, width), bool(outside), dtype=bool)
    y0, y1 = max(0, -dy), min(height, height - dy)
    x0, x1 = max(0, -dx), min(width, width - dx)
    if y0 < y1 and x0 < x1:
        out[y0:y1, x0:x1] = array[y0 + dy:y1 + dy, x0 + dx:x1 + dx]
    return out


def morphologyEx(image, op, kernel):
    """Binary morphological opening for the small kernels used in PUTRA's mask cleanup."""
    if op != MORPH_OPEN:
        raise NotImplementedError(f"cv2_compat morphology operation {op} is not supported")
    arr = np.asarray(image) != 0
    structure = np.asarray(kernel, dtype=bool)
    if arr.ndim != 2 or structure.ndim != 2 or not np.any(structure):
        raise ValueError("morphologyEx expects a 2D image and a non-empty 2D kernel")
    anchor_y, anchor_x = structure.shape[0] // 2, structure.shape[1] // 2
    offsets = [(int(y - anchor_y), int(x - anchor_x))
               for y, x in zip(*np.nonzero(structure))]
    eroded = np.ones_like(arr, dtype=bool)
    for dy, dx in offsets:
        eroded &= _shift_bool(arr, dy, dx, outside=True)
    opened = np.zeros_like(arr, dtype=bool)
    for dy, dx in offsets:
        opened |= _shift_bool(eroded, dy, dx)
    return opened.astype(np.uint8) * 255


def _find(parent, item):
    root = item
    while parent[root] != root:
        root = parent[root]
    while parent[item] != item:
        nxt = parent[item]
        parent[item] = root
        item = nxt
    return root


def _union(parent, left, right):
    a = _find(parent, left)
    b = _find(parent, right)
    if a == b:
        return
    # Keep the earliest provisional label as the root, close to OpenCV's raster-order labels.
    if a < b:
        parent[b] = a
    else:
        parent[a] = b


def connectedComponentsWithStats(image, connectivity=8):
    """Run-length connected-components labeling without SciPy or native extensions.

    This is used only for the binary red/green masks. Runs are unioned with adjacent-row runs,
    then expanded into the label matrix and OpenCV-compatible stats/centroid arrays.
    """
    mask = np.asarray(image) != 0
    if mask.ndim != 2:
        raise ValueError("connectedComponentsWithStats expects a 2D mask")
    if connectivity not in (4, 8):
        raise ValueError("connectivity must be 4 or 8")
    height, width = mask.shape
    parent = [0]
    runs_by_row = []
    previous_runs = []

    for y in range(height):
        row = mask[y]
        # Boolean transition indices yield contiguous foreground runs [start, end).
        transitions = np.diff(np.pad(row.astype(np.int8), (1, 1)))
        starts = np.flatnonzero(transitions == 1)
        ends = np.flatnonzero(transitions == -1)
        current_runs = []
        prev_index = 0
        for start, end in zip(starts.tolist(), ends.tolist()):
            provisional = len(parent)
            parent.append(provisional)
            # Skip preceding-row runs which cannot touch this run.
            while prev_index < len(previous_runs):
                _, p_start, p_end = previous_runs[prev_index]
                can_reach = p_end >= start if connectivity == 8 else p_end > start
                if can_reach:
                    break
                prev_index += 1
            k = prev_index
            while k < len(previous_runs):
                p_label, p_start, p_end = previous_runs[k]
                if (p_start > end) if connectivity == 8 else (p_start >= end):
                    break
                connected = (p_start <= end and p_end >= start) if connectivity == 8 else (p_start < end and p_end > start)
                if connected:
                    _union(parent, provisional, p_label)
                k += 1
            current_runs.append((provisional, int(start), int(end)))
        runs_by_row.append(current_runs)
        previous_runs = current_runs

    roots = sorted({_find(parent, label) for row_runs in runs_by_row for label, _, _ in row_runs})
    compact = {root: index + 1 for index, root in enumerate(roots)}
    count = len(roots)
    labels = np.zeros((height, width), dtype=np.int32)
    # Stats columns match OpenCV: LEFT, TOP, WIDTH, HEIGHT, AREA.
    stats = np.zeros((count + 1, 5), dtype=np.int32)
    centers = np.zeros((count + 1, 2), dtype=np.float64)
    right_edges = np.zeros(count + 1, dtype=np.int32)
    bottom_edges = np.zeros(count + 1, dtype=np.int32)
    if count:
        stats[1:, 0] = width
        stats[1:, 1] = height

    x_sum = np.zeros(count + 1, dtype=np.float64)
    y_sum = np.zeros(count + 1, dtype=np.float64)
    for y, row_runs in enumerate(runs_by_row):
        for provisional, start, end in row_runs:
            label_id = compact[_find(parent, provisional)]
            length = end - start
            labels[y, start:end] = label_id
            stats[label_id, 0] = min(stats[label_id, 0], start)
            stats[label_id, 1] = min(stats[label_id, 1], y)
            right_edges[label_id] = max(right_edges[label_id], end)
            bottom_edges[label_id] = max(bottom_edges[label_id], y + 1)
            stats[label_id, 4] += length
            x_sum[label_id] += ((start + end - 1) * length) / 2.0
            y_sum[label_id] += y * length

    foreground_area = int(np.count_nonzero(mask))
    background_area = height * width - foreground_area
    stats[0] = [0, 0, width, height, background_area]
    centers[0] = [width / 2.0, height / 2.0]
    for label_id in range(1, count + 1):
        area = int(stats[label_id, 4])
        stats[label_id, 2] = max(0, int(right_edges[label_id] - stats[label_id, 0]))
        stats[label_id, 3] = max(0, int(bottom_edges[label_id] - stats[label_id, 1]))
        if area:
            centers[label_id, 0] = x_sum[label_id] / area
            centers[label_id, 1] = y_sum[label_id] / area
    return count + 1, labels, stats, centers


def Laplacian(image, ddepth=CV_64F):
    arr = np.asarray(image, dtype=np.float64)
    if arr.ndim != 2:
        raise ValueError("Laplacian expects a 2D image")
    padded = np.pad(arr, ((1, 1), (1, 1)), mode="reflect")
    center = padded[1:-1, 1:-1]
    return (padded[:-2, 1:-1] + padded[2:, 1:-1]
            + padded[1:-1, :-2] + padded[1:-1, 2:] - 4.0 * center)


def resize(image, dsize=None, fx=0.0, fy=0.0, interpolation=0):
    from PIL import Image
    arr = np.asarray(image)
    if dsize is None:
        width = max(1, int(round(arr.shape[1] * fx)))
        height = max(1, int(round(arr.shape[0] * fy)))
    else:
        width, height = int(dsize[0]), int(dsize[1])
    method = Image.Resampling.NEAREST if interpolation == 0 else Image.Resampling.BILINEAR
    image_pil = Image.fromarray(arr)
    output = image_pil.resize((width, height), method)
    return np.asarray(output)
