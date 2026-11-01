"""Laptop activity: seconds since the last keyboard or mouse input (macOS ioreg HIDIdleTime), cached. Python 3 stdlib only."""
import re
import subprocess
import sys
import time

ACTIVE_BELOW_S = 120   # idle for less than this = the owner is at the laptop
HID_RE = re.compile(r'"HIDIdleTime"\s*=\s*(\d+)')


def parse_hid_idle(text):
    """Seconds (float) from `ioreg -c IOHIDSystem` output (HIDIdleTime is in nanoseconds; the smallest value wins), or None."""
    values = [int(m) for m in HID_RE.findall(text or "")]
    return min(values) / 1e9 if values else None


def ioreg_idle():
    """The real reader: idle seconds, or None (not macOS, no ioreg, any failure)."""
    if sys.platform != "darwin":
        return None
    try:
        out = subprocess.run(["ioreg", "-c", "IOHIDSystem"], capture_output=True, text=True, timeout=2).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    return parse_hid_idle(out)


class Idle:
    """idle_s() / active(), with the reader injectable and the answer cached for `ttl` seconds."""

    def __init__(self, reader=ioreg_idle, clock=time.monotonic, ttl=5.0):
        self.reader, self.clock, self.ttl = reader, clock, ttl
        self._at = None
        self._val = None

    def idle_s(self):
        now = self.clock()
        if self._at is None or now - self._at >= self.ttl:
            try:
                v = self.reader()
            except Exception:
                v = None
            self._val = None if v is None else round(float(v), 1)
            self._at = now
        return self._val

    def active(self):
        v = self.idle_s()
        return None if v is None else v < ACTIVE_BELOW_S
