#!/usr/bin/env python3
"""A small QR code encoder (byte mode, error level L, versions 1 to 12), pure Python, no packages.
`encode(text)` returns a square list of rows of 0/1 (1 = dark). `render(matrix)` returns terminal text (two rows per line).
Run:  python3 qr.py "some text"
"""
import sys

# version -> (data blocks as [(count, data codewords per block)], error codewords per block); error level L
BLOCKS = {
    1: ([(1, 19)], 7), 2: ([(1, 34)], 10), 3: ([(1, 55)], 15), 4: ([(1, 80)], 20), 5: ([(1, 108)], 26),
    6: ([(2, 68)], 18), 7: ([(2, 78)], 20), 8: ([(2, 97)], 24), 9: ([(2, 116)], 30),
    10: ([(2, 68), (2, 69)], 18), 11: ([(4, 81)], 20), 12: ([(2, 92), (2, 93)], 24),
}
ALIGN = {1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42],
         9: [6, 26, 46], 10: [6, 28, 50], 11: [6, 30, 54], 12: [6, 32, 58]}

EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]


def _rs_generator(n):
    g = [1]
    for i in range(n):
        nxt = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            nxt[j] ^= c
            if c:
                nxt[j + 1] ^= EXP[LOG[c] + i]
        g = nxt
    return g


def rs_remainder(data, n):
    """The n error codewords of [data]."""
    gen = _rs_generator(n)
    rem = [0] * n
    for b in data:
        f = b ^ rem[0]
        rem = rem[1:] + [0]
        if f:
            for i in range(n):
                rem[i] ^= EXP[LOG[gen[i + 1]] + LOG[f]] if gen[i + 1] else 0
    return rem


def capacity(version):
    blocks, _ = BLOCKS[version]
    return sum(c * d for c, d in blocks)


def pick_version(n_bytes):
    for v in sorted(BLOCKS):
        head = 4 + (8 if v <= 9 else 16)
        if head + 8 * n_bytes <= 8 * capacity(v):
            return v
    raise ValueError("text too long for this encoder (%d bytes, max %d)" % (n_bytes, capacity(12) - 3))


def data_codewords(data, version):
    bits = [0, 1, 0, 0]
    n = len(data)
    cc = 8 if version <= 9 else 16
    bits += [(n >> i) & 1 for i in range(cc - 1, -1, -1)]
    for b in data:
        bits += [(b >> i) & 1 for i in range(7, -1, -1)]
    total = 8 * capacity(version)
    bits += [0] * min(4, total - len(bits))
    while len(bits) % 8:
        bits.append(0)
    out = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = [0xEC, 0x11]
    while len(out) < capacity(version):
        out.append(pad[(len(out) - len(bits) // 8) % 2])
    return out


def interleave(codewords, version):
    blocks, ecn = BLOCKS[version]
    data_blocks, pos = [], 0
    for count, size in blocks:
        for _ in range(count):
            data_blocks.append(codewords[pos:pos + size])
            pos += size
    ec_blocks = [rs_remainder(b, ecn) for b in data_blocks]
    out = []
    for i in range(max(len(b) for b in data_blocks)):
        for b in data_blocks:
            if i < len(b):
                out.append(b[i])
    for i in range(ecn):
        for b in ec_blocks:
            out.append(b[i])
    return out


def _bch(value, poly, bits_total, shift):
    v = value << shift
    top = poly.bit_length() - 1
    for i in range(bits_total - 1, top - 1, -1):
        if v >> i & 1:
            v ^= poly << (i - top)
    return (value << shift) | v


def format_bits(mask):
    return _bch((0b01 << 3) | mask, 0x537, 15, 10) ^ 0x5412


def version_bits(version):
    return _bch(version, 0x1F25, 18, 12)


MASKS = [lambda r, c: (r + c) % 2 == 0, lambda r, c: r % 2 == 0, lambda r, c: c % 3 == 0, lambda r, c: (r + c) % 3 == 0,
         lambda r, c: (r // 2 + c // 3) % 2 == 0, lambda r, c: (r * c) % 2 + (r * c) % 3 == 0,
         lambda r, c: ((r * c) % 2 + (r * c) % 3) % 2 == 0, lambda r, c: ((r + c) % 2 + (r * c) % 3) % 2 == 0]


def function_map(version):
    """(matrix with the fixed patterns drawn, same-size map where True = not a data cell)."""
    n = 17 + 4 * version
    m = [[0] * n for _ in range(n)]
    f = [[False] * n for _ in range(n)]

    def put(r, c, v):
        if 0 <= r < n and 0 <= c < n:
            m[r][c] = v
            f[r][c] = True

    for (r0, c0) in ((0, 0), (0, n - 7), (n - 7, 0)):
        for dr in range(-1, 8):
            for dc in range(-1, 8):
                inside = 0 <= dr <= 6 and 0 <= dc <= 6
                dark = inside and (dr in (0, 6) or dc in (0, 6) or (2 <= dr <= 4 and 2 <= dc <= 4))
                put(r0 + dr, c0 + dc, 1 if dark else 0)
    for i in range(8, n - 8):
        put(6, i, 1 - i % 2)
        put(i, 6, 1 - i % 2)
    pos = ALIGN[version]
    for r in pos:
        for c in pos:
            if (r, c) in ((6, 6), (6, pos[-1]), (pos[-1], 6)):
                continue
            for dr in range(-2, 3):
                for dc in range(-2, 3):
                    put(r + dr, c + dc, 1 if max(abs(dr), abs(dc)) != 1 else 0)
    put(n - 8, 8, 1)   # the always-dark module
    for i in range(9):   # reserve the format areas
        for (r, c) in ((8, i), (i, 8)):
            if not f[r][c]:
                put(r, c, 0)
    for i in range(8):
        put(8, n - 1 - i, 0)
        put(n - 1 - i, 8, 0)
    put(n - 8, 8, 1)
    if version >= 7:
        vb = version_bits(version)
        for i in range(18):
            bit = vb >> i & 1
            put(n - 11 + i % 3, i // 3, bit)
            put(i // 3, n - 11 + i % 3, bit)
    return m, f


def _penalty(m):
    n = len(m)
    p = 0
    for grid in (m, [list(col) for col in zip(*m)]):
        for row in grid:
            run = 1
            for i in range(1, n):
                if row[i] == row[i - 1]:
                    run += 1
                else:
                    if run >= 5:
                        p += run - 2
                    run = 1
            if run >= 5:
                p += run - 2
            s = "".join(map(str, row))
            p += 40 * (s.count("10111010000") + s.count("00001011101"))
    for r in range(n - 1):
        for c in range(n - 1):
            if m[r][c] == m[r + 1][c] == m[r][c + 1] == m[r + 1][c + 1]:
                p += 3
    dark = sum(map(sum, m))
    p += 10 * (abs(dark * 20 - 10 * n * n) // (n * n))
    return p


def _place(base, fmap, stream, mask):
    n = len(base)
    m = [row[:] for row in base]
    bits = [(b >> i) & 1 for b in stream for i in range(7, -1, -1)]
    k, up = 0, True
    c = n - 1
    while c > 0:
        if c == 6:
            c -= 1
        for step in range(n):
            r = n - 1 - step if up else step
            for cc in (c, c - 1):
                if not fmap[r][cc]:
                    bit = bits[k] if k < len(bits) else 0
                    k += 1
                    m[r][cc] = bit ^ (1 if MASKS[mask](r, cc) else 0)
        up = not up
        c -= 2
    fb = format_bits(mask)
    for i in range(15):
        bit = fb >> i & 1
        if i < 6:
            m[i][8] = bit
        elif i == 6:
            m[7][8] = bit
        elif i == 7:
            m[8][8] = bit
        elif i == 8:
            m[8][7] = bit
        else:
            m[8][14 - i] = bit
        if i < 8:
            m[8][n - 1 - i] = bit
        else:
            m[n - 15 + i][8] = bit
    return m


def encode(text):
    data = text.encode("utf-8")
    version = pick_version(len(data))
    stream = interleave(data_codewords(data, version), version)
    base, fmap = function_map(version)
    best = None
    for mask in range(8):
        m = _place(base, fmap, stream, mask)
        s = _penalty(m)
        if best is None or s < best[0]:
            best = (s, m)
    return best[1]


def render(m, quiet=2):
    """Terminal text: two matrix rows per line with half-block characters, dark on a light background."""
    n = len(m)
    size = n + 2 * quiet
    g = [[0] * size for _ in range(size)]
    for r in range(n):
        for c in range(n):
            g[r + quiet][c + quiet] = m[r][c]
    if size % 2:
        g.append([0] * size)
    glyph = {(1, 1): "█", (0, 0): " ", (0, 1): "▄", (1, 0): "▀"}   # black on white whatever the terminal theme
    lines = []
    for r in range(0, len(g), 2):
        lines.append("\x1b[30;47m" + "".join(glyph[(g[r][c], g[r + 1][c])] for c in range(size)) + "\x1b[0m")
    return "\n".join(lines)


if __name__ == "__main__":
    print(render(encode(" ".join(sys.argv[1:]) or "hello")))
