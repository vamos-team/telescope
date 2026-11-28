<script setup lang="ts">
// 페이지 정보 (헤더 오른쪽 i 아이콘): 내 페이지 정보 + 바로 협업자 편집(초대 · 권한). 일반 액세스(회사 전체)는 없음
// 헤더 아래 오른쪽에 뜨는 팝오버. 닫기는 ✕ · 바깥 클릭 · Esc(App). 공유 설정은 소유자만 바꾸고, 편집·보기 권한이면 읽기만. 홈으로 가는 건 로고
import { computed, onMounted, ref } from "vue";
import { useCanvas, ALL_MEMBERS, shareOf, ROLE, when, type Role } from "../stores/canvas";
import { SYNC_MODE } from "../sync/config";
import { usePeopleSearch } from "./usePeopleSearch";

const emit = defineEmits<{ close: [] }>();
const cv = useCanvas();
const server = SYNC_MODE === "server";
// server 모드는 열 때 공유 명단을 서버에서 읽는다(GET /shares). 권한자 모두 볼 수 있다
onMounted(() => { if (server) cv.loadShares(); });
const can = computed(() => cv.role === "owner");
/** 소유자 ID와 초대한 사람별 권한. server 모드는 서버 명단, local 모드는 저장된 공유 정보 */
const s = computed(() => {
  if (!server) return shareOf(cv.meta!);
  const sh = cv.serverShares;
  return { ownerId: sh?.ownerId ?? 0, share: Object.fromEntries((sh?.members ?? []).map((m) => [String(m.userId), m.role])) as Record<string, "edit" | "view"> };
});
const q = ref(""), role = ref<"edit" | "view">("edit"), focused = ref(false);
const has = computed(() => new Set([s.value.ownerId, ...Object.keys(s.value.share).map(Number)]));
const { found, hint } = usePeopleSearch(q, () => has.value);  // local은 빈 칸이면 추천, server는 입력해야 찾음
// 후보를 누르기 전에 목록이 닫히지 않게 포커스가 창 안 다른 곳으로 갈 때만 닫음
function blur(e: FocusEvent) { if (!(e.relatedTarget as HTMLElement | null)?.closest(".found")) focused.value = false; }
/** 권한 있는 사람 목록: 소유자와 직접 초대한 사람. 각 사람에 권한(owner / edit / view)을 붙인다. 이름·부서는 server 모드면 서버 명단의 값 */
const people = computed((): { id: number; name: string; detail: string; role: Role }[] => {
  const sh = cv.serverShares;
  if (server) {
    if (!sh) return [];
    const name = (n: string | null | undefined, id: number) => n ?? `사용자 ${id}`;
    return [{ id: sh.ownerId, name: name(sh.owner.name, sh.ownerId), detail: sh.owner.dept ?? "", role: "owner" as const },
      ...sh.members.map((m) => ({ id: m.userId, name: name(m.name, m.userId), detail: m.dept ?? "", role: m.role }))];
  }
  return [...has.value].flatMap((id) => {
    const m = ALL_MEMBERS.find((x) => x.id === id); if (!m) return [];
    return [{ id, name: m.name, detail: `${m.dept} · ${m.job}`, role: id === s.value.ownerId ? "owner" as const : s.value.share[String(id)] }];
  });
});
/** 찾은 사람을 고른 권한으로 초대하고 찾기 칸을 비운다. */
function invite(id: number) { cv.setShare({ share: { ...s.value.share, [String(id)]: role.value } }); q.value = ""; focused.value = false; }
/** 초대한 사람의 권한을 바꾸거나("edit" / "view") 권한을 지운다("remove"). */
function setRole(id: number, v: string) {
  const share = { ...s.value.share };
  if (v === "remove") delete share[String(id)]; else share[String(id)] = v as "edit" | "view";
  cv.setShare({ share });
}
</script>

<template>
  <div class="pinfo-bg" @click.self="emit('close')">
    <section class="share pinfo" role="dialog" aria-label="페이지 정보">
      <header><h2>페이지 정보</h2><button class="x" aria-label="닫기" @click="emit('close')">✕</button></header>
      <template v-if="cv.meta && cv.user">
        <dl class="pmeta">
          <dt>이름</dt><dd>{{ cv.canvasName }}</dd>
          <dt>기준 시점</dt><dd>{{ cv.pageQuarter }}</dd>
          <dt>내 권한</dt><dd>{{ cv.role ? ROLE[cv.role] : '확인할 수 없음' }}</dd>
          <dt>수정</dt><dd>{{ cv.meta.updatedBy }} · {{ when(cv.meta.updated) }} · 카드 {{ cv.cards.length }}</dd>
        </dl>
        <h3>협업자</h3>

        <div v-if="can" class="inv">
          <input v-model="q" placeholder="사번, 이름, 부서로 사내 사람 찾기" aria-label="초대할 사람 찾기" @keydown.enter.prevent="found[0] && invite(found[0].id)" @focus="focused = true" @click="focused = true" @input="focused = true" @blur="blur" />
          <select v-model="role" aria-label="초대 권한"><option value="edit">편집 가능</option><option value="view">보기 가능</option></select>
          <ul v-if="focused" class="found">
            <li v-if="!q.trim() && found.length" class="cap">추천</li>
            <li v-for="m in found" :key="m.id"><button @mousedown.prevent @click="invite(m.id)">
              <span class="av">{{ m.name[0] }}</span><b>{{ m.name }}</b><small>{{ [m.employeeNo, m.dept, m.job].filter(Boolean).join(' · ') }}</small></button></li>
            <li v-if="hint" class="none">{{ hint }}</li>
            <li v-else-if="!found.length" class="none">찾는 사람이 없거나 이미 권한이 있습니다.</li>
          </ul>
        </div>
        <p v-else class="note">공유 설정은 소유자만 바꿀 수 있습니다.</p>

        <h3>권한 있는 사람</h3>
        <p v-if="server && !cv.serverShares" class="note">명단을 불러오는 중입니다.</p>
        <ul class="ppl">
          <li v-for="m in people" :key="m.id">
            <span class="av" :data-me="m.id === cv.user!.id">{{ m.name[0] }}</span>
            <span class="nm"><b>{{ m.name }}<template v-if="m.id === cv.user!.id"> (나)</template></b><small>{{ m.detail }}</small></span>
            <span v-if="m.role === 'owner' || !can" class="rl">{{ ROLE[m.role] }}</span>
            <select v-else :value="m.role" :aria-label="m.name + ' 권한'" @change="setRole(m.id, ($event.target as HTMLSelectElement).value)">
              <option value="edit">편집 가능</option><option value="view">보기 가능</option><option value="remove">권한 삭제</option></select>
          </li>
        </ul>
      </template>
      <p v-else class="note">페이지 정보를 확인할 수 없습니다.</p>
    </section>
  </div>
</template>
