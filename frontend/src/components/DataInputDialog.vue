<script setup lang="ts">
// 데이터 입력 창 (검색창 왼쪽 +): 사내 자료 파일을 골라 검증한 뒤 캔버스에 올림
// 검증(MVP): 포맷 불일치 / 회사 불일치(SKT · SKB · KT · LGU+). 통과한 파일만 올릴 수 있음
// 실제 서비스는 업로드 API → 파서 모델(본문 · 표 요약) → 임베딩 적재. 데모는 텍스트 파일만 읽어 앞부분을 요약으로
import { ref, computed } from "vue";
import { api, docTitle, KIND_LABEL, type Doc } from "../api";
import { checkUpload, FORMATS, TEXT_FORMATS, type UploadCode } from "../local/validate";
import type { Pick } from "./SourcePanel.vue";

const emit = defineEmits<{ close: []; add: [items: Pick[]] }>();
const file = ref<HTMLInputElement>(), over = ref(false), busy = ref(false);
const ext = (n: string) => (n.includes(".") ? n.split(".").pop()!.toUpperCase() : "?");
interface Item { name: string; size: number; code: UploadCode; msg: string; doc?: Doc }
const items = ref<Item[]>([]);
const ok = computed(() => items.value.filter((i) => !i.code && i.doc));
const bad = computed(() => items.value.filter((i) => i.code).length);
const CODE: Record<string, string> = { FORMAT_MISMATCH: "포맷 불일치", COMPANY_MISMATCH: "회사 불일치" };
const KEYWORDS: Record<string, string[]> = { capex: ["capex", "설비투자", "설비"], ai_dc: ["데이터센터", "aidc", "gpu"], da: ["감가상각"],
  comm: ["수수료", "마케팅"], rev: ["매출", "영업수익"], opex: ["영업비용", "비용"], opi: ["영업이익"], dividend: ["배당"], labor: ["인건비"], debt: ["차입"],
  rp_purch: ["특수관계자", "내부거래", "계열사"] };

async function read(fs: FileList | File[]) {
  busy.value = true;
  for (const [i, f] of Array.from(fs).entries()) {
    let text: string | null = null;
    if (TEXT_FORMATS.test(f.name)) { try { text = (await f.text()).replace(/^﻿/, ""); if (text.includes("�")) text = ""; } catch { text = ""; } }
    const r = checkUpload(f.name, text);
    const it: Item = { name: f.name, size: f.size, code: r.code, msg: r.msg };
    if (!r.code) {
      const flat = (text ?? "").replace(/\s+/g, " ").trim();
      const figures = [...flat.matchAll(/([가-힣A-Za-z]{2,12})\s*[:=]?\s*(-?\d[\d,]*(?:\.\d+)?)\s*(십억원|억원|백만원|조원|원|%)/g)].slice(0, 4)
        .map((m) => ({ label: m[1], value: +m[2].replace(/,/g, ""), unit: m[3], period: "업로드 자료" }));
      const hay = (f.name + flat).toLowerCase().replace(/\s+/g, "");
      it.doc = { id: `up${Date.now()}${i}`, kind: "internal", company: r.company!, title: f.name.replace(/\.[^.]+$/, ""),
        publisher: "업로드 (파서 요약)", date: new Date().toISOString().slice(0, 10), url: "", uploaded: true, figures,
        tags: Object.entries(KEYWORDS).filter(([, ks]) => ks.some((k) => hay.includes(k))).map(([id]) => id),
        summary: flat ? flat.slice(0, 180) + (flat.length > 180 ? "…" : "")
          : `${f.name.split(".").pop()!.toUpperCase()} ${Math.ceil(f.size / 1024)}KB. 파서 모델을 연결하면 본문 요약과 표 수치가 이 자리에 들어감` };
    }
    items.value = [...items.value.filter((x) => x.name !== f.name), it];
  }
  busy.value = false;
}
function pick(e: Event) { const el = e.target as HTMLInputElement; if (el.files) read(el.files); el.value = ""; }
function drop(e: DragEvent) { over.value = false; if (e.dataTransfer?.files.length) read(e.dataTransfer.files); }
function add() {
  emit("add", ok.value.map((i) => { const d = api.registerDoc(i.doc!);
    return { key: "doc:" + d.id, label: docTitle(d), tag: KIND_LABEL[d.kind], src: d.kind, kind: "doc" as const, id: d.id }; }));
}
</script>

<template>
  <!-- v01 자료 첨부 팝업 모양: 화면 가운데 창, 점선 칸 안에 파일 줄, 문제 있는 파일은 노란 경고와 빼기 -->
  <div class="mdim" @click.self="emit('close')">
    <section class="modal sm dinput" role="dialog" aria-modal="true" aria-labelledby="dit">
      <div class="mh"><h3 id="dit">사내 자료 입력</h3><button class="btn grey sm" @click="emit('close')">닫기</button></div>
      <div v-if="bad" class="warnbar" role="status"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3l8 14H2z" /><path d="M10 8v4M10 14.5v.1" /></svg>올릴 수 없는 자료가 {{ bad }}개 있어요</div>
      <div class="drop"><h4>사내 자료 <small>SKT · SKB · KT · LGU+ 자료, 여러 개</small></h4>
        <div class="zone" :class="{ over }" @dragover.prevent="over = true" @dragleave="over = false" @drop.prevent="drop">
          <div v-for="i in items" :key="i.name" class="frow" :class="{ w: i.code }">
            <span class="k">{{ ext(i.name) }}</span>
            <div class="grow"><b>{{ i.name }}</b><small>{{ i.code ? `${Math.ceil(i.size / 1024)}KB` : `${i.doc!.company} · 사내 자료 · ${Math.ceil(i.size / 1024)}KB` }}</small></div>
            <button v-if="!i.code" class="icon-btn" :aria-label="i.name + ' 빼기'" @click="items = items.filter((x) => x !== i)">✕</button>
            <div v-if="i.code" class="wmsg" role="alert"><b>{{ CODE[i.code] }}</b><span>{{ i.msg }}</span>
              <button class="btn grey sm" @click="items = items.filter((x) => x !== i)">빼기</button></div>
          </div>
          <button class="dz" @click="file?.click()">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 13V3M6 7l4-4 4 4M4 13v3h12v-3" /></svg>
            {{ busy ? "확인하는 중…" : "클릭하거나 파일을 끌어 놓으세요" }}<small>{{ FORMATS.map((f) => f.toUpperCase()).join(", ") }}</small></button>
        </div>
      </div>
      <input ref="file" class="hide" type="file" multiple @change="pick" />
      <div class="mf"><span v-if="bad" class="hint">올릴 수 없는 자료를 빼 주세요</span>
        <button class="btn grey" @click="emit('close')">취소</button><button class="btn fill" :disabled="!ok.length || !!bad" @click="add">캔버스에 올리기{{ ok.length ? ` ${ok.length}` : "" }}</button></div>
    </section>
  </div>
</template>
