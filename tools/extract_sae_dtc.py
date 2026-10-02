#!/usr/bin/env python3
"""
Готовит app/src/main/assets/dtc_sae_en.tsv из датасета obdex.

Зачем отдельный скрипт: в приложение незачем тащить 16 МБ YAML с причинами,
симптомами и оценками стоимости ремонта - нужен только заголовок кода, чтобы
показать хоть что-то осмысленное для кода, которого нет в русском справочнике.

Запуск (нужен интернет):
    python3 tools/extract_sae_dtc.py
"""
import io
import re
import sys
import urllib.request

BASE = "https://raw.githubusercontent.com/foerbsnavi/OBDex/main/data/generic/"
FAMILIES = ["P0xxx", "P2xxx", "P3xxx", "U0xxx", "U3xxx", "B0xxx", "C0xxx"]
OUT = "app/src/main/assets/dtc_sae_en.tsv"


def unquote(value: str) -> str:
    """Снимает кавычки YAML-строки: в заголовках встречаются и кавычки, и двоеточия."""
    value = value.strip()
    if value.startswith('"'):
        out, i = [], 1
        while i < len(value):
            ch = value[i]
            if ch == "\\" and i + 1 < len(value):
                out.append(value[i + 1])
                i += 2
                continue
            if ch == '"':
                break
            out.append(ch)
            i += 1
        return "".join(out)
    if value.startswith("'"):
        return value[1:].rsplit("'", 1)[0].replace("''", "'")
    return value


def main() -> int:
    rows = {}
    for family in FAMILIES:
        url = f"{BASE}{family}_enriched.yaml"
        print("скачиваю", url, file=sys.stderr)
        text = urllib.request.urlopen(url, timeout=120).read().decode("utf-8")
        for block in re.split(r"\n(?=- code: )", text):
            code = re.match(r"- code: ([A-Z]\d[0-9A-F]{3})", block.strip())
            title = re.search(r"\n  title:\n    en: (.+)", block)
            if code and title:
                rows[code.group(1)] = unquote(title.group(1)).replace("\t", " ").strip()

    data = io.StringIO()
    for code in sorted(rows):
        data.write(f"{code}\t{rows[code]}\n")
    with open(OUT, "w", encoding="utf-8") as handle:
        handle.write(data.getvalue())
    print(f"записано {len(rows)} кодов в {OUT}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
