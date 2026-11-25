<script setup lang="ts">
// 공유 창(C13): 사내 사람을 초대하고 권한(편집 가능 / 보기 가능)을 줌. 일반 액세스(회사 전체)는 없음
// 닫기는 ✕ · 완료 · Esc만 (빈 곳 클릭으로는 닫지 않음). 공유 설정은 소유자만 바꾸고, 편집·보기 권한이면 읽기만
import { computed, ref } from "vue";
import { useCanvas, ALL_MEMBERS, shareOf, ROLE, findPeople } from "../stores/canvas";

const emit = defineEmits<{ close: [] }>();
const cv = useCanvas();
const can = computed(() => cv.role === "owner");
const s = computed(() => shareOf(cv.meta!));
const q = ref(""), role = ref<"edit" | "view">("edit"), focused = ref(false);
const has = computed(() => new Set([s.value.ownerId, ...Object.keys(s.value.share)]));
const found = computed(() => findPeople(q.value, has.value, cv.user!.dept));  // 빈 칸이면 추천
// 후보를 누르기 전에 목록이 닫히지 않게 포커스가 창 안 다른 곳으로 갈 때만 닫음
function blur(e: FocusEvent) { if (!(e.relatedTarget as HTMLElement | null)?.closest(".found")) focused.value = false; }
/** 권한 있는 사람 목록: 소유자와 직접 초대한 사람. 각 사람에 권한(owner / edit / view)을 붙인다. */
const people = computed(() => [...has.value].flatMap((id) => {
  const m = ALL_MEMBERS.find((x) => x.id === id); if (!m) return [];
  return [{ ...m, role: id === s.value.ownerId ? "owner" as const : s.value.share[id] }];
}));
/** 찾은 사람을 고른 권한으로 초대하고 찾기 칸을 비운다. */
function invite(id: string) { cv.setShare({ share: { ...s.value.share, [id]: role.value } }); q.value = ""; focused.value = false; }
/** 초대한 사람의 권한을 바꾸거나("edit" / "view") 권한을 지운다("remove"). */
function setRole(id: string, v: string) {
  const share = { ...s.value.share };
  if (v === "remove") delete share[id]; else share[id] = v as "edit" | "view";
  cv.setShare({ share });
}
</script>

<template>
  <div class="scrim share-scrim">
    <section class="share" role="dialog" aria-modal="true" :aria-label="`'${cv.canvasName}' 공유`">
      <header><h2>'{{ cv.canvasName }}' 공유</h2><button class="x" aria-label="닫기" @click="emit('close')">✕</button></header>

      <div v-if="can" class="inv">
        <input v-model="q" placeholder="사번, 이름, 부서로 사내 사람 찾기" aria-label="초대할 사람 찾기" @keydown.enter.prevent="found[0] && invite(found[0].id)" @focus="focused = true" @click="focused = true" @input="focused = true" @blur="blur" />
        <select v-model="role" aria-label="초대 권한"><option value="edit">편집 가능</option><option value="view">보기 가능</option></select>
        <ul v-if="focused" class="found">
          <li v-if="!q.trim() && found.length" class="cap">추천</li>
          <li v-for="m in found" :key="m.id"><button @mousedown.prevent @click="invite(m.id)">
            <span class="av">{{ m.name[0] }}</span><b>{{ m.name }}</b><small>{{ m.id }} · {{ m.dept }} · {{ m.job }}</small></button></li>
          <li v-if="!found.length" class="none">찾는 사람이 없거나 이미 권한이 있습니다.</li>
        </ul>
      </div>
      <p v-else class="note">공유 설정은 소유자만 바꿀 수 있습니다.</p>

      <h3>권한 있는 사람</h3>
      <ul class="ppl">
        <li v-for="m in people" :key="m.id">
          <span class="av" :data-me="m.id === cv.user!.id">{{ m.name[0] }}</span>
          <span class="nm"><b>{{ m.name }}<template v-if="m.id === cv.user!.id"> (나)</template></b><small>{{ m.dept }} · {{ m.job }}</small></span>
          <span v-if="m.role === 'owner' || !can" class="rl">{{ ROLE[m.role] }}</span>
          <select v-else :value="m.role" :aria-label="m.name + ' 권한'" @change="setRole(m.id, ($event.target as HTMLSelectElement).value)">
            <option value="edit">편집 가능</option><option value="view">보기 가능</option><option value="remove">권한 삭제</option></select>
        </li>
      </ul>

      <footer><button class="primary" @click="emit('close')">완료</button></footer>
    </section>
  </div>
</template>
