#!/usr/bin/env python3
"""
Проверка строковых ресурсов до сборки.

Зачем отдельный скрипт: :app в среде разработки не собирается (нет доступа к
Google Maven), а aapt придирчив к мелочам, которых не видно глазом. Одна
неэкранированная кавычка роняет всю сборку, и узнать об этом через пять минут
в CI - дорого. Здесь ловится то же самое за секунду.

Что проверяется:
  * XML разбирается;
  * наборы ключей в русском и английском файлах совпадают;
  * апострофы и кавычки внутри строк экранированы (требование aapt);
  * нет настоящих переводов строк внутри строки - только литеральные \\n;
  * проценты либо экранированы, либо строка помечена formatted="false".

Запуск:
    python3 tools/check_strings.py
"""
import re
import sys
import xml.etree.ElementTree as ET

FILES = [
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-en/strings.xml",
]


def unescaped(text: str, char: str) -> bool:
    """Символ без предшествующего обратного слэша - для aapt это ошибка."""
    for i, ch in enumerate(text):
        if ch != char:
            continue
        backslashes = 0
        j = i - 1
        while j >= 0 and text[j] == "\\":
            backslashes += 1
            j -= 1
        if backslashes % 2 == 0:
            return True
    return False


def check(path: str) -> list[str]:
    problems: list[str] = []
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        return [f"{path}: XML не разбирается: {exc}"]

    for node in root.iter("string"):
        name = node.get("name")
        text = node.text or ""
        where = f"{path}: {name}"

        if unescaped(text, "'"):
            problems.append(f"{where}: апостроф без экранирования, нужен \\'")
        if unescaped(text, '"'):
            problems.append(f'{where}: кавычка без экранирования, нужна \\"')
        if "\n" in text:
            problems.append(f"{where}: настоящий перевод строки, нужен литеральный \\n")
        if node.get("formatted") != "false":
            # Одиночный процент aapt принимает за начало подстановки.
            # Полноценная спецификация формата: %s, %1$s, %1$.0f, %.2f.
            spec = r"%(?:\d+\$)?[-+ 0,#]*\d*(?:\.\d+)?[a-zA-Z]"
            singles = re.findall(r"(?<!%)%(?!%)", text)
            if singles and not re.search(spec, text):
                problems.append(f"{where}: процент без экранирования, нужен %% или formatted=\"false\"")
    return problems


def main() -> int:
    problems: list[str] = []
    keys = {}
    for path in FILES:
        problems += check(path)
        try:
            root = ET.parse(path).getroot()
            keys[path] = {n.get("name") for n in root.iter("string")}
        except ET.ParseError:
            pass

    if len(keys) == len(FILES):
        ru, en = (keys[p] for p in FILES)
        for missing in sorted(ru - en):
            problems.append(f"нет перевода в values-en: {missing}")
        for extra in sorted(en - ru):
            problems.append(f"есть только в values-en: {extra}")

    if problems:
        print("Проблемы в строковых ресурсах:")
        for p in problems:
            print(" ", p)
        return 1
    total = len(next(iter(keys.values()))) if keys else 0
    print(f"строковые ресурсы в порядке: {total} строк в каждом языке")
    return 0


if __name__ == "__main__":
    sys.exit(main())
