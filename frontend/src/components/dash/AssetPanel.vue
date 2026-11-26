<script setup lang="ts">
// 자산 계정 (v01-stella DART 대시보드 › 자산 계정 탭). SKT 별도 분기 장부금액을 표 · 자산 맵(트리맵) · 히트맵으로 보고,
// 칸을 누르면 오른쪽 근거 패널(분기 전후 외부 자료 · 이후 공시). 근거 칸 상태(AEV)는 화면 설명용 예시
// compact: 캔버스 카드 · 자료 검색 목록에서 근거 패널 없이 그림만
import { ref, computed } from "vue";
import { AQ, AAC, AEV, AREG, CIP_EVIDENCE as CIP } from "../../local/dart";
import { com } from "../../local/dashboard";

const props = defineProps<{ compact?: boolean }>();
const view = ref<"tbl" | "map" | "heat">("heat");
const layer = ref<"off" | "str" | "unx">("str");
const q = ref(2), noRou = ref(false), cell = ref<string | null>(null);
const LV = ["없음", "약", "중", "강"];
const rows = computed(() => AAC.filter((a) => !(noRou.value && a[1] === "rou")));
const delta = (k: string, i: number) => { const a = AAC.find((x) => x[1] === k)![3]; return i ? a[i] - a[i - 1] : null; };
const sd = (v: number | null) => v === null ? "—" : (v > 0 ? "+" : v < 0 ? "−" : "") + com(Math.abs(v));
function cls(k: string, i: number) { const e = AEV[`${k}|${i}`]; if (!e || layer.value === "off") return ""; if (layer.value === "unx") return e.unx ? "unx" : ""; return e.unx ? "unx" : e.lv ? "lv" + e.lv : ""; }
function badge(k: string, i: number) { const e = AEV[`${k}|${i}`]; if (!e || layer.value === "off") return null; if (e.unx) return { c: "q", t: "?" };
  if (layer.value === "unx") return null; if (e.later) return { c: "lt", t: "1" }; return e.n ? { c: "", t: String(e.n) } : null; }
const pick = (k: string, i: number) => { if (props.compact) return; cell.value = cell.value === `${k}|${i}` ? null : `${k}|${i}`; };
const grp = (g: "t" | "i") => rows.value.filter((a) => a[0] === g);

// 히트맵: 전분기 대비 증감률. 진하기는 ±15% 이상에서 최대
const pc = (k: string, i: number) => { const a = AAC.find((x) => x[1] === k)![3]; return (a[i] / a[i - 1] - 1) * 100; };
function heat(k: string, i: number) { const p = pc(k, i), t = Math.min(Math.abs(p) / 15, 1), mx = Math.round(6 + Math.pow(t, 0.8) * 74);
  return { p, bg: `color-mix(in oklab, ${p >= 0 ? "#5B47D6" : "#4F6684"} ${mx}%, var(--hbase))`, dark: t > 0.6 }; }
const pctTxt = (p: number) => (p >= 0 ? "+" : "−") + Math.abs(p).toFixed(1) + "%";

// 자산 맵: squarified treemap. 작은 무형자산은 "기타 무형 N종"으로 묶음
interface It { g: string; k: string; n: string; v: number; d: number | null; etc?: boolean; sub?: string }
function sqf(items: It[], x: number, y: number, w: number, h: number) {
  const out: (It & { x: number; y: number; w: number; h: number })[] = []; let rest = items.slice();
  const worst = (row: It[], sh: number, tot: number, A: number) => { const sm = row.reduce((a, i) => a + i.v, 0) * A / tot; let m = 0;
    for (const i of row) { const a = i.v * A / tot; m = Math.max(m, sh * sh * a / (sm * sm), (sm * sm) / (sh * sh * a)); } return m; };
  while (rest.length) {
    const sh = Math.min(w, h), tot = rest.reduce((a, i) => a + i.v, 0), A = w * h; let row = [rest[0]], b = worst(row, sh, tot, A), i = 1;
    while (i < rest.length) { const r2 = row.concat(rest[i]), w2 = worst(r2, sh, tot, A); if (w2 <= b) { row = r2; b = w2; i++; } else break; }
    const rs = row.reduce((a, it) => a + it.v, 0), ar = A * rs / tot;
    if (w >= h) { const cw = ar / h; let cy = y; row.forEach((it) => { const ch = h * it.v / rs; out.push({ ...it, x, y: cy, w: cw, h: ch }); cy += ch; }); x += cw; w -= cw; }
    else { const ch = ar / w; let cx = x; row.forEach((it) => { const cw = w * it.v / rs; out.push({ ...it, x: cx, y, w: cw, h: ch }); cx += cw; }); y += ch; h -= ch; }
    rest = rest.slice(row.length);
  }
  return out;
}
const MW = 760, MH = computed(() => props.compact ? 300 : 440);
const map = computed(() => {
  const all: It[] = rows.value.map((a) => ({ g: a[0], k: a[1], n: a[2], v: a[3][q.value], d: delta(a[1], q.value) }));
  const isum = all.filter((i) => i.g === "i").reduce((a, i) => a + i.v, 0), tsum = all.filter((i) => i.g === "t").reduce((a, i) => a + i.v, 0);
  const small = all.filter((i) => i.g === "i" && i.v < isum * 0.06); const items = all.filter((i) => !small.includes(i));
  if (small.length) items.push({ g: "i", k: "_etc", n: `기타 무형 ${small.length}종`, v: small.reduce((a, i) => a + i.v, 0), d: null, etc: true, sub: small.map((i) => i.n).join(", ") });
  items.sort((a, b) => b.v - a.v);
  return { tsum, isum, blocks: sqf(items, 0, 0, MW, MH.value).map((b) => ({ ...b, size: b.w >= 130 && b.h >= 78 ? "l" : b.w >= 86 && b.h >= 46 ? "m" : b.w >= 46 && b.h >= 24 ? "s" : "x" })) };
});
const qInfo = (i: number) => { if (!i) return "직전 분기 없음"; const pts = AAC.filter((a) => AEV[`${a[1]}|${i}`]).length, ux = AAC.filter((a) => AEV[`${a[1]}|${i}`]?.unx).length;
  return `포인트 ${pts}${ux ? ` · 미설명 ${ux}` : ""}`; };

// 근거 패널
const points = computed(() => AAC.filter((a) => AEV[`${a[1]}|${q.value}`]).map((a) => ({ k: a[1], n: a[2], d: delta(a[1], q.value), e: AEV[`${a[1]}|${q.value}`] }))
  .sort((a, b) => Math.abs(b.d ?? 0) - Math.abs(a.d ?? 0)));
const regular = computed(() => q.value ? AAC.filter((a) => AREG[a[1]] && !AEV[`${a[1]}|${q.value}`]).map((a) => ({ n: a[2], r: AREG[a[1]], d: delta(a[1], q.value) })) : []);
const sel = computed(() => { if (!cell.value) return null; const [k, qs] = cell.value.split("|"), i = +qs, a = AAC.find((x) => x[1] === k)!;
  return { k, i, a, e: AEV[cell.value] ?? {}, bal: a[3][i], d: delta(k, i) }; });
</script>

<template>
  <div class="asw" :class="{ compact }">
    <div class="apane">
      <div class="actl nodrag">
        <div class="seg" role="tablist" aria-label="보기">
          <button v-for="[v, l] in ([['heat', '히트맵'], ['map', '자산 맵'], ['tbl', '표']] as const)" :key="v" role="tab" :aria-selected="view === v" @click.stop="view = v">{{ l }}</button></div>
        <template v-if="!compact">
          <span class="lb">근거 레이어</span>
          <div class="seg" role="radiogroup" aria-label="근거 레이어">
            <button v-for="[v, l] in ([['off', '끄기'], ['str', '근거 강도'], ['unx', '미설명만']] as const)" :key="v" :aria-pressed="layer === v" @click.stop="layer = v">{{ l }}</button></div>
          <label @click.stop><input v-model="noRou" type="checkbox">사용권자산 제외</label>
        </template>
      </div>
      <div class="acap">SK텔레콤 별도 · 단위 억원 · {{ view === 'tbl' ? '칸 아래 숫자 = 전분기 대비 증감' : view === 'heat' ? '칸 = 전분기 대비 증감률' + (compact ? '' : ' · 칸을 누르면 근거 패널') : `${AQ[q]} 장부금액 · 면적 = 장부금액, 색과 핀 = 근거` }}</div>

      <div v-if="view === 'tbl'" class="tbl"><table class="atbl">
        <thead><tr><th>계정</th><th v-for="l in AQ" :key="l">{{ l }}</th></tr></thead>
        <tbody v-for="[g, gl] in ([['t', '유형자산'], ['i', '무형자산']] as const)" :key="g">
          <tr class="ag"><td>{{ gl }}</td><td v-for="l in AQ" :key="l" /></tr>
          <tr v-for="[, k, n, v, note] in grp(g)" :key="k"><td class="nm">{{ n }}<small v-if="note">{{ note }}</small></td>
            <td v-for="(x, i) in v" :key="i"><button class="acell" :class="[cls(k, i), { sel: cell === `${k}|${i}` }]" @click.stop="pick(k, i)">
              <span v-if="badge(k, i)" class="bd" :class="badge(k, i)!.c">{{ badge(k, i)!.t }}</span>{{ com(x) }}<small>{{ sd(delta(k, i)) }}</small></button></td></tr>
        </tbody></table></div>

      <template v-else-if="view === 'heat'">
        <div class="tbl"><table class="htbl">
          <thead><tr><th>계정</th><th v-for="i in [1, 2, 3, 4]" :key="i">{{ AQ[i] }}</th></tr></thead>
          <tbody v-for="[g, gl] in ([['t', '유형자산'], ['i', '무형자산']] as const)" :key="g">
            <tr class="hg"><td colspan="5">{{ gl }}</td></tr>
            <tr v-for="[, k, n] in grp(g)" :key="k"><td class="hn">{{ n }}</td>
              <td v-for="i in [1, 2, 3, 4]" :key="i"><button class="hcell" :class="{ sel: cell === `${k}|${i}`, unx: AEV[`${k}|${i}`]?.unx }"
                :style="{ background: heat(k, i).bg, color: heat(k, i).dark ? '#fff' : 'var(--t-strong)' }" :title="`${n} ${AQ[i]} · ${sd(delta(k, i))}억 (${pctTxt(heat(k, i).p)})`" @click.stop="pick(k, i)">
                {{ pctTxt(heat(k, i).p) }}<i v-if="AEV[`${k}|${i}`]?.n">{{ AEV[`${k}|${i}`]!.n }}</i><i v-if="AEV[`${k}|${i}`]?.unx" class="q">?</i></button></td></tr>
          </tbody></table></div>
        <div class="hleg"><span>감소</span><i /><span>증가</span><small>색 진하기 = 전분기 대비 증감률 (±15% 이상은 가장 진하게) · 숫자 = 근거 수</small></div>
      </template>

      <template v-else>
        <div class="tmhead"><span><i class="sw" />유형자산 <b>{{ com(map.tsum) }}억</b></span><span><i class="sw i" />무형자산 <b>{{ com(map.isum) }}억</b></span></div>
        <div class="tmap" :style="{ height: MH + 'px' }">
          <button v-for="b in map.blocks" :key="b.k" class="tblk" :class="[b.size, b.etc ? '' : cls(b.k, q), { ig: b.g === 'i', sel: cell === `${b.k}|${q}` }]"
            :title="`${b.n}${b.sub ? ` (${b.sub})` : ''} · ${com(b.v)}억${b.d !== null ? ` · 전분기 ${sd(b.d)}` : ''}`"
            :style="{ left: `calc(${b.x / MW * 100}% + 2px)`, top: b.y + 2 + 'px', width: `calc(${b.w / MW * 100}% - 4px)`, height: b.h - 4 + 'px' }" @click.stop="!b.etc && pick(b.k, q)">
            <span v-if="!b.etc && badge(b.k, q)" class="bd">{{ badge(b.k, q)!.t }}</span>
            <template v-if="b.size !== 'x'"><b>{{ b.n }}</b><span v-if="b.size !== 's'" class="v">{{ com(b.v) }}억</span>
              <small v-if="b.size === 'l'">전분기 {{ sd(b.d) }}{{ b.g === 'i' ? ' · 무형' : '' }}</small></template></button>
        </div>
        <div class="qscrub nodrag" role="radiogroup" aria-label="분기">
          <button v-for="(l, i) in AQ" :key="l" :aria-pressed="q === i" @click.stop="q = i">{{ l }}<small>{{ qInfo(i) }}</small></button></div>
      </template>
      <div v-if="!compact" class="alegend"><b>근거 강도</b><span><i style="background:var(--hbase)" />없음</span><span><i style="background:var(--ev1)" />약</span>
        <span><i style="background:var(--ev2)" />중</span><span><i style="background:var(--ev3)" />강</span><span>● 근거 수</span><span><i class="unxsw" />설명 안 된 변동</span><span>① 이후 공시에서만 확인</span></div>
    </div>

    <!-- 근거 패널: 칸을 고르지 않으면 이번 분기 포인트, 고르면 그 칸의 근거 -->
    <aside v-if="!compact" class="apane evp nodrag" @click.stop>
      <template v-if="!sel">
        <div class="ph"><div><h3>이번 분기 포인트</h3><small>SK텔레콤 별도 · {{ AQ[q] }} · 변동 큰 순</small></div></div>
        <div class="qsel seg"><button v-for="(l, i) in AQ.slice(1)" :key="l" :aria-pressed="q === i + 1" @click="q = i + 1">{{ l.slice(2) }}</button></div>
        <div v-if="points.length" class="pts">
          <div v-for="p in points" :key="p.k" class="pt"><div class="t">{{ p.n }}<span class="d">{{ sd(p.d) }}억</span></div>
            <span>{{ p.e.unx ? "설명 안 된 변동 · 근거를 찾지 못했어요" : p.e.later ? "이후 공시에서만 확인돼요" : `근거 ${LV[p.e.lv ?? 0]} · ${p.e.n}건` }}</span>
            <button @click="cell = `${p.k}|${q}`">근거 패널 열기 →</button></div></div>
        <p v-else class="anote">이 분기에는 짚을 만한 변동이 없어요.</p>
        <div v-if="regular.length"><div class="evh">표시 안 함 · 규칙적 변동</div>
          <div class="reg"><div v-for="r in regular" :key="r.n"><span>{{ r.n }}<small>{{ r.r }}</small></span><span>{{ sd(r.d) }}억</span></div></div></div>
      </template>
      <template v-else>
        <div class="ph"><div><h3>근거</h3><small>SK텔레콤 · 별도 · {{ AQ[sel.i] }}</small><div class="acct">{{ sel.a[0] === "t" ? "유형자산" : "무형자산" }} › {{ sel.a[2] }}</div></div>
          <button class="x" aria-label="근거 닫기" @click="cell = null">✕</button></div>
        <div class="kv3"><div><span>잔액</span><b>{{ com(sel.bal) }}억</b></div><div><span>전분기 대비</span><b>{{ sd(sel.d) }}{{ sel.d === null ? "" : "억" }}</b></div>
          <div v-if="cell === 'cip|2'"><span>분기 취득</span><b>{{ com(CIP.acq) }}억</b></div><div v-else><span>근거 강도</span><b>{{ LV[sel.e.lv ?? 0] }}</b></div>
          <div v-if="cell === 'cip|2'" class="f">{{ CIP.formula }}<br>전년 같은 분기 취득 {{ com(CIP.prevAcq) }} → {{ (CIP.acq / CIP.prevAcq).toFixed(1) }}배</div></div>
        <template v-if="cell === 'cip|2'">
          <div class="strength"><b>근거 강도</b><i class="on" /><i class="on" /><i class="on" />강 · 근거 3건</div>
          <p class="anote">분기 앞뒤 외부 자료 2건과 이후 공시 1건이 이 증가를 설명해요.</p>
          <template v-for="later in [false, true]" :key="String(later)">
            <div class="evh">{{ later ? "이후 공시에서 확인 · 1" : "분기 전후 외부 자료 · 2" }}</div>
            <div v-for="x in CIP.items.filter((y) => y.later === later)" :key="x.t" class="evc">
              <div class="top"><span class="k" :class="{ d: later }">{{ x.k }}</span><span>{{ x.d }}</span><span class="r">{{ x.r }}</span></div>
              <b>{{ x.t }}</b><p>{{ x.p }}</p><div class="chips"><span v-for="c in x.chips" :key="c">{{ c }}</span></div></div>
          </template>
        </template>
        <p v-else class="anote">{{ sel.e.unx ? "이 변동을 설명할 자료를 아직 찾지 못했어요. 검토 자료에 이유가 적혀 있는지 확인해 보세요." : sel.e.later ? "분기 당시 자료에는 없고, 이후 공시에서만 확인되는 변동이에요." : sel.e.n ? `근거 ${sel.e.n}건이 붙은 칸이에요.` : "근거가 붙지 않은 칸이에요." }}</p>
      </template>
      <p class="evfoot">외부 자료: 뉴스 · 애널리스트 리포트 · IR · DART 공시. 근거 칸 상태는 화면 설명용 예시예요.</p>
    </aside>
  </div>
</template>
