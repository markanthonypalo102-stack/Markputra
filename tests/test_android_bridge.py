"""End-to-end smoke test for the exact PNG -> on-device Python engine bridge.

Uses a deterministic synthetic 100-candle chart. This is a wiring/regression test,
not a live broker screenshot or an empirical forecast-accuracy test.
"""
from __future__ import annotations

import io
import json
import os
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ANDROID_PYTHON = ROOT / "app" / "src" / "main" / "python"
sys.path.insert(0, str(ANDROID_PYTHON))


def synthetic_png() -> bytes:
    width, height = 900, 480
    image = Image.new("RGB", (width, height), (26, 29, 34))
    draw = ImageDraw.Draw(image)
    price = 300
    for i in range(100):
        x = 20 + i * 8
        previous = price
        price += (3 if i % 7 < 4 else -4) + (1 if i % 2 == 0 else -1)
        y0 = max(30, min(430, 480 - int(previous)))
        y1 = max(30, min(430, 480 - int(price)))
        high = max(5, min(y0, y1) - 7)
        low = min(475, max(y0, y1) + 7)
        # RGB values corresponding to the original test's BGR green/red candles.
        color = (0, 180, 0) if price >= previous else (190, 0, 0)
        draw.line((x + 2, high, x + 2, low), fill=color, width=1)
        draw.rectangle((x, min(y0, y1), x + 4, max(y0, y1)), fill=color)
    out = io.BytesIO()
    image.save(out, format="PNG", optimize=False)
    return out.getvalue()


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="putra-android-bridge-") as tmp:
        app_files = Path(tmp) / "private"
        os.environ["PUTRA_TRADING_DB"] = str(app_files / "putra_trading_memory_btc_otc.db")
        import putra_android_bridge as bridge

        config = json.loads(bridge.configure(str(app_files)))
        assert config["ready"] is True
        assert config["database"] == "LOCAL_PRIVATE_APP_STORAGE"
        assert config["network_feeds"] == "DISABLED"

        result = json.loads(bridge.analyze_png(synthetic_png()))
        assert result["ok"] is True
        assert result["candle_count"] >= 90, f"Unexpected candle count: {result['candle_count']}"
        assert result["green_count"] + result["red_count"] == result["candle_count"]
        assert result["external_data"] == "DISABLED"
        assert "confirmation_gate_passed" in result
        assert "confirmation_matrix_qualified" in result
        assert result["signal_ready"] is False or result["candle_count"] >= 12
        assert (app_files / "putra_trading_memory_btc_otc.db").exists()
        print(f"PASS | bridge config: {config['engine_version']}")
        print(f"PASS | lossless PNG frame -> Python analysis: {result['frame_width']}x{result['frame_height']}")
        print(f"PASS | candles: {result['candle_count']} (green={result['green_count']}, red={result['red_count']})")
        print(f"PASS | result: {result['direction']} {result['score_percent']}%, signal_ready={result['signal_ready']}")
        print(f"PASS | local SQLite created: {(app_files / 'putra_trading_memory_btc_otc.db').exists()}")
        try:
            bridge.analyze_png(b"not a PNG").__str__()
        except Exception as exc:
            print(f"PASS | malformed frame fails closed: {type(exc).__name__}")
        else:
            raise AssertionError("Malformed input unexpectedly returned a score")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
