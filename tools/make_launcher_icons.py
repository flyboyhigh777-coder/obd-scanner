#!/usr/bin/env python3
"""
Растровые иконки запуска из того же рисунка, что и адаптивная.

Зачем они нужны при minSdk=26, где адаптивных достаточно: оболочки головных
устройств (Teyes и прочие китайские магнитолы) - это не стоковый лаунчер. Часть
из них читает только android:icon как обычный bitmap и на adaptive-icon рисует
либо пустой квадрат, либо стандартного зелёного робота. PNG рядом с XML ничего
не ломает (на телефоне система всё равно возьмёт mipmap-anydpi-v26), а на
магнитоле даёт нормальную иконку.

Рисунок повторяет drawable/ic_launcher_foreground.xml: дуга шкалы, стрелка и
ступица на тёмном фоне. Содержимое увеличено в 108/72 раза - у адаптивной
иконки система сама обрезает поля, а у обычной полей быть не должно, иначе
значок выглядит мелким.

Запуск:
    python3 tools/make_launcher_icons.py
"""
import math
import os
import struct
import zlib

BACKGROUND = (0x0E, 0x11, 0x16, 0xFF)
ARC = (0x2E, 0xD3, 0xC6, 0xFF)
NEEDLE = (0xFF, 0xB0, 0x2E, 0xFF)
HUB = (0xF2, 0xF5, 0xF8, 0xFF)

# Плотности Android и сторона иконки в пикселях.
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Сглаживание: считаем в SS раз крупнее и усредняем.
SS = 4

# Исходный рисунок задан в сетке 108x108 (как у вектора).
VIEWPORT = 108.0
# Адаптивная иконка показывает только центральные 72 из 108 - в обычной иконке
# полей нет, поэтому содержимое увеличиваем.
CONTENT_ZOOM = VIEWPORT / 72.0

ARC_CENTRE = (54.0, 60.41)
ARC_RADIUS = 24.0
ARC_WIDTH = 6.0
ARC_FROM = 156.4
ARC_TO = 383.6

NEEDLE_FROM = (54.0, 70.0)
NEEDLE_TO = (68.0, 46.0)
NEEDLE_WIDTH = 5.0

HUB_CENTRE = (54.0, 70.0)
HUB_RADIUS = 4.0


def blend(dst, src):
    """src поверх dst; у нас всё непрозрачное, так что просто замена."""
    return src if src[3] else dst


def point_in_arc(x, y):
    cx, cy = ARC_CENTRE
    dx, dy = x - cx, y - cy
    distance = math.hypot(dx, dy)
    if abs(distance - ARC_RADIUS) > ARC_WIDTH / 2:
        return False
    angle = math.degrees(math.atan2(dy, dx)) % 360
    # Дуга переходит через 360 градусов - проверяем оба оборота.
    return ARC_FROM <= angle <= ARC_TO or ARC_FROM <= angle + 360 <= ARC_TO


def distance_to_segment(x, y, a, b):
    ax, ay = a
    bx, by = b
    vx, vy = bx - ax, by - ay
    length = vx * vx + vy * vy
    t = 0.0 if length == 0 else max(0.0, min(1.0, ((x - ax) * vx + (y - ay) * vy) / length))
    return math.hypot(x - (ax + t * vx), y - (ay + t * vy))


def colour_at(x, y):
    """Цвет точки в координатах исходной сетки 108x108."""
    if point_in_arc(x, y):
        return ARC
    if math.hypot(x - HUB_CENTRE[0], y - HUB_CENTRE[1]) <= HUB_RADIUS:
        return HUB
    if distance_to_segment(x, y, NEEDLE_FROM, NEEDLE_TO) <= NEEDLE_WIDTH / 2:
        return NEEDLE
    return None


def render(size, round_icon):
    """Иконка size x size пикселей; round_icon - круглая маска вместо скруглённого квадрата."""
    big = size * SS
    centre = big / 2.0
    radius = big / 2.0
    corner = big * 0.18
    rows = []
    for py in range(big):
        row = bytearray()
        for px in range(big):
            x = px + 0.5
            y = py + 0.5
            # Маска формы: вне её - прозрачно.
            if round_icon:
                inside = math.hypot(x - centre, y - centre) <= radius
            else:
                dx = max(abs(x - centre) - (big / 2.0 - corner), 0.0)
                dy = max(abs(y - centre) - (big / 2.0 - corner), 0.0)
                inside = math.hypot(dx, dy) <= corner
            if not inside:
                row += bytes((0, 0, 0, 0))
                continue
            # Переводим пиксель в координаты рисунка с учётом увеличения.
            u = (x / big - 0.5) * VIEWPORT / CONTENT_ZOOM + VIEWPORT / 2
            v = (y / big - 0.5) * VIEWPORT / CONTENT_ZOOM + VIEWPORT / 2
            row += bytes(blend(BACKGROUND, colour_at(u, v) or (0, 0, 0, 0)))
        rows.append(row)

    # Усреднение SS x SS.
    out = bytearray()
    for y in range(size):
        out.append(0)  # фильтр строки
        for x in range(size):
            r = g = b = a = 0
            for sy in range(SS):
                row = rows[y * SS + sy]
                for sx in range(SS):
                    i = ((x * SS) + sx) * 4
                    r += row[i]
                    g += row[i + 1]
                    b += row[i + 2]
                    a += row[i + 3]
            n = SS * SS
            out += bytes((r // n, g // n, b // n, a // n))
    return bytes(out)


def chunk(tag, data):
    body = tag + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def write_png(path, size, pixels):
    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(pixels, 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main():
    root = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app/src/main/res")
    for density, size in DENSITIES.items():
        folder = os.path.join(root, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        write_png(os.path.join(folder, "ic_launcher.png"), size, render(size, round_icon=False))
        write_png(os.path.join(folder, "ic_launcher_round.png"), size, render(size, round_icon=True))
        print("%s: %dx%d" % (density, size, size))


if __name__ == "__main__":
    main()
