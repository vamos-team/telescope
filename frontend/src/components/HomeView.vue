<script setup lang="ts">
// 페이지 홈(C00): 내가 볼 수 있는 페이지 전부. 페이지 = 캔버스 + 분석 (Figma 파일 목록처럼). 로그인하면 여기로 옴
import { computed, ref } from "vue";
import { useCanvas, memberOf, roleOf, when, ROLE, type CanvasMeta } from "../stores/canvas";
import Logo from "./Logo.vue";

const cv = useCanvas();
const MENU = { recent: "최근", mine: "내가 만든 페이지", shared: "공유받은 페이지" } as const;
const menu = ref<keyof typeof MENU>("recent");
const q = ref("");
const list = computed(() => cv.canvases.filter((m) => {
  const r = roleOf(m, cv.user);
  if (menu.value === "mine" && r !== "owner") return false;
  if (menu.value === "shared" && r === "owner") return false;
  return !q.value.trim() || m.name.toLowerCase().includes(q.value.trim().toLowerCase());
}));
const count = (k: keyof typeof MENU) => cv.canvases.filter((m) => k === "recent" || (roleOf(m, cv.user) === "owner") === (k === "mine")).length;

// 미리보기: 저장된 카드 좌표를 작은 사각형으로 (카드 크기는 대략값)
const W = 560, H = 360;
function thumb(id: string) {
  const cs = cv.thumb(id); if (!cs.length) return null;
  const x0 = Math.min(...cs.map((c) => c.x)), y0 = Math.min(...cs.map((c) => c.y));
  const x1 = Math.max(...cs.map((c) => c.x + W)), y1 = Math.max(...cs.map((c) => c.y + H));
  return { box: `${x0 - 80} ${y0 - 80} ${x1 - x0 + 160} ${y1 - y0 + 160}`, cs };
}
function del(m: CanvasMeta) { if (confirm(`'${m.name}' 페이지를 지울까요? 공유받은 사람 모두에게서 지워집니다.`)) cv.removeCanvas(m.id); }
</script>

<template>
  <!-- 헤더: 페이지 안 헤더와 같은 높이 · 같은 로고 자리 -->
  <div class="bar">
    <h1 class="logo"><button class="home" title="홈" aria-label="홈"><Logo /></button></h1>
    <b class="ctitle">Telescope</b>
  </div>
  <div class="home">
    <nav class="hnav" aria-label="페이지 홈 메뉴">
      <button v-for="(t, k) in MENU" :key="k" :aria-current="menu === k ? 'page' : undefined" @click="menu = k">{{ t }}<small>{{ count(k) }}</small></button>
      <div class="who"><span class="av" data-me="true">{{ cv.user!.name[0] }}</span>
        <div><b>{{ cv.user!.name }}</b><small>{{ cv.user!.dept }} · {{ memberOf(cv.user!.name)?.job }}</small></div>
        <button class="lo" @click="cv.logout()">로그아웃</button></div>
    </nav>
    <main class="hmain">
      <header>
        <h1>{{ MENU[menu] }}</h1>
        <input v-model="q" type="search" placeholder="페이지 이름 검색" aria-label="페이지 이름 검색" />
        <button class="primary" @click="cv.creating = true">+ 새 페이지</button>
      </header>
      <p v-if="!list.length" class="none">{{ q ? "검색어와 맞는 페이지가 없습니다." : "볼 수 있는 페이지가 없습니다." }}</p>
      <ul class="tiles">
        <li v-for="m in list" :key="m.id">
          <button class="tile" @click="cv.open(m.id)">
            <span class="th">
              <svg v-if="thumb(m.id)" :viewBox="thumb(m.id)!.box" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
                <rect v-for="(c, i) in thumb(m.id)!.cs" :key="i" :x="c.x" :y="c.y" :width="W" :height="H" rx="24" :data-kind="c.kind" />
              </svg>
              <span v-else>빈 페이지</span>
            </span>
            <b>{{ m.name }}</b>
            <small>{{ m.updatedBy }} · {{ when(m.updated) }} 수정 · 카드 {{ m.count }}</small>
            <small>{{ ROLE[roleOf(m, cv.user)!] }}</small>
          </button>
          <button v-if="roleOf(m, cv.user) === 'owner'" class="x" :aria-label="m.name + ' 지우기'" title="지우기" @click="del(m)">✕</button>
        </li>
      </ul>
    </main>
  </div>
</template>
