"""In-process Android bridge for the original PUTRA live candle analysis engine.

The native Android service passes a lossless PNG crop captured using MediaProjection. The full Python
analysis path (frame -> chart region -> candle extraction -> pattern/strategy analysis -> five-minute
score and confirmation gates) is reused. Only desktop-specific screen capture, OCR, and chart drawing
are disabled: the phone already supplies a cropped screen frame, OCR cannot be assumed on-device,
and the Android UI draws its monitor separately. No network calls are made from this module.
"""
from __future__ import annotations

import io
import json
import os
import time
from datetime import datetime, timedelta, timezone

_engine = None
_engine_lock = None
_db_path = None


def configure(app_files_dir: str) -> str:
    """Bind all Python history/database state to this app's private on-device files directory."""
    global _engine, _engine_lock, _db_path
    os.makedirs(app_files_dir, exist_ok=True)
    _db_path = os.path.join(app_files_dir, "putra_trading_memory_btc_otc.db")
    os.environ["PUTRA_TRADING_DB"] = _db_path
    import threading
    _engine_lock = threading.RLock()
    # Import only after setting PUTRA_TRADING_DB, because putra.py reads it during initialization.
    import putra as core
    core.DEFAULT_DB_PATH = core.Path(_db_path)
    core.DB_PATH = core.DEFAULT_DB_PATH
    # The phone's native capture permission supplies frames. No OCR dependency is silently faked.
    core._read_current_price_tag_cached = lambda frame, candles: (None, "PHONE PRICE OCR DISABLED")
    # The Android native monitor renders the result. Drawing is display-only and must not touch scoring.
    core.draw_chart_analysis = lambda image, *args, **kwargs: image
    _engine = core
    return json.dumps({
        "ready": True,
        "engine_version": str(core.VERSION),
        "analysis_model_version": str(core.CANDLE_ANALYSIS_MODEL_VERSION),
        "database": "LOCAL_PRIVATE_APP_STORAGE",
        "network_feeds": "DISABLED",
        "screen_capture": "ANDROID_MEDIAPROJECTION",
        "score_engine": "ORIGINAL_PUTRA_PYTHON_ANALYZER",
    })


def analyze_png(png_data, captured_epoch_ms: int = 0) -> str:
    """Analyze one lossless native Android screen-capture frame already cropped by the user.

    Returns a compact JSON result. Raises an exception rather than returning a fabricated score
    if decoding, capture validation, or the Python analysis engine fails.
    """
    global _engine, _engine_lock
    if _engine is None:
        raise RuntimeError("PUTRA_ENGINE_NOT_CONFIGURED")
    started = time.monotonic()
    # Chaquopy maps java byte[] to Python bytes. Accept bytearray for adapter/test callers too.
    if not isinstance(png_data, (bytes, bytearray, memoryview)):
        try:
            png_data = bytes(png_data)
        except Exception as exc:
            raise TypeError("PNG_FRAME_BYTES_REQUIRED") from exc
    raw = bytes(png_data)
    if not raw:
        raise ValueError("EMPTY_CAPTURE_FRAME")

    from PIL import Image
    import numpy as np
    with Image.open(io.BytesIO(raw)) as source:
        if source.format != "PNG":
            raise ValueError("LOSSLESS_PNG_FRAME_REQUIRED")
        rgb_image = source.convert("RGB")
        if rgb_image.width < 100 or rgb_image.height < 80:
            raise ValueError("CAPTURE_CROP_TOO_SMALL")
        rgb = np.asarray(rgb_image, dtype=np.uint8)
    # The desktop pipeline expects a BGR uint8 NumPy array, exactly as before.
    bgr = np.ascontiguousarray(rgb[:, :, ::-1])
    core = _engine
    with _engine_lock:
        result = core.analyze_live_screen_frame(
            bgr,
            {"available": False, "reason": "PHONE PRICE AXIS CALIBRATION NOT CONFIGURED", "tick_points": []},
            {"available": False, "reason": "ANDROID LIVE MODE: SCALE-RESIZE SELF-CHECK SKIPPED"},
        )
    candle_count = int(result.get("candle_count") or 0)
    combined = result.get("combined") if isinstance(result.get("combined"), dict) else {}
    analysis = result.get("candle_analysis") if isinstance(result.get("candle_analysis"), dict) else {}
    direction = str(combined.get("direction") or analysis.get("direction") or "ANALYZING").upper()
    score_val = combined.get("selected_direction_score_percent")
    if score_val is None:
        score_val = analysis.get("selected_direction_score_percent")
    buy_val = combined.get("buy_outcome_probability_percent")
    sell_val = combined.get("sell_outcome_probability_percent")
    if buy_val is None:
        buy_val = analysis.get("buy_direction_score_percent")
    if sell_val is None:
        sell_val = analysis.get("sell_direction_score_percent")
    patterns = analysis.get("patterns") if isinstance(analysis.get("patterns"), list) else []
    confirmation = combined.get("confirmation_matrix") if isinstance(combined.get("confirmation_matrix"), dict) else {}
    coverage = analysis.get("requested_analysis_coverage") if isinstance(analysis.get("requested_analysis_coverage"), dict) else {}
    summary = {
        "ok": True,
        "captured_at_manila": datetime.now(timezone.utc).astimezone(timezone(timedelta(hours=8))).strftime("%b %d %Y · %I:%M:%S %p"),
        "frame_width": int(bgr.shape[1]),
        "frame_height": int(bgr.shape[0]),
        "candle_count": candle_count,
        "green_count": int(result.get("green_count") or 0),
        "red_count": int(result.get("red_count") or 0),
        "direction": direction if candle_count else "ANALYZING",
        "score_percent": float(score_val) if score_val is not None and candle_count > 0 else None,
        "buy_percent": float(buy_val) if buy_val is not None and candle_count > 0 else None,
        "sell_percent": float(sell_val) if sell_val is not None and candle_count > 0 else None,
        "trend": str(result.get("trend") or analysis.get("trend") or "—"),
        "momentum": str(result.get("momentum") or analysis.get("momentum") or "—"),
        "pattern_count": len(patterns),
        "confirmed_patterns": int(result.get("confirmed_patterns") or sum(bool(p.get("confirmed")) for p in patterns)),
        "signal_ready": bool(result.get("signal_ready", False)) and candle_count >= int(core.MIN_FINAL_DECISION_CANDLES),
        "confirmation_gate_passed": bool(result.get("confirmation_gate_passed", False)),
        "confirmation_matrix_qualified": bool(confirmation.get("qualified", False)),
        "candidate_status": str(result.get("candidate_status") or "CANDIDATE_NOT_ALERT_QUALIFIED"),
        "signal_status_reason": str(result.get("signal_status_reason") or "No qualified signal"),
        "confirmation_check_count": int(result.get("confirmation_check_count") or confirmation.get("available_check_count") or 0),
        "confirmation_agreement_percent": float(result.get("confirmation_agreement_percent") or confirmation.get("directional_agreement_ratio_percent") or 0.0),
        "score_semantics": str(result.get("score_semantics") or "Directional evidence score; not a calibrated win probability."),
        "analysis_ms": round((time.monotonic() - started) * 1000.0, 1),
        "database": "LOCAL_PRIVATE_APP_STORAGE",
        "external_data": "DISABLED",
        "price_axis_calibration": "UNAVAILABLE_ON_ANDROID",
        "requested_analysis_coverage": {
            "catalog_version": coverage.get("catalog_version"),
            "requested_item_count": coverage.get("requested_item_count"),
            "evaluated_items": coverage.get("evaluated_items"),
            "matched_items_on_this_chart": coverage.get("matched_items_on_this_chart"),
        },
    }
    return json.dumps(summary, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
