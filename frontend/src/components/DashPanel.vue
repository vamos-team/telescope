<script setup lang="ts">
// 대시보드 패널 하나 (자료 검색 화면과 캔버스 대시보드 카드가 함께 씀). 차트는 의존성 없이 SVG
import { computed } from "vue";
import { DASH, opMargin, debtRatio } from "../local/dashboard";

const props = defineProps<{ panel: string }>();
const Y = DASH.years, COS = DASH.companies;
const W = 520, H = 190, PL = 52, PR = 76, PT = 14, PB = 24;
const com = (v: number) => v.toLocaleString("ko-KR");
const last = (xs: number[]) => xs[xs.length - 1];
const x = (i: number) => PL + i * (W - PL - PR) / (Y.length - 1);

// 3사 영업이익 선 그래프
const opi = computed(() => {
  const all = COS.flatMap((c) => DASH.op_income[c]), mx = Math.ceil(Math.max(...all) / 5000) * 5000;
  const y = (v: number) => PT + (mx - v) / mx * (H - PT - PB);
  return { ticks: [0, mx / 2, mx].map((v) => ({ v, y: y(v) })),
    lines: COS.map((c, k) => ({ c, k, pts: DASH.op_income[c].map((v, i) => `${x(i)},${y(v)}`).join(" "), end: y(last(DASH.op_income[c])), v: last(DASH.op_income[c]) })) };
});
// CapEx·감가상각 묶음 막대
const cap = computed(() => {
  const a = DASH.capex.SKT, b = DASH.dep_ppe.SKT, mx = Math.ceil(Math.max(...a, ...b) / 10000) * 10000;
  const y = (v: number) => PT + (mx - v) / mx * (H - PT - PB), gw = (W - PL - 20) / Y.length, bw = 18;
  return { ticks: [0, mx / 2, mx].map((v) => ({ v, y: y(v) })), bars: Y.map((yr, i) => ({ yr, cx: PL + i * gw + gw / 2,
    a: { x: PL + i * gw + gw / 2 - bw - 1, y: y(a[i]), h: y(0) - y(a[i]), v: a[i] }, b: { x: PL + i * gw + gw / 2 + 1, y: y(b[i]), h: y(0) - y(b[i]), v: b[i] } })), bw, base: y(0) };
});
</script>

<template>
  <div class="dpanel">
    <template v-if="panel === 'opi'">
      <div class="dleg"><span v-for="c in COS" :key="c"><i :data-i="COS.indexOf(c)" />{{ c }}</span><small>억원</small></div>
      <svg :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="3사 영업이익 추이">
        <g class="ax"><template v-for="t in opi.ticks" :key="t.v"><line :x1="PL" :x2="W - PR" :y1="t.y" :y2="t.y" /><text :x="PL - 6" :y="t.y + 4" text-anchor="end">{{ com(t.v) }}</text></template>
          <text v-for="(yr, i) in Y" :key="yr" :x="x(i)" :y="H - 6" text-anchor="middle">{{ yr }}</text></g>
        <g v-for="l in opi.lines" :key="l.c" class="ser" :data-i="l.k"><polyline :points="l.pts" /><text :x="W - PR + 6" :y="l.end + 4">{{ com(l.v) }}</text></g>
      </svg>
    </template>
    <template v-else-if="panel === 'capex'">
      <div class="dleg"><span><i data-i="0" />CapEx</span><span><i data-i="1" />유형자산 감가상각비</span><small>억원</small></div>
      <svg :viewBox="`0 0 ${W} ${H}`" role="img" aria-label="SKT CapEx와 감가상각비">
        <g class="ax"><template v-for="t in cap.ticks" :key="t.v"><line :x1="PL" :x2="W - 20" :y1="t.y" :y2="t.y" /><text :x="PL - 6" :y="t.y + 4" text-anchor="end">{{ com(t.v) }}</text></template>
          <text v-for="b in cap.bars" :key="b.yr" :x="b.cx" :y="H - 6" text-anchor="middle">{{ b.yr }}</text></g>
        <g v-for="b in cap.bars" :key="'b' + b.yr" class="cbar"><rect :x="b.a.x" :y="b.a.y" :width="cap.bw" :height="b.a.h" rx="2" data-i="0"><title>{{ b.yr }} CapEx {{ com(b.a.v) }}</title></rect>
          <rect :x="b.b.x" :y="b.b.y" :width="cap.bw" :height="b.b.h" rx="2" data-i="1"><title>{{ b.yr }} 감가상각비 {{ com(b.b.v) }}</title></rect></g>
      </svg>
    </template>
    <table v-else class="g dtab">
      <thead><tr><th></th><th class="n">매출</th><th class="n">영업이익</th><th class="n">영업이익률</th><th class="n">부채비율</th><th class="n">CapEx</th></tr></thead>
      <tbody><tr v-for="c in COS" :key="c"><td>{{ c }}</td><td class="n">{{ com(last(DASH.revenue[c])) }}</td><td class="n">{{ com(last(DASH.op_income[c])) }}</td>
        <td class="n">{{ opMargin(c).toFixed(1) }}%</td><td class="n">{{ debtRatio(c).toFixed(1) }}%</td><td class="n">{{ com(last(DASH.capex[c])) }}</td></tr></tbody>
    </table>
  </div>
</template>
