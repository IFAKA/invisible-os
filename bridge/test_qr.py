"""Tests for qr.py. A small decoder (read the format, remove the mask, read the codewords, check the error codes) proves the
code is a well formed QR code. On a Mac, Apple's own QR reader (swift + CoreImage) is used too when it is there."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import qr  # noqa: E402


def decode(m):
    n = len(m)
    version = (n - 17) // 4
    fb = 0
    for i in range(15):   # first format copy, same order as the encoder
        r, c = ((i, 8) if i < 6 else (7, 8) if i == 6 else (8, 8) if i == 7 else (8, 7) if i == 8 else (8, 14 - i))
        fb |= m[r][c] << i
    mask = next(k for k in range(8) if qr.format_bits(k) == fb)
    base, fmap = qr.function_map(version)
    bits, up, c = [], True, n - 1
    while c > 0:
        if c == 6:
            c -= 1
        for step in range(n):
            r = n - 1 - step if up else step
            for cc in (c, c - 1):
                if not fmap[r][cc]:
                    bits.append(m[r][cc] ^ (1 if qr.MASKS[mask](r, cc) else 0))
        up = not up
        c -= 2
    words = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits) - len(bits) % 8, 8)]
    blocks, ecn = qr.BLOCKS[version]
    sizes = [d for cnt, d in blocks for _ in range(cnt)]
    total_data = sum(sizes)
    data_blocks = [[] for _ in sizes]
    pos = 0
    for i in range(max(sizes)):
        for b, size in enumerate(sizes):
            if i < size:
                data_blocks[b].append(words[pos]); pos += 1
    ec_blocks = [[] for _ in sizes]
    for i in range(ecn):
        for b in range(len(sizes)):
            ec_blocks[b].append(words[pos]); pos += 1
    for d, e in zip(data_blocks, ec_blocks):
        assert qr.rs_remainder(d, ecn) == e, "error code words do not match"
    flat = [w for d in data_blocks for w in d]
    assert len(flat) == total_data
    bitstr = "".join(format(w, "08b") for w in flat)
    assert bitstr[:4] == "0100", "not byte mode"
    cc = 8 if version <= 9 else 16
    count = int(bitstr[4:4 + cc], 2)
    body = bitstr[4 + cc:4 + cc + 8 * count]
    return bytes(int(body[i:i + 8], 2) for i in range(0, len(body), 8)).decode("utf-8")


class QrTest(unittest.TestCase):
    def test_round_trip_many_sizes(self):
        for n in (1, 10, 17, 18, 40, 100, 150, 190, 220, 270, 330):
            text = ("invisible-pair:" + "Ab3-_" * 100)[:n]
            self.assertEqual(decode(qr.encode(text)), text, n)

    def test_utf8(self):
        self.assertEqual(decode(qr.encode("Facundo ñ é")), "Facundo ñ é")

    def test_size_and_finders(self):
        m = qr.encode("hi")
        self.assertEqual(len(m), 21)
        for r0, c0 in ((0, 0), (0, 14), (14, 0)):
            self.assertEqual([m[r0][c0 + i] for i in range(7)], [1] * 7)
            self.assertEqual(m[r0 + 3][c0 + 3], 1)
            self.assertEqual(m[r0 + 1][c0 + 1], 0)

    def test_too_long(self):
        with self.assertRaises(ValueError):
            qr.encode("x" * 400)

    def test_pair_code_fits(self):
        import server
        code = server.pair_code("Mac-Work-user", "http://100.101.102.103:8766", "t" * 32)
        self.assertEqual(decode(qr.encode(code)), code)

    def test_render_has_colors_and_lines(self):
        out = qr.render(qr.encode("hi"))
        self.assertIn("\x1b[30;47m", out)
        self.assertGreater(len(out.splitlines()), 10)

    @unittest.skipUnless(shutil.which("swift") and sys.platform == "darwin", "needs swift on a Mac")
    def test_apple_reader(self):
        text = "invisible-pair:" + "Ab3-_" * 40
        m = qr.encode(text)
        here = os.path.dirname(os.path.abspath(__file__))
        with tempfile.TemporaryDirectory() as d:
            mf = os.path.join(d, "m.txt")
            open(mf, "w").write("\n".join("".join(map(str, r)) for r in m))
            out = subprocess.run(["swift", os.path.join(here, "qr_check.swift"), mf], capture_output=True, text=True, timeout=120)
        self.assertEqual(out.stdout.strip(), text)


if __name__ == "__main__":
    unittest.main()
