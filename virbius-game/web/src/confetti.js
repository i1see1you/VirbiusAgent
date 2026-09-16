export function burst(canvas, { reduced = false } = {}) {
  if (!canvas || reduced) return () => {};
  const ctx = canvas.getContext("2d");
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const resize = () => {
    canvas.width = canvas.clientWidth * dpr;
    canvas.height = canvas.clientHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  };
  resize();
  const colors = ["#f5c15d", "#7dd3fc", "#38bdf8", "#f8fafc", "#34d399", "#fb7185"];
  const n = 140;
  const pieces = Array.from({ length: n }, (_, i) => {
    const side = i % 2 === 0 ? 0.2 : 0.8;
    return {
      x: canvas.clientWidth * (side + (Math.random() - 0.5) * 0.25),
      y: -20 - Math.random() * 80,
      r: 4 + Math.random() * 6,
      vx: (Math.random() - 0.5) * 7,
      vy: 4 + Math.random() * 8,
      rot: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.25,
      color: colors[i % colors.length],
      w: 6 + Math.random() * 8,
      h: 3 + Math.random() * 4,
    };
  });
  let alive = true;
  const tick = () => {
    if (!alive) return;
    ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
    for (const p of pieces) {
      p.vy += 0.12;
      p.x += p.vx;
      p.y += p.vy;
      p.rot += p.vr;
      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rot);
      ctx.fillStyle = p.color;
      ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
      ctx.restore();
    }
    requestAnimationFrame(tick);
  };
  tick();
  return () => {
    alive = false;
    ctx.clearRect(0, 0, canvas.clientWidth, canvas.clientHeight);
  };
}
