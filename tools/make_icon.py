"""Furniture Fit icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the
adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py [export.png]

Layer 0: the masking-tape blue tile. Layer 1: a sofa on its tape footprint, the yellow gap tape running
out to a wall. sdf_N = the layer's alpha in white. Coordinates below are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
BLUE = (61, 139, 255, 255)
BLUE_EDGE = (31, 95, 204, 255)
WHITE = (255, 255, 255, 255)
SEAT = (227, 236, 255, 255)
INK = (22, 24, 28, 255)
YELLOW = (255, 199, 44, 255)
RED = (226, 59, 46, 255)


def r(d, x0, y0, x1, y1, fill, radius=0.0):
    box = (x0 * K, y0 * K, x1 * K, y1 * K)
    if radius > 0:
        d.rounded_rectangle(box, radius=radius * K, fill=fill)
    else:
        d.rectangle(box, fill=fill)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=BLUE)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    r(d, 20, 76, 74, 79, BLUE_EDGE)
    r(d, 26, 38, 68, 54, WHITE, 3)
    r(d, 26, 47, 68, 54, WHITE)
    r(d, 28, 54, 66, 66, SEAT)
    r(d, 22, 47, 30, 70, WHITE, 3)
    r(d, 22, 60, 30, 70, WHITE)
    r(d, 64, 47, 72, 70, WHITE, 3)
    r(d, 64, 60, 72, 70, WHITE)
    r(d, 24, 70, 27, 75, INK)
    r(d, 67, 70, 70, 75, INK)
    r(d, 72, 56, 83, 61, YELLOW)
    r(d, 72, 56, 73.6, 61, RED)
    for x, h in ((75.6, 2.6), (78.6, 1.8), (81.6, 2.6)):
        r(d, x, 56, x + 0.6, 56 + h, INK)
    r(d, 83, 34, 86, 79, WHITE)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        Image.alpha_composite(l0, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
