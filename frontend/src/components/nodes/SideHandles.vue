<script setup lang="ts">
// 연결 점: 좌우만. 보이는 점은 왼쪽(들어옴) · 오른쪽(나감). 선은 카드 위치에 맞춰 마주 보는 쪽 점에 붙음(App edges의 sourceHandle/targetHandle)
// 그래서 왼쪽으로 나가는 · 오른쪽으로 들어오는 보조 점(hh)을 숨겨 둔다. 위아래 점은 없음
import { Handle, Position } from "@vue-flow/core";
</script>

<template>
  <Handle id="tl" type="target" :position="Position.Left" />
  <Handle id="sr" type="source" :position="Position.Right" />
  <Handle id="sl" class="hh" type="source" :position="Position.Left" :connectable="false" :connectable-start="false" :connectable-end="false" />
  <Handle id="tr" class="hh" type="target" :position="Position.Right" :connectable="false" :connectable-start="false" :connectable-end="false" />
</template>
