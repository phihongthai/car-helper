"""Export model YOLO (.pt) sang TFLite đúng định dạng app đọc, rồi copy vào assets.

    python training/export_tflite.py --weights best.pt

Ultralytics >= 8.4 export TFLite qua litert-torch, mặc định input NCHW [1,3,H,W].
App (SignDetector) cần NHWC [1,H,W,3] là layout gốc của Android/GPU delegate, nên script
bọc model bằng `litert_torch.to_channel_last_io` trước khi convert.
Kết quả: input float32 [1,640,640,3] (0..1), output float32 [1,4+nc,8400], toạ độ chuẩn hoá 0..1.
FP16 không cần export riêng: GPU delegate tự chạy FP16 khi suy luận.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_MODEL = ROOT / "app/src/main/assets/traffic_signs.tflite"


def export(weights: Path, imgsz: int = 640, copy_to_app: bool = True) -> Path:
    import litert_torch
    from ultralytics import YOLO

    original_convert = litert_torch.convert

    def convert_nhwc(model, sample_args, *a, **kw):
        nhwc = litert_torch.to_channel_last_io(model, args=[0])
        sample = sample_args[0].permute(0, 2, 3, 1).contiguous()
        return original_convert(nhwc, (sample,), *a, **kw)

    litert_torch.convert = convert_nhwc
    try:
        exported = Path(YOLO(str(weights)).export(format="tflite", imgsz=imgsz))
    finally:
        litert_torch.convert = original_convert

    check(exported)
    if copy_to_app:
        APP_MODEL.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(exported, APP_MODEL)
        print(f"Đã copy model vào {APP_MODEL}")
    return exported


def check(tflite: Path) -> None:
    from ai_edge_litert.interpreter import Interpreter

    it = Interpreter(model_path=str(tflite))
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]
    print(f"input {inp['shape']} {inp['dtype'].__name__}, output {out['shape']} {out['dtype'].__name__}")
    assert inp["shape"][-1] == 3, "Input phải là NHWC"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", type=Path, required=True)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--no-copy", action="store_true", help="Không copy vào app/src/main/assets")
    args = ap.parse_args()
    export(args.weights, args.imgsz, copy_to_app=not args.no_copy)


if __name__ == "__main__":
    main()
