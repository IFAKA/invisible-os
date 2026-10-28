"""Python port of app/oskit/.../logickit/ClickModel.kt (itself a port of Nomon's clock inference, GPL v3, see NOMON-LICENSE.md).
Used by tools/write_bench.py to simulate a person clicking in the letter loop. Same maths, same order of operations as the Kotlin;
`tools/write_bench.py --verify-click` compares the posteriors with the Kotlin dump (ClickModelDumpTest, build/click-dump.txt) within 1e-6."""
import math

class Params:
    def __init__(self, bins=80, mu0=0.05, sigma0=0.14, damp=0.96, win_diff=math.log(99), win_diff_high=math.log(999),
                 max_init_diff=math.log(99) - math.log(4), learn_delay=2, learn_wrap_s=1.0, min_prior=1e-9):
        self.bins, self.mu0, self.sigma0, self.damp = bins, mu0, sigma0, damp
        self.win_diff, self.win_diff_high, self.max_init_diff = win_diff, win_diff_high, max_init_diff
        self.learn_delay, self.learn_wrap_s, self.min_prior = learn_delay, learn_wrap_s, min_prior

def _wrap(a, m): return a - math.floor(a / m) * m

class ClickModel:
    def __init__(self, period_ms, params=None):
        p = self.params = params or Params()
        self.period_ms = period_ms; self.bins = p.bins
        self.n_ksigma = max(5, int(1.0 / (1 - p.damp)))
        self.ns_factor = 1.06 / self.n_ksigma ** 0.2
        self.n_hist = min(200, int(math.log(0.02) / math.log(p.damp)))
        self.period_s = period_ms / 1000.0
        self.xs = [i * self.period_s / self.bins - self.period_s / 2.0 for i in range(self.bins)]
        self.dens = [0.0] * self.bins; self.z = 0.0
        self.ksigma0 = 1.06 * p.sigma0 / self.n_ksigma ** 0.2; self.ksigma = self.ksigma0
        self.ys = []; self.yk = []   # newest first
        self.keys = []; self.noon = []; self.gate = []; self.scores = []; self.start_ms = 0.0; self.sorted = []
        self.clock_history = [[]]; self.win_history = [None]
        self._init_dens()

    def _init_dens(self):
        p = self.params; self.z = 0.0
        for i in range(self.bins):
            diff = self.xs[i] - p.mu0
            d = math.exp(-1 / (2 * p.sigma0 * p.sigma0) * diff * diff)
            d /= math.sqrt(2 * math.pi * p.sigma0 * p.sigma0)
            d *= self.n_ksigma
            self.dens[i] = d; self.z += d

    def _increment(self, yin, ks):
        self.z = 0.0; ks_sq = ks * ks; damp = self.params.damp
        for i in range(self.bins):
            diff = self.xs[i] - yin
            d = math.exp(-1 / (2 * ks_sq) * diff * diff)
            d /= math.sqrt(2 * math.pi * ks_sq)
            self.dens[i] = damp * self.dens[i] + d
            self.z += self.dens[i]

    def _calc_ksigma(self, n):
        ysum = 0.0; y2sum = 0.0
        for i in range(n): ysum += self.ys[i]; y2sum += self.ys[i] * self.ys[i]
        a = (self.n_ksigma - n) * self.ksigma0 * self.ksigma0
        if n > 0: a += y2sum - ysum * ysum / n
        a /= self.n_ksigma
        self.ksigma = self.ns_factor * math.sqrt(max(0.0001, a))

    def _inc_score(self, yin):
        if len(self.ys) > self.n_hist: self.ys.pop(); self.yk.pop()
        self.ys.insert(0, yin); self.yk.insert(0, self.ksigma)
        self._increment(yin, self.ksigma)
        self._calc_ksigma(min(self.n_ksigma, len(self.ys)))

    def _score_inc(self, y_s):
        idx = math.floor(self.bins * (y_s / self.period_s + 0.5)) % self.bins
        if self.z == 0.0: return 1.0
        d = self.dens[idx] / self.z
        return math.log(d) if d > 0 else -math.inf   # Kotlin ln(0) = -Infinity

    def _offset_s(self, i, at_ms):
        y = (at_ms - self.start_ms) / 1000.0 - self.noon[i]
        return _wrap(y + self.period_s / 2, self.period_s) - self.period_s / 2

    def start(self, priors, times_ms, cycle_start_ms=0.0, win_diffs=None):
        """priors, times_ms: dicts key -> value (insertion order = item order); win_diffs: dict key -> gap."""
        win_diffs = win_diffs or {}
        self.keys = list(priors.keys())
        self.noon = [times_ms.get(k, 0.0) / 1000.0 for k in self.keys]
        self.gate = [win_diffs.get(k, self.params.win_diff) for k in self.keys]
        self.scores = [math.log(max(priors[k], self.params.min_prior)) for k in self.keys]
        self.start_ms = cycle_start_ms
        self._sort()
        if len(self.keys) > 1 and self.scores[self.sorted[0]] - self.scores[self.sorted[1]] > self.params.max_init_diff:
            self.scores[self.sorted[0]] = self.scores[self.sorted[1]] + self.params.max_init_diff

    def _sort(self): self.sorted = sorted(range(len(self.keys)), key=lambda i: -self.scores[i])   # stable

    def click(self, at_ms):
        if not self.keys: return False
        hist = {}
        w = self.params.learn_wrap_s
        for i in range(len(self.keys)):
            y = self._offset_s(i, at_ms)
            self.scores[i] += self._score_inc(y)
            hist[self.keys[i]] = _wrap(y + w / 2, w) - w / 2
        self.clock_history[0].append(hist)
        self._sort()
        return self.chosen() is not None

    def posteriors(self):
        if not self.keys: return {}
        m = max(self.scores); e = [math.exp(s - m) for s in self.scores]; s = sum(e)
        return {k: v / s for k, v in zip(self.keys, e)}

    def best(self): return self.keys[self.sorted[0]] if self.sorted else None

    def chosen(self):
        if not self.keys: return None
        if len(self.keys) <= 1: return self.keys[0]
        a = self.sorted[0]
        return self.keys[a] if self.scores[a] - self.scores[self.sorted[1]] > self.gate[a] else None

    def learn(self, key): self.win_history[0] = key; self._learn_scores(False)

    def offsets(self): return list(self.ys)

    def seed(self, offsets_newest_first):
        for y in reversed(offsets_newest_first[:self.n_hist + 1]): self._inc_score(y)

    def inherit(self, prev):
        self.seed(prev.ys)
        self.clock_history = list(prev.clock_history); self.win_history = list(prev.win_history)

    def undo(self): self._learn_scores(True)

    def _learn_scores(self, is_undo):
        n = len(self.clock_history)
        if is_undo:
            if n > 1: del self.clock_history[0:2]; del self.win_history[0:2]
            else: self.clock_history.clear(); self.win_history.clear()
        elif n > self.params.learn_delay:
            sel = n - self.params.learn_delay
            win = self.win_history[sel]
            if win is not None:
                for press in self.clock_history[sel]:
                    if win in press: self._inc_score(press[win])
            for i in range(n - 1, self.params.learn_delay - 1, -1):
                del self.clock_history[i]; del self.win_history[i]
        self.clock_history.insert(0, []); self.win_history.insert(0, None)
