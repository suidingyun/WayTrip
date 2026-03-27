"""
将项目根目录 travel_guide.xlsx 转为 assistant-docs 可用的 Markdown。
每个目的地以 --- 分隔，便于 Spring AI MarkdownDocumentReader 分块。
用法（在项目根目录）: python scripts/convert_travel_guide_xlsx_to_md.py
依赖: pip install openpyxl
"""
from __future__ import annotations

from pathlib import Path


def cell_str(v: object) -> str:
    if v is None:
        return ""
    return str(v).strip()


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    xlsx = root / "travel_guide.xlsx"
    out = root / "travel-server" / "src" / "main" / "resources" / "assistant-docs" / "travel_guide_corpus.md"

    try:
        import openpyxl
    except ImportError as e:
        raise SystemExit("请先安装: pip install openpyxl") from e

    if not xlsx.is_file():
        raise SystemExit(f"未找到: {xlsx}")

    wb = openpyxl.load_workbook(xlsx, read_only=True, data_only=True)
    sh = wb.active
    raw = sh.iter_rows(values_only=True)
    headers_row = next(raw)
    headers = [cell_str(h) if h else f"列{i}" for i, h in enumerate(headers_row)]

    parts: list[str] = []
    parts.append("# 旅游攻略语料库\n\n")
    parts.append(
        "> 本文件由 `travel_guide.xlsx` 自动生成，可按需重新运行 "
        "`python scripts/convert_travel_guide_xlsx_to_md.py` 更新。\n\n"
    )

    count = 0
    for row in raw:
        if not row:
            continue
        cells = [cell_str(row[i]) if i < len(row) else "" for i in range(len(headers))]
        if not any(cells):
            continue
        dest = cells[0] if cells else ""
        if not dest and not any(cells[1:]):
            continue

        block: list[str] = []
        title = dest if dest else "（未填写目的地）"
        block.append(f"## 目的地：{title}\n\n")
        for i in range(1, len(headers)):
            h, val = headers[i], cells[i] if i < len(cells) else ""
            if not val:
                continue
            block.append(f"### {h}\n\n{val}\n\n")
        block.append("---\n\n")
        parts.append("".join(block))
        count += 1

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(parts), encoding="utf-8")
    print(f"OK: {count} 条目的地 -> {out}")


if __name__ == "__main__":
    main()
