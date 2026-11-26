<script setup lang="ts">
// 카드 메모 (예전 저장본에 남은 것만 읽기 전용으로 표시). 새 메모는 카드 오른쪽 위 +로 메모 카드를 달아 씀
defineProps<{ id: string; text?: string }>();
</script>

<template>
  <div v-if="text" class="cmemo"><p class="mtext">{{ text }}</p></div>
</template>
