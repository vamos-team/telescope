<script setup lang="ts">
// 대시보드 SVG 차트 (v01-stella 데모의 bars · lines · waterfall · 산점도 · 누적 막대 · 순위 막대 · 트리맵을 옮김). 의존성 없이 SVG 요소 목록을 만들어 그린다
// 툴팁은 SVG <title>. 색은 시리즈 토큰(--s1~--s4)
import { computed } from "vue";
import { com, type Chart } from "../../local/dashboard";

const props = defineProps<{ chart: Chart }>();
type El = { t: string; a: Record<string, string | number>; x?: string | number; tip?: string };

function niceMax(v: number) { if (v <= 0) return 1; const p = Math.pow(10, Math.floor(Math.log10(v))), n = v / p; return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p; }
function ticks(min: number, max: number, n = 4) { const step = niceMax((max - min) / n), lo = Math.floor(min / step) * step, hi = Math.ceil(max / step) * step;
  const out: number[] = []; for (let v = lo; v <= hi + step / 2; v += step) out.push(+v.toFixed(6)); return out; }
const fmtAx = (v: number) => Math.abs(v) >= 10000 ? (v / 10000).toFixed(Math.abs(v) % 10000 ? 1 : 0) + "만" : Number.isInteger(v) ? com(v) : String(+v.toFixed(2));
const fill = (c: string, op = 1) => `fill:var(${c});opacity:${op}`;
const text = (x: number, y: number, s: string | number, cls = "", anchor = "start"): El => ({ t: "text", a: { x, y, class: cls, "text-anchor": anchor }, x: s });
function frame(W: number, pl: number, pr: number, tk: number[], Y: (v: number) => number, ax: (v: number) => string = fmtAx): El[] {
  return [...tk.map((t): El => ({ t: "line", a: { x1: pl, x2: W - pr, y1: Y(t), y2: Y(t), class: "gl" } })), ...tk.map((t) => text(pl - 6, Y(t) + 4, ax(t), "", "end"))];
}

const view = computed(() => {
  const c = props.chart, E: El[] = [];
  if (c.type === "bars") {
    const W = c.W ?? 560, H = 220, pl = 44, pr = 8, pt = 18, pb = 26, n = c.labels.length, k = c.series.length, f = c.fmt ?? com;
    const all = c.series.flatMap((s) => s.v), tk = ticks(Math.min(0, ...all), Math.max(0, ...all)), mn = tk[0], mx = tk[tk.length - 1];
    const Y = (v: number) => pt + (mx - v) / (mx - mn) * (H - pt - pb), gw = (W - pl - pr) / n, bw = Math.min(c.bw ?? 40, (gw - 16) / k - 3);
    E.push(...frame(W, pl, pr, tk, Y));
    c.labels.forEach((L, i) => {
      const gx = pl + i * gw + (gw - (bw * k + 2 * (k - 1))) / 2, lastI = i === n - 1;
      c.series.forEach((s, j) => { const v = s.v[i], y0 = Y(0), y1 = Y(v);
        E.push({ t: "rect", a: { x: gx + j * (bw + 3), y: Math.min(y0, y1), width: bw, height: Math.max(1, Math.abs(y1 - y0)), rx: 4, style: fill(s.c, s.fade && !lastI ? 0.45 : 1) }, tip: `${L} · ${s.name} ${f(v)}` });
        if ((c.labelLast && lastI && j === 0) || (k === 1 && n <= 6)) E.push(text(gx + j * (bw + 2) + bw / 2, v >= 0 ? y1 - 6 : y1 + 14, f(v), lastI ? "b" : "m", "middle")); });
      if (n <= 6 || i % 2 === 0 || i === n - 1) E.push(text(pl + i * gw + gw / 2, H - 8, L, "", "middle"));
    });
    E.push({ t: "line", a: { x1: pl, x2: W - pr, y1: Y(0), y2: Y(0), class: "axis" } });
    return { W, H, E };
  }
  if (c.type === "lines") {
    const W = c.W ?? 560, H = 220, pl = 44, pr = c.pr ?? 96, pt = 18, pb = 26, n = c.labels.length, f = c.fmt ?? com;
    const all = c.series.flatMap((s) => s.v), tk = ticks(c.min ?? Math.min(...all), c.max ?? Math.max(...all)), mn = tk[0], mx = tk[tk.length - 1];
    const X = (i: number) => pl + (n === 1 ? 0 : i * (W - pl - pr) / (n - 1)), Y = (v: number) => pt + (mx - v) / (mx - mn) * (H - pt - pb);
    E.push(...frame(W, pl, pr, tk, Y, c.axfmt ? (v) => c.axfmt!(+v.toFixed(2)) : fmtAx));
    if (c.ref !== undefined) E.push({ t: "line", a: { x1: pl, x2: W - pr, y1: Y(c.ref), y2: Y(c.ref), class: "ref" } }, text(W - pr + 6, Y(c.ref) + 4, c.refLabel ?? c.ref));
    const ends: { y: number; t: string; bold?: boolean }[] = [];
    for (const s of c.series) {
      const pts = s.v.map((v, i) => [X(i), Y(v)]), ps = pts.map((p) => p.join(",")).join(" ");
      if (s.bold) E.push({ t: "polygon", a: { points: `${X(0)},${Y(mn)} ${ps} ${X(n - 1)},${Y(mn)}`, style: fill(s.c, 0.08) } });
      E.push({ t: "polyline", a: { points: ps, style: `fill:none;stroke:var(${s.c});stroke-width:${s.bold ? 3 : 2.2};stroke-linejoin:round;stroke-linecap:round` } });
      pts.forEach((p, i) => E.push({ t: "circle", a: { cx: p[0], cy: p[1], r: i === n - 1 ? 4.5 : 3, style: `fill:var(${s.c});stroke:var(--surface);stroke-width:1.5` }, tip: `${c.labels[i]} · ${s.name ? s.name + " " : ""}${f(s.v[i])}` }));
      ends.push({ y: pts[n - 1][1], t: `${s.name ? s.name + " " : ""}${f(s.v[n - 1])}`, bold: s.bold });
    }
    ends.sort((a, b) => a.y - b.y); for (let i = 1; i < ends.length; i++) if (ends[i].y - ends[i - 1].y < 14) ends[i].y = ends[i - 1].y + 14;
    for (const e of ends) E.push(text(X(n - 1) + 9, e.y + 4, e.t, e.bold ? "b" : "m"));
    c.labels.forEach((L, i) => { if (n <= 6 || i % 2 === 0 || i === n - 1) E.push(text(X(i), H - 8, L, "", "middle")); });
    return { W, H, E };
  }
  if (c.type === "stack") {
    const W = c.W ?? 560, H = 230, pl = 48, pr = 8, pt = 16, pb = 26, n = c.labels.length, gw = (W - pl - pr) / n, bw = Math.min(60, gw - 30);
    const tot = c.labels.map((_, i) => c.series.reduce((a, s) => a + s.v[i], 0));
    const tk = c.norm ? [0, 25, 50, 75, 100] : ticks(0, niceMax(Math.max(...tot) * 1.05)), mx = tk[tk.length - 1], Y = (v: number) => pt + (mx - v) / mx * (H - pt - pb);
    E.push(...frame(W, pl, pr, tk, Y, c.norm ? (v) => v + "%" : fmtAx));
    c.labels.forEach((L, i) => {
      let acc = 0; const x = pl + i * gw + (gw - bw) / 2;
      for (const s of c.series) { const v = c.norm ? s.v[i] / tot[i] * 100 : s.v[i], y1 = Y(acc + v), y0 = Y(acc); acc += v;
        E.push({ t: "rect", a: { x, y: y1 + 1, width: bw, height: Math.max(0, y0 - y1 - 2), rx: 2, style: fill(s.c) }, tip: `${L} · ${s.name} ${c.norm ? v.toFixed(1) + "% (" + com(s.v[i]) + ")" : com(v)}` }); }
      if (!c.norm) E.push(text(x + bw / 2, Y(tot[i]) - 6, com(tot[i]), i === n - 1 ? "b" : "m", "middle"));
      E.push(text(x + bw / 2, H - 8, L, "", "middle"));
    });
    return { W, H, E };
  }
  if (c.type === "waterfall") {
    const W = 720, H = 250, pl = 48, pr = 8, pt = 24, pb = 34, n = c.steps.length; let acc = 0;
    const bars = c.steps.map(([l, v, t]) => { let a, b; if (t) { a = 0; b = v; acc = v; } else { a = acc; b = acc + v; acc = b; } return { l, v, t, a, b }; });
    const tk = ticks(0, niceMax(Math.max(...bars.map((b) => Math.max(b.a, b.b))) * 1.08)), top = tk[tk.length - 1];
    const Y = (v: number) => pt + (top - v) / top * (H - pt - pb), gw = (W - pl - pr) / n, bw = Math.min(54, gw - 18);
    E.push(...frame(W, pl, pr, tk, Y));
    bars.forEach((b, i) => {
      const x = pl + i * gw + (gw - bw) / 2, y = Math.min(Y(b.a), Y(b.b)), h = Math.max(1, Math.abs(Y(b.a) - Y(b.b))), lab = b.t ? com(b.v) : (b.v > 0 ? "+" : "") + com(b.v);
      E.push({ t: "rect", a: { x, y, width: bw, height: h, rx: 4, style: fill(b.t ? "--s1" : b.v < 0 ? "--s2" : "--s3") }, tip: `${b.l} ${lab}억원` },
        text(x + bw / 2, y - 6, lab, b.t ? "b" : "m", "middle"), text(x + bw / 2, H - 14, b.l, "", "middle"));
      if (i < n - 1) { const yc = Y(b.t ? b.v : b.b); E.push({ t: "line", a: { x1: x + bw, x2: x + gw, y1: yc, y2: yc, class: "ref" } }); }
    });
    return { W, H, E };
  }
  if (c.type === "scatter") {
    const W = 420, H = 250, pl = 44, pr = 16, pt = 16, pb = 34, [x0, x1] = c.xr, [y0, y1] = c.yr;
    const X = (v: number) => pl + (v - x0) / (x1 - x0) * (W - pl - pr), Y = (v: number) => pt + (y1 - v) / (y1 - y0) * (H - pt - pb);
    const yt = ticks(y0, y1, 3), xt = ticks(x0, x1, 3), rmax = Math.max(...c.points.map((p) => p.r));
    E.push(...frame(W, pl, pr, yt, Y, (v) => v + "%"), ...xt.map((t) => text(X(t), H - 16, t + "%", "", "middle")), text(W - pr, H - 2, c.xl, "", "end"), text(pl, pt - 4, c.yl));
    for (const p of c.points) { const r = 6 + Math.sqrt(p.r / rmax) * 16;
      E.push({ t: "circle", a: { cx: X(p.x), cy: Y(p.y), r, style: `fill:var(${p.c});opacity:.85;stroke:var(--surface);stroke-width:2` }, tip: `${p.n} · ${c.xl} ${p.x.toFixed(1)}%, ${c.yl} ${p.y.toFixed(1)}%, 매출 ${com(p.r)}억원` },
        text(X(p.x) + r + 5, Y(p.y) + 4, p.n, p.n === "SKT" ? "b" : "m")); }
    return { W, H, E };
  }
  if (c.type === "hbars") {
    const W = 420, H = 40 + c.items.length * 56;
    c.items.forEach((it, i) => { const y = 24 + i * 56, bw = (W - 170) * it.v / c.max;
      E.push(text(0, y + 18, `${i + 1}  ${it.n}`, it.n === "SKT" ? "b" : "m"), { t: "rect", a: { x: 110, y, width: Math.max(1, bw), height: 28, rx: 4, style: fill(it.c) }, tip: `${it.n} ${c.fmt(it.v)}` },
        text(116 + bw, y + 18, c.fmt(it.v), it.n === "SKT" ? "b" : "m")); });
    return { W, H, E };
  }
  if (c.type === "treemap") {
    // 1등은 왼쪽 열, 2·3등은 오른쪽 위 행, 나머지는 오른쪽 아래를 나눔 (v01 배치)
    const W = 420, H = 250, tot = c.items.reduce((a, x) => a + x[1], 0), [first, ...rest] = c.items;
    const box = (x: number, y: number, w: number, h: number, [n, v]: [string, number], col: string) => {
      E.push({ t: "rect", a: { x: x + 1, y: y + 1, width: Math.max(0, w - 2), height: Math.max(0, h - 2), rx: 4, style: fill(col) }, tip: `${n} ${com(v)}억원 (${(v / tot * 100).toFixed(1)}%)` });
      if (w > 70 && h > 34) E.push(text(x + 10, y + 20, n, "tw b"), text(x + 10, y + 36, `${(v / tot * 100).toFixed(1)}%`, "tw"));
    };
    const w0 = W * first[1] / tot; box(0, 0, w0, H, first, c.colors[0]);
    const rw = W - w0, rt = tot - first[1]; let y = 0;
    rest.slice(0, 2).forEach((it, i) => { const h = H * it[1] / rt; box(w0, y, rw, h, it, c.colors[i + 1]); y += h; });
    const rem = rest.slice(2), remT = rem.reduce((a, x) => a + x[1], 0); let x = w0;
    rem.forEach((it) => { const ww = rw * it[1] / remT; box(x, y, ww, H - y, it, c.colors[3]); x += ww; });
    return { W, H, E };
  }
  return null;
});
</script>

<template>
  <svg v-if="view" class="dchart" :viewBox="`0 0 ${view.W} ${view.H}`" role="img">
    <component :is="e.t" v-for="(e, i) in view.E" :key="i" v-bind="e.a"><title v-if="e.tip">{{ e.tip }}</title>{{ e.x }}</component>
  </svg>
</template>
