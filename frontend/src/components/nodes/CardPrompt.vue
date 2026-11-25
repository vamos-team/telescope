<script setup lang="ts">
// 카드 프롬프트: 카드를 누르면 펼쳐지는 보라 칸. "이 근거를 어떻게 활용할지" 적으면 생성(결과물·질문)에 지시로 들어감
import { computed } from "vue";
import { useCanvas } from "../../stores/canvas";

const props = defineProps<{ id: string; text?: string }>();
const cv = useCanvas();
const open = computed(() => cv.active === props.id || !!props.text);
</script>

<template>
  <div v-if="open" class="cprompt nodrag">
    <span class="plab">프롬프트</span>
    <textarea :value="text ?? ''" rows="2" :readonly="cv.readOnly" aria-label="카드 프롬프트" placeholder="이 근거를 어떻게 활용할지 적으세요 (예: 예산 증감의 근거로만 쓰고 전망에는 쓰지 말 것)"
      @blur="cv.setCardPrompt(id, ($event.target as HTMLTextAreaElement).value.trim())" />
  </div>
</template>
