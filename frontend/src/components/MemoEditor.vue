<script setup lang="ts">
// 메모 입력 칸 (메모 카드 · 연결 메모 공용). Enter = 저장, Shift+Enter = 줄바꿈, 입력 완료 버튼 (화면에 사용법 문구는 두지 않음)
// 저장하면 보기 모드가 되고, 수정 버튼으로 다시 고친다
import { ref, watch, nextTick, onMounted } from "vue";

const props = defineProps<{ text?: string; placeholder?: string; readonly?: boolean; autofocus?: boolean }>();
const emit = defineEmits<{ save: [text: string] }>();
const editing = ref(!props.text && !props.readonly), draft = ref(props.text ?? ""), box = ref<HTMLTextAreaElement>();
watch(() => props.text, (t) => { if (!editing.value) draft.value = t ?? ""; });
const focus = () => nextTick(() => { box.value?.focus(); box.value?.setSelectionRange(draft.value.length, draft.value.length); });
onMounted(() => { if (props.autofocus && editing.value) focus(); });
function save() { const t = draft.value.trim(); emit("save", t); editing.value = !t; }
function edit() { draft.value = props.text ?? ""; editing.value = true; focus(); }
function key(e: KeyboardEvent) {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) { e.preventDefault(); save(); }
  else if (e.key === "Escape") { e.stopPropagation(); draft.value = props.text ?? ""; editing.value = !props.text; (e.target as HTMLElement).blur(); }
}
</script>

<template>
  <div class="medit nodrag nowheel" :class="{ on: editing }">
    <template v-if="editing && !readonly">
      <textarea ref="box" v-model="draft" rows="2" :placeholder="placeholder ?? '메모'" aria-label="메모" @keydown="key" />
      <div class="mrow"><button class="done" :disabled="!draft.trim() && !text" @click="save">입력 완료</button></div>
    </template>
    <template v-else>
      <p class="mtext">{{ text }}</p>
      <div v-if="!readonly" class="mrow"><button class="ed" @click="edit">수정</button></div>
    </template>
  </div>
</template>
