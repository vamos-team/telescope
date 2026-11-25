<script setup lang="ts">
// Telescope 로고: 중간점검 발표 표지 로고(남색 바탕, 얇은 원형 테두리, 가운데 빛나는 네 꼭짓점 별, 테두리 위 작은 점 2개)를 SVG로 옮김
defineProps<{ size?: number }>();
const uid = "tl" + Math.random().toString(36).slice(2, 7);
</script>

<template>
  <svg :width="size ?? 34" :height="size ?? 34" viewBox="0 0 64 64" role="img" aria-label="Telescope">
    <defs>
      <radialGradient :id="uid + 'bg'" cx="50%" cy="45%" r="70%"><stop offset="0" stop-color="#2B2FA8" /><stop offset="1" stop-color="#0E1258" /></radialGradient>
      <radialGradient :id="uid + 'glow'"><stop offset="0" stop-color="#C9CCFF" stop-opacity=".55" /><stop offset="1" stop-color="#C9CCFF" stop-opacity="0" /></radialGradient>
    </defs>
    <rect width="64" height="64" rx="16" :fill="`url(#${uid}bg)`" />
    <circle cx="32" cy="32" r="21" fill="#3A3FBF" fill-opacity=".35" stroke="#C3C6FF" stroke-opacity=".8" stroke-width="1.3" />
    <circle cx="32" cy="32" r="13" :fill="`url(#${uid}glow)`" />
    <path d="M32 21.5C33 28.6 35.4 31 42.5 32 35.4 33 33 35.4 32 42.5 31 35.4 28.6 33 21.5 32 28.6 31 31 28.6 32 21.5Z" fill="#fff" />
    <circle cx="45.2" cy="15.8" r="1.2" fill="#fff" /><circle cx="17.3" cy="46.3" r="1" fill="#fff" />
  </svg>
</template>
