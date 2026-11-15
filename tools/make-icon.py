#!/usr/bin/env python3
"""生成 assets/railplanner/icon.png（128×128，Fabric 模组图标）。

图形 = 面板那套深色底（0x101418 系）+ 32 格方块网格 + 一条按铁路口径圆角化过的选线：
基线沿「切线—圆曲线—切线」走，上面正交打短枕木（地图上表示铁路的老记号），两端白方块是点过的顶点。

    python3 tools/make-icon.py                    # 写 src/main/resources/assets/railplanner/icon.png
    python3 tools/make-icon.py --style plain      # 只画线、不打枕木
    python3 tools/make-icon.py --sheet out.png    # 拼一张对照图（两种画法 + 32/16 px 缩小件）

只依赖 Pillow + numpy。改颜色、线形、选线形状改下面这几个常量。
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw

S = 4          # 超采样倍率：512 画，缩到 128
N = 128        # 成品边长

# 底色：与 HUD 面板同族（0x101418），上方稍亮一点，缩小后不至于糊成一块黑
BG_TOP    = (0x18, 0x20, 0x27)
BG_BOTTOM = (0x09, 0x0D, 0x11)
CORNER    = 24                 # 圆角半径（128 空间）
EDGE_COL  = (0xFF, 0xFF, 0xFF, 16)   # 内描边
GRID_COL  = (0xFF, 0xFF, 0xFF, 12)
GRID_STEP = 32                 # 方格网格间距

LINE_COL  = (0xFF, 0xD7, 0x40)       # 选线主色（HUD 的琥珀）
LINE_W    = 10
TIE_COL   = (0xFF, 0xB7, 0x4D)       # 枕木（桥橙，比主线深一档）
TIE_LEN   = 20
TIE_W     = 2.5
TIE_GAP   = 26

NODE_COL  = (0xE6, 0xE6, 0xE6)       # 顶点方块
NODE_EDGE = (0x10, 0x14, 0x18)
NODE_SZ   = 15
NODE_EDGE_W = 3

PATH   = [(15, 105), (48, 105), (76, 60), (113, 42)]   # 点过的顶点（128 空间）
RADIUS = 26                                          # 圆曲线半径（会被 0.45×段长 夹住）

STYLES = ("ties", "plain")


def fillet(pts, r, steps=96):
    """把折线在顶点处圆角化成 切线—圆弧—切线，返回密集点列。"""
    pts = [np.array(p, float) for p in pts]
    out = [pts[0]]
    for i in range(1, len(pts) - 1):
        A, B, C = pts[i - 1], pts[i], pts[i + 1]
        v1, v2 = A - B, C - B
        l1, l2 = np.linalg.norm(v1), np.linalg.norm(v2)
        v1u, v2u = v1 / l1, v2 / l2
        ang = math.acos(max(-1.0, min(1.0, float(v1u @ v2u))))
        if ang > math.pi - 1e-6:
            out.append(B)
            continue
        t = min(r / math.tan(ang / 2), l1 * 0.45, l2 * 0.45)
        rr = t * math.tan(ang / 2)
        T1, T2 = B + v1u * t, B + v2u * t
        bis = v1u + v2u
        cen = B + bis / np.linalg.norm(bis) * (rr / math.sin(ang / 2))
        a1 = math.atan2(T1[1] - cen[1], T1[0] - cen[0])
        a2 = math.atan2(T2[1] - cen[1], T2[0] - cen[0])
        d = (a2 - a1 + math.pi) % (2 * math.pi) - math.pi
        for k in range(steps + 1):
            a = a1 + d * k / steps
            out.append(cen + rr * np.array([math.cos(a), math.sin(a)]))
    out.append(pts[-1])
    return np.array(out)


def arclen_sampler(path):
    seg = np.linalg.norm(np.diff(path, axis=0), axis=1)
    cum = np.concatenate([[0.0], np.cumsum(seg)])

    def at(s):
        s = min(max(s, 0.0), cum[-1])
        i = min(int(np.searchsorted(cum, s, side="right") - 1), len(seg) - 1)
        f = 0.0 if seg[i] == 0 else (s - cum[i]) / seg[i]
        return path[i] + (path[i + 1] - path[i]) * f, (path[i + 1] - path[i]) / max(seg[i], 1e-9)

    return at, cum[-1]


def gradient(w):
    top, bot = np.array(BG_TOP, float), np.array(BG_BOTTOM, float)
    ramp = np.linspace(0, 1, w)[:, None, None]
    arr = (top * (1 - ramp) + bot * ramp).astype(np.uint8)
    return Image.fromarray(np.repeat(arr, w, axis=1), "RGB").convert("RGBA")


def layer(w, draws, clip=True):
    """透明层上画，按圆角蒙版裁，再 alpha 合成回去。"""
    lay = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    draws(ImageDraw.Draw(lay))
    if clip:
        mask = Image.new("L", (w, w), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, w - 1], radius=CORNER * S, fill=255)
        lay.putalpha(Image.composite(lay.getchannel("A"), Image.new("L", (w, w), 0), mask))
    return lay


def cap(d, q, r, col):
    d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=col + (255,))


def draw_route(d, path, ties):
    d.line([tuple(p) for p in path], fill=LINE_COL + (255,), width=int(LINE_W * S), joint="curve")
    for q in (path[0], path[-1]):
        cap(d, q, LINE_W * S / 2, LINE_COL)
    if ties:
        at, total = arclen_sampler(path)
        half = TIE_LEN * S / 2
        s = TIE_GAP * S * 0.75
        while s < total - half:
            p, tg = at(s)
            nrm = np.array([-tg[1], tg[0]])
            a, b = p + nrm * half, p - nrm * half
            d.line([tuple(a), tuple(b)], fill=TIE_COL + (255,), width=int(round(TIE_W * S)))
            cap(d, a, TIE_W * S / 2, TIE_COL)
            cap(d, b, TIE_W * S / 2, TIE_COL)
            s += TIE_GAP * S


def draw_nodes(d, path):
    h = NODE_SZ * S / 2
    for q in (path[0], path[-1]):
        d.rounded_rectangle([q[0] - h, q[1] - h, q[0] + h, q[1] + h], radius=3 * S,
                            fill=NODE_COL + (255,), outline=NODE_EDGE + (255,), width=NODE_EDGE_W * S)


def build(style="ties", path_pts=PATH, radius=RADIUS):
    w = N * S
    canvas = gradient(w)

    def grid(d):
        for k in range(0, N // GRID_STEP + 1):
            x = k * GRID_STEP * S
            d.line([(x, 0), (x, w)], fill=GRID_COL, width=1 * S)
            d.line([(0, x), (w, x)], fill=GRID_COL, width=1 * S)

    canvas = Image.alpha_composite(canvas, layer(w, grid))

    path = fillet(path_pts, radius) * S
    canvas = Image.alpha_composite(canvas, layer(w, lambda d: draw_route(d, path, style == "ties")))
    canvas = Image.alpha_composite(canvas, layer(w, lambda d: draw_nodes(d, path), clip=False))

    def rim(d):
        d.rounded_rectangle([0, 0, w - 1, w - 1], radius=CORNER * S - 1,
                            outline=EDGE_COL, width=2 * S)

    canvas = Image.alpha_composite(canvas, layer(w, rim))
    return canvas.resize((N, N), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--style", default="ties", choices=STYLES)
    ap.add_argument("--sheet", help="另存一张对照图")
    a = ap.parse_args()

    out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "src", "main", "resources", "assets", "railplanner", "icon.png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    im = build(a.style)
    im.save(out, optimize=True)
    print("written", out, os.path.getsize(out), "B", im.size, im.mode)

    if a.sheet:
        cands = [(s, build(s)) for s in STYLES]
        cell, pad = 128, 12
        sheet = Image.new("RGBA", (pad + (cell + pad) * len(cands), pad + cell + 76), (30, 30, 34, 255))
        d = ImageDraw.Draw(sheet)
        for i, (name, c) in enumerate(cands):
            x = pad + i * (cell + pad)
            d.text((x, pad + 4), name, fill=(220, 220, 220, 255))
            sheet.alpha_composite(c, (x, pad + 20))
            sheet.alpha_composite(c.resize((32, 32), Image.LANCZOS), (x, pad + 20 + cell + 14))
            sheet.alpha_composite(c.resize((16, 16), Image.LANCZOS), (x + 42, pad + 20 + cell + 14))
            d.text((x, pad + 20 + cell + 52), "32 / 16", fill=(160, 160, 160, 255))
        sheet.save(a.sheet)
        print("sheet", a.sheet, sheet.size)


if __name__ == "__main__":
    main()
