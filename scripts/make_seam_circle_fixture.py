#!/usr/bin/env python3
"""生成「跨缝小字圆泡 + 同页大字气泡」夹具（2026-10-15）。

复刻用户真机现场（Kakao）：圆形气泡跨两张切图，泡里两行小字；全页还有更大的
字号把「页面中位行高」拉大 —— 用来验证 `growBubbleBottomPx` 的
`行数 × 中位行高 × 1.2` 是否把**小字号跨缝气泡**的框撑成文字高度的两倍。

用法：python scripts/make_seam_circle_fixture.py
输出：.verify/web/seam-circle-a.png / seam-circle-b.png / seam-circle.html
"""

import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_A = os.path.join(ROOT, ".verify", "web", "seam-circle-a.png")
OUT_B = os.path.join(ROOT, ".verify", "web", "seam-circle-b.png")
OUT_HTML = os.path.join(ROOT, ".verify", "web", "seam-circle.html")
FONT_PATH = r"C:\Windows\Fonts\malgun.ttf"

W, H = 760, 1000

# 大字气泡（把页面中位行高拉大：字号 96 → 行高 ≈ 110）
BIG_BOX = (60, 180, 700, 500)
BIG_LINES = ["이건 아주 큰 글씨", "두 줄짜리 대사다"]

# 跨缝圆泡：圆心 (380, 960)，半径 250 → 圆跨 A 的下边界（700..1000 在 A，0..210 在 B）
CIRCLE = (130, 710, 630, 1210)
CIRCLE_LINES = ["잘", "했는데,"]
CIRCLE_FONT_SIZE = 46


def draw_centered_lines(draw, lines, font, line_height, center_x, top_y, fill):
    for index, text in enumerate(lines):
        box = draw.textbbox((0, 0), text, font=font)
        width = box[2] - box[0]
        draw.text((center_x - width // 2, top_y + index * line_height), text, font=font, fill=fill)


def draw_page(draw, offset_y):
    """把整页内容画在 (0, offset_y) 的坐标系里（cut A: offset 0；cut B: offset -1000）。"""
    # 大字气泡
    draw.rounded_rectangle(
        (BIG_BOX[0], BIG_BOX[1] - offset_y, BIG_BOX[2], BIG_BOX[3] - offset_y),
        radius=24, fill=(255, 255, 255), outline=(30, 30, 34), width=4,
    )
    big_font = ImageFont.truetype(FONT_PATH, 96)
    draw_centered_lines(
        draw, BIG_LINES, big_font, 118,
        (BIG_BOX[0] + BIG_BOX[2]) // 2, BIG_BOX[1] + 30 - offset_y, (20, 20, 24),
    )
    # 跨缝圆泡
    draw.ellipse(
        (CIRCLE[0], CIRCLE[1] - offset_y, CIRCLE[2], CIRCLE[3] - offset_y),
        fill=(255, 255, 255), outline=(30, 30, 34), width=4,
    )
    circle_font = ImageFont.truetype(FONT_PATH, CIRCLE_FONT_SIZE)
    draw_centered_lines(
        draw, CIRCLE_LINES, circle_font, 58,
        (CIRCLE[0] + CIRCLE[2]) // 2, 930 - offset_y, (20, 20, 24),
    )


def main():
    # 背景：浅灰 + 轻微纹理（与用户现场的白底漫画一致，但留一点可辨识的点）
    for path, offset in ((OUT_A, 0), (OUT_B, -1000)):
        img = Image.new("RGB", (W, H), (238, 238, 240))
        draw = ImageDraw.Draw(img)
        for y in range(0, H, 7):
            draw.line((0, y, W, y), fill=(236, 236, 238))
        draw_page(draw, offset)
        img.save(path)

    html = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>MT 跨缝圆泡夹具</title>
<style>
  html, body { margin: 0; padding: 0; background: #16181c; }
  #page img { display: block; width: 100%; margin: 0; }
</style>
</head>
<body>
<div id="page">
  <img src="seam-circle-a.png" width="760" height="1000" alt="cut A">
  <img src="seam-circle-b.png" width="760" height="1000" alt="cut B">
</div>
</body></html>
"""
    with open(OUT_HTML, "w", encoding="utf-8") as handle:
        handle.write(html)
    print("wrote", OUT_A)
    print("wrote", OUT_B)
    print("wrote", OUT_HTML)


if __name__ == "__main__":
    main()
