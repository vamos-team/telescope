<script setup lang="ts">
// 첫 화면: 사번 + 비밀번호로 로그인. backend 연결 전이라 비밀번호는 검사하지 않고, 기본 계정(1001)이 미리 입력됨
// 부서는 사번의 구성원 정보에서 정해짐
import { ref } from "vue";
import { useCanvas } from "../stores/canvas";
import Logo from "./Logo.vue";

const cv = useCanvas();
const DEFAULT_ID = "1001";  // 미리 입력하는 계정
const id = ref(DEFAULT_ID), pw = ref("demo"), err = ref("");
function submit() {
  if (!id.value.trim() || !pw.value) { err.value = "사번과 비밀번호를 입력하세요"; return; }
  if (cv.login(id.value) === false) err.value = "사번을 확인하세요";
}
</script>

<template>
  <main class="login">
    <form class="lcard" @submit.prevent="submit">
      <Logo :size="52" />
      <h1>Telescope</h1>
      <label>사번<input v-model="id" inputmode="numeric" autocomplete="username" placeholder="예: 1001" @input="err = ''" /></label>
      <label>비밀번호<input v-model="pw" type="password" autocomplete="current-password" placeholder="비밀번호" @input="err = ''" /></label>
      <p v-if="err" class="lerr" role="alert">{{ err }}</p>
      <button class="primary">로그인</button>
    </form>
  </main>
</template>
