#!/usr/bin/env python3
"""
Готовит русский справочник кодов неисправностей для приложения:

    app/src/main/assets/dtc_ru.tsv        - код -> русское и английское название
    app/src/main/assets/dtc_ru_texts.tsv  - пронумерованные частые причины и симптомы

Источник - проект parhipov/OBDScanner (MIT): он взял обобщённые коды из obdex
(CC0) и добавил к ним русские названия, причины и симптомы. До этого у нас был
только английский заголовок из obdex, то есть для любого кода вне русского
справочника приложение показывало английский текст.

Английские описания кода (поле "d") сознательно НЕ берутся: это ещё 1,6 МБ, они
только на английском, и русские причины с симптомами полезнее.

Запуск (нужен интернет):
    python3 tools/extract_ru_dtc.py
Или с локальной копии репозитория:
    python3 tools/extract_ru_dtc.py /path/to/OBDScanner/core/data/dtc/generic.json
"""
import json
import sys
import urllib.request

SRC = (
    "https://raw.githubusercontent.com/parhipov/OBDScanner/main/core/data/dtc/generic.json"
)
OUT_CODES = "app/src/main/assets/dtc_ru.tsv"
OUT_TEXTS = "app/src/main/assets/dtc_ru_texts.tsv"

CODE_HEADER = """\
# Справочник обобщённых кодов неисправностей: русское и английское название.
#
# Формат: код<TAB>русское название<TAB>английское название<TAB>причины<TAB>симптомы.
# Причины и симптомы - номера строк из dtc_ru_texts.tsv через запятую, самые
# вероятные первыми. Пустое поле означает, что их нет.
#
# Собран скриптом tools/extract_ru_dtc.py, править руками не нужно.
# Источники и лицензии: data/SOURCES.md.
"""

TEXTS_HEADER = """\
# Частые причины и симптомы - по строке на фразу, на них ссылается dtc_ru.tsv.
#
# Формат: номер<TAB>по-русски<TAB>по-английски. Одна и та же фраза ("Горит
# Check Engine") хранится один раз на тысячи кодов.
#
# Собран скриптом tools/extract_ru_dtc.py, править руками не нужно.
"""


def clean(value: str) -> str:
    """Табуляции и переводы строк сломали бы построчный разбор в приложении."""
    return " ".join((value or "").split())


def main() -> int:
    if len(sys.argv) > 1:
        with open(sys.argv[1], encoding="utf-8") as fh:
            data = json.load(fh)
    else:
        print(f"качаю {SRC}")
        with urllib.request.urlopen(SRC, timeout=120) as resp:
            data = json.loads(resp.read().decode("utf-8"))

    codes, texts = data["codes"], data["texts"]

    with open(OUT_TEXTS, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(TEXTS_HEADER)
        for i, text in enumerate(texts):
            ru, en = clean(text.get("ru")), clean(text.get("en"))
            fh.write(f"{i}\t{ru}\t{en}\n")

    written = 0
    with open(OUT_CODES, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(CODE_HEADER)
        for code in sorted(codes):
            entry = codes[code]
            ru, en = clean(entry.get("ru")), clean(entry.get("en"))
            if not ru and not en:
                continue
            causes = ",".join(str(i) for i in entry.get("c", []))
            symptoms = ",".join(str(i) for i in entry.get("s", []))
            fh.write(f"{code}\t{ru}\t{en}\t{causes}\t{symptoms}\n")
            written += 1

    print(f"{OUT_CODES}: {written} кодов")
    print(f"{OUT_TEXTS}: {len(texts)} фраз")
    return 0


if __name__ == "__main__":
    sys.exit(main())
