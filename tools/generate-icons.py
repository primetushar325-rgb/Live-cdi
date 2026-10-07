#!/usr/bin/env python3
"""
Generate the Mihad Live launcher + notification icons.

No third party dependency is needed (no PIL in CI/sandbox), the PNGs are written
by hand with zlib. Re-run with:  python3 tools/generate-icons.py
"""
import pathlib
import struct
import zlib

BLACK = (8, 10, 14, 255)
DEEP = (3, 4, 6, 255)
CYAN = (56, 189, 248, 255)
CYAN_DIM = (14, 116, 144, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


class Canvas:
    def __init__(self, size: int, bg=CLEAR):
        self.size = size
        self.px = [list(bg) for _ in range(size * size)]

    def blend(self, x: int, y: int, color) -> None:
        if not (0 <= x < self.size and 0 <= y < self.size):
            return
        r, g, b, a = color
        if a == 0:
            return
        dst = self.px[y * self.size + x]
        if a == 255:
            dst[:] = [r, g, b, 255]
            return
        sa = a / 255.0
        da = dst[3] / 255.0
        oa = sa + da * (1 - sa)
        if oa <= 0:
            return
        for i, src in enumerate((r, g, b)):
            dst[i] = int(round((src * sa + dst[i] * da * (1 - sa)) / oa))
        dst[3] = int(round(oa * 255))

    def fill(self, color) -> None:
        for i in range(self.size * self.size):
            self.px[i] = list(color)

    def rect(self, x0, y0, x1, y1, color) -> None:
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self.blend(x, y, color)

    def circle(self, cx, cy, radius, color) -> None:
        r2 = radius * radius
        for y in range(int(cy - radius) - 1, int(cy + radius) + 2):
            for x in range(int(cx - radius) - 1, int(cx + radius) + 2):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                if dx * dx + dy * dy <= r2:
                    self.blend(x, y, color)

    def ring(self, cx, cy, radius, thickness, color) -> None:
        outer = radius * radius
        inner = (radius - thickness) ** 2
        for y in range(int(cy - radius) - 1, int(cy + radius) + 2):
            for x in range(int(cx - radius) - 1, int(cx + radius) + 2):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                d2 = dx * dx + dy * dy
                if inner <= d2 <= outer:
                    self.blend(x, y, color)

    def line(self, x0, y0, x1, y1, width, color) -> None:
        steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 3) + 1
        half = width / 2.0
        for i in range(steps + 1):
            t = i / steps
            x = x0 + (x1 - x0) * t
            y = y0 + (y1 - y0) * t
            self.circle(x, y, half, color)

    def png(self) -> bytes:
        raw = bytearray()
        for i in range(self.size):
            raw.append(0)
            for j in range(self.size):
                raw.extend(self.px[i * self.size + j])
        def chunk(tag: bytes, data: bytes) -> bytes:
            return (struct.pack(">I", len(data)) + tag + data
                    + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

        header = struct.pack(">IIBBBBB", self.size, self.size, 8, 6, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
                + chunk(b"IEND", b""))


def launcher(size: int, round_icon: bool) -> Canvas:
    c = Canvas(size, CLEAR)
    s = size / 100.0
    if round_icon:
        c.circle(50 * s, 50 * s, 50 * s, DEEP)
        c.ring(50 * s, 50 * s, 49.5 * s, max(1.0, 2.2 * s), CYAN_DIM)
    else:
        c.fill(DEEP)
        # cyan glow border
        c.rect(0, 0, size - 1, int(1.8 * s), CYAN_DIM)
        c.rect(0, size - 1 - int(1.8 * s), size - 1, size - 1, CYAN_DIM)
        c.rect(0, 0, int(1.8 * s), size - 1, CYAN_DIM)
        c.rect(size - 1 - int(1.8 * s), 0, size - 1, size - 1, CYAN_DIM)

    # broadcast arcs
    for r, t in ((38 * s, 3.0 * s), (28 * s, 2.4 * s)):
        c.ring(50 * s, 50 * s, r, t, CYAN)
    # live dot
    c.circle(50 * s, 50 * s, 11 * s, CYAN)
    c.circle(50 * s, 50 * s, 5.5 * s, WHITE)
    return c


def notification(size: int) -> Canvas:
    """White silhouette on transparent: Android tints notification icons."""
    c = Canvas(size, CLEAR)
    s = size / 100.0
    c.circle(50 * s, 50 * s, 42 * s, WHITE)
    c.circle(50 * s, 50 * s, 22 * s, CLEAR)
    c.rect(46 * s, 8 * s, 54 * s, 30 * s, WHITE)
    return c


def write(path: pathlib.Path, canvas: Canvas) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canvas.png())
    print(f"  {path} ({path.stat().st_size} bytes)")


def main() -> None:
    root = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
    mipmaps = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 108, "xxxhdpi": 192}
    print("launcher icons")
    for name, size in mipmaps.items():
        write(root / f"mipmap-{name}/ic_launcher.png", launcher(size, False))
        write(root / f"mipmap-{name}/ic_launcher_round.png", launcher(size, True))
    print("notification icons")
    for name, size in {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72}.items():
        write(root / f"drawable-{name}/ic_stat_live.png", notification(size))


if __name__ == "__main__":
    main()
