"""Huấn luyện YOLO nhận diện biển báo VN rồi export sang TFLite cho app Android.

Dataset theo định dạng YOLO:
    <dataset>/images/{train,val}/*.jpg
    <dataset>/labels/{train,val}/*.txt   (mỗi dòng: class cx cy w h, chuẩn hoá 0..1)

classId trong file nhãn PHẢI theo đúng thứ tự dòng của app/src/main/assets/labels.txt.

Ví dụ (Colab / Kaggle có GPU):
    pip install -r training/requirements.txt
    python training/train.py --dataset /path/to/vn-signs --epochs 100
"""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
LABELS = ROOT / "app/src/main/assets/labels.txt"


def read_class_codes() -> list[str]:
    codes = []
    for line in LABELS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            codes.append(line.split("|", 1)[0].strip())
    return codes


def write_data_yaml(dataset: Path, out: Path) -> Path:
    names = read_class_codes()
    data = {
        "path": str(dataset.resolve()),
        "train": "images/train",
        "val": "images/val",
        "names": dict(enumerate(names)),
    }
    out.write_text(yaml.safe_dump(data, allow_unicode=True, sort_keys=False), encoding="utf-8")
    print(f"{len(names)} lớp -> {out}")
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", type=Path, required=True)
    ap.add_argument("--model", default="yolo11n.pt", help="yolo11n.pt (nhanh) hoặc yolo11s.pt (chính xác hơn)")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--no-export", action="store_true")
    args = ap.parse_args()

    from ultralytics import YOLO

    data_yaml = write_data_yaml(args.dataset, Path(__file__).with_name("data.generated.yaml"))
    model = YOLO(args.model)
    model.train(
        data=str(data_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        # KHÔNG lật ngang: lật sẽ biến "cấm rẽ trái" thành "cấm rẽ phải" và làm ngược chữ số.
        fliplr=0.0,
        # Mô phỏng ngược sáng, ban đêm, rung xe.
        hsv_v=0.5,
        degrees=5.0,
        scale=0.5,
        mosaic=1.0,
        project=str(ROOT / "training/runs"),
        name="signs",
    )

    if args.no_export:
        return

    from export_tflite import export

    export(Path(model.trainer.best), args.imgsz, copy_to_app=True)


if __name__ == "__main__":
    main()
