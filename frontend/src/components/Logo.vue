<script setup lang="ts">
// Telescope 로고: 중간점검 발표 표지의 네 꼭짓점 별만
defineProps<{ size?: number }>();
</script>

<template>
  <svg :width="size ?? 30" :height="size ?? 30" viewBox="0 0 64 64" role="img" aria-label="Telescope">
    <path d="M32 4C34.6 22.6 41.4 29.4 60 32 41.4 34.6 34.6 41.4 32 60 29.4 41.4 22.6 34.6 4 32 22.6 29.4 29.4 22.6 32 4Z" fill="var(--logo)" />
  </svg>
</template>
