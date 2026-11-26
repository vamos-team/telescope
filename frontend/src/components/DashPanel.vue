<script setup lang="ts">
// 대시보드 패널 본문 하나 (자료 검색 화면과 캔버스 대시보드 카드가 함께 씀). 패널 정의는 local/dashboard.ts
// compact: 캔버스 카드 안 (자산 계정은 근거 패널 없이)
import { computed } from "vue";
import { panelOf, com, type Series } from "../local/dashboard";
import { api } from "../api";
import SvgChart from "./dash/SvgChart.vue";
import AssetPanel from "./dash/AssetPanel.vue";

const props = defineProps<{ panel: string; compact?: boolean }>();
const p = computed(() => panelOf(props.panel));
const c = computed(() => p.value?.chart());
const legend = computed(() => { const x = c.value; if (!x) return [];
  if (x.type === "bars" || x.type === "lines" || x.type === "stack") return x.series.length > 1 ? x.series.map((s: Series) => [s.name, s.c]) : [];
  if (x.type === "waterfall") return p.value!.id === "cash_bridge" ? [["기초·기말 현금", "--s1"], ["유출", "--s2"], ["유입", "--s3"]] : [["영업이익", "--s1"], ["감소 요인", "--s2"], ["증가 요인", "--s3"]];
  if (x.type === "treemap") return x.items.slice(0, 3).map(([n], i) => [n, x.colors[i]]).concat([["그 밖", x.colors[3]]]);
  return []; });
// 히트맵: 전년 대비 증감(비율 행은 %p). 셀 농도 = 변화폭(30% 이상 최대)
const yoy = (v: number[], pctRow?: boolean) => v.map((x, i) => (i ? (pctRow ? x - v[i - 1] : (x / v[i - 1] - 1) * 100) : null));
function spark(v: number[]) { const w = 72, h = 34, mn = Math.min(...v), mx = Math.max(...v);
  const pts = v.map((x, i) => [i * (w - 6) / (v.length - 1) + 3, h - 4 - (x - mn) / ((mx - mn) || 1) * (h - 8)]);
  return { line: pts.map((q) => q.join(",")).join(" "), end: pts[pts.length - 1] }; }
const fmt = (x: number | string) => typeof x === "number" ? com(x) : x;
const news = computed(() => (p.value?.news ?? []).map((id) => api.docs.get(id)).filter((d) => !!d));
</script>

<template>
  <div v-if="p && c" class="dpanel">
    <div v-if="legend.length" class="dleg"><span v-for="[n, col] in legend" :key="n"><i :style="{ background: `var(${col})` }" />{{ n }}</span><small>{{ p.unit }}</small></div>

    <div v-if="c.type === 'kpis'" class="dkpis">
      <div v-for="k in c.items" :key="k.l" class="dkpi"><div class="l">{{ k.l }}
        <svg viewBox="0 0 72 34" aria-hidden="true"><polyline :points="spark(k.s).line" class="sp" /><circle :cx="spark(k.s).end[0]" :cy="spark(k.s).end[1]" r="3" class="spd" /></svg></div>
        <div class="v">{{ k.v }}</div><div class="d" :title="k.d">{{ k.d }}</div><span class="tag">{{ k.tag }}</span></div>
    </div>

    <div v-else-if="c.type === 'heat'" class="tbl"><table class="heat">
      <thead><tr><th /><th v-for="y in c.cols" :key="y">{{ y }}</th></tr></thead>
      <tbody><tr v-for="r in c.rows" :key="r.n"><td>{{ r.n }}</td>
        <td v-for="(x, i) in r.v" :key="i"><span class="cell" :style="{ background: `color-mix(in srgb,var(--s1) ${Math.round(Math.min(Math.abs(yoy(r.v, r.pct)[i] ?? 0) / 30, 1) * 38)}%,transparent)` }">
          {{ r.pct ? x.toFixed(1) + "%" : com(x) }}<small v-if="yoy(r.v, r.pct)[i] === null">&nbsp;</small>
          <small v-else>{{ yoy(r.v, r.pct)[i]! >= 0 ? "▲" : "▼" }} {{ Math.abs(yoy(r.v, r.pct)[i]!).toFixed(1) }}{{ r.pct ? "%p" : "%" }}</small></span></td></tr></tbody>
    </table></div>

    <div v-else-if="c.type === 'table'" class="tbl"><table class="ftbl">
      <thead><tr><th>{{ c.hl }}</th><th v-for="h in c.cols" :key="h" class="n">{{ h }}</th></tr></thead>
      <tbody><tr v-for="r in c.rows" :key="r.n" :class="{ b: r.b }"><td>{{ r.n }}</td><td v-for="(x, i) in r.v" :key="i" class="n">{{ fmt(x) }}</td></tr></tbody>
    </table></div>

    <AssetPanel v-else-if="c.type === 'asset'" :compact="compact" />
    <SvgChart v-else :chart="c" />

    <div v-if="p.cap" class="cap">{{ p.cap() }}</div>
    <div v-if="p.ai" class="aip"><div class="ai-lab">AI 해석</div><p>{{ p.ai }}</p>
      <div class="nrefs"><a v-for="d in news" :key="d!.id" :href="d!.url" target="_blank" rel="noopener" draggable="false">{{ d!.publisher }} {{ d!.date.slice(5) }} · {{ d!.title }}</a></div></div>
  </div>
</template>
