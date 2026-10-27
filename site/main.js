(() => {
  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // --- rings of light behind everything: a click sends one out ---
  const cv = document.getElementById("field"), cx = cv.getContext("2d");
  let W, H, dpr, rings = [];
  const fit = () => { dpr = Math.min(devicePixelRatio || 1, 2); W = cv.width = innerWidth * dpr; H = cv.height = innerHeight * dpr; };
  fit(); addEventListener("resize", fit);
  const pulse = (x = innerWidth / 2, y = innerHeight / 2, n = 1) => {
    if (reduce) return;
    for (let i = 0; i < n; i++) rings.push({ x: x * dpr, y: y * dpr, t: -i * 0.18 });
  };
  let last = performance.now();
  (function frame(now) {
    const dt = Math.min((now - last) / 1000, .05); last = now;
    cx.clearRect(0, 0, W, H);
    rings = rings.filter(r => r.t < 1.6);
    for (const r of rings) {
      r.t += dt;
      if (r.t < 0) continue;
      const k = r.t / 1.6, e = 1 - Math.pow(1 - k, 3);
      cx.beginPath(); cx.arc(r.x, r.y, e * Math.max(W, H) * .55, 0, 7);
      cx.strokeStyle = `rgba(255,255,255,${(1 - k) * .28})`; cx.lineWidth = 1.2 * dpr; cx.stroke();
    }
    requestAnimationFrame(frame);
  })(last);

  // --- sound: opt in, small tones made with the browser (no files) ---
  let ac = null, on = false;
  const btn = document.getElementById("sound");
  const tone = (f, d = .12, v = .08, type = "sine", at = 0) => {
    if (!on || !ac) return;
    const o = ac.createOscillator(), g = ac.createGain(), t = ac.currentTime + at;
    o.type = type; o.frequency.value = f;
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(v, t + .01); g.gain.exponentialRampToValueAtTime(.0001, t + d);
    o.connect(g).connect(ac.destination); o.start(t); o.stop(t + d + .02);
  };
  btn.onclick = () => {
    on = !on; if (on && !ac) ac = new (window.AudioContext || window.webkitAudioContext)();
    if (ac && ac.state === "suspended") ac.resume();
    btn.textContent = on ? "Sound on" : "Sound off"; btn.setAttribute("aria-pressed", on);
    tone(660, .15);
  };

  // --- the button: click, tap or space ---
  const press = document.getElementById("press"), note = document.getElementById("pressNote");
  const lines = ["Hear that? That is a click.", "Now scroll. It is simple on purpose.", "One button is enough."];
  let n = 0;
  const hit = (x, y) => {
    pulse(x, y); tone(880, .1, .09); tone(1320, .14, .05, "sine", .07);
    note.textContent = lines[Math.min(n++, lines.length - 1)];
  };
  press.addEventListener("click", e => { const r = press.getBoundingClientRect(); hit(r.left + 32, r.top + 32); });
  addEventListener("keydown", e => { if (e.code === "Space" && e.target === document.body) { e.preventDefault(); press.classList.add("down"); } });
  addEventListener("keyup", e => { if (e.code === "Space" && e.target === document.body) { press.classList.remove("down"); press.click(); } });

  // --- demo menu: reads itself, you click to pick ---
  const items = ["Messages", "Tasks", "Timer", "Volume", "Status", "Games"];
  const ol = document.getElementById("list"), lnote = document.getElementById("listNote");
  const lis = items.map(t => { const li = document.createElement("li"); li.textContent = t; ol.appendChild(li); return li; });
  let i = -1, timer = null, visible = false, picked = false;
  const step = () => {
    if (picked) return;
    lis.forEach(l => l.classList.remove("on")); i = (i + 1) % lis.length;
    lis[i].classList.add("on"); tone(440 + i * 55, .09, .05);
  };
  const run = () => { if (!timer && visible && !picked) { step(); timer = setInterval(step, 1300); } };
  const stop = () => { clearInterval(timer); timer = null; };
  const choose = () => {
    if (!visible || picked || i < 0) return;
    picked = true; stop(); lis.forEach(l => l.classList.remove("on")); lis[i].classList.add("picked");
    tone(988, .12, .09); tone(1318, .2, .07, "sine", .09); pulse(innerWidth / 2, ol.getBoundingClientRect().top + ol.offsetHeight / 2, 2);
    lnote.textContent = `You picked ${items[i]}. That was one click.`;
    setTimeout(() => { picked = false; lis[i].classList.remove("picked"); lnote.textContent = "Click when you hear what you want."; run(); }, 3200);
  };
  ol.addEventListener("click", choose);
  ol.style.cursor = "pointer";
  addEventListener("keydown", e => { if (e.code === "Enter" && visible) choose(); });
  new IntersectionObserver(es => { visible = es[0].isIntersecting; visible ? run() : stop(); }, { threshold: .5 }).observe(ol);

  // --- reveal on scroll ---
  const io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { threshold: .15 });
  document.querySelectorAll(".reveal").forEach((el, k) => { el.style.transitionDelay = (k % 4) * 90 + "ms"; io.observe(el); });
  document.querySelectorAll('.rings[data-n]').forEach(r => { for (let k = 0; k < +r.dataset.n; k++) r.appendChild(document.createElement("i")); });
})();
