<script setup lang="ts">
// 첫 화면: 왼쪽은 로고와 우주 배경, 오른쪽은 로그인 폼(아래 작은 회원가입 링크)과 회원가입 폼을 바꿔 보여 줌.
// server 모드(VITE_CANVAS_SYNC=server)는 backend 로그인·회원가입(docs/backend/api-spec.md 3장),
// local 모드는 목업 구성원으로 들어가고 비밀번호는 검사하지 않음(기본 계정 1001이 미리 입력됨)
import { ref } from "vue";
import { useCanvas } from "../stores/canvas";
import { SYNC_MODE } from "../sync/config";
import { authErrorMessage } from "../sync/http";
import Logo from "./Logo.vue";
import SignupForm from "./SignupForm.vue";

const cv = useCanvas();
const local = SYNC_MODE !== "server";
const view = ref<"login" | "signup">("login");
const id = ref(local ? "1001" : ""), pw = ref(local ? "demo" : ""), err = ref(""), busy = ref(false);

async function submit() {
  if (busy.value) return;
  if (!id.value.trim() || !pw.value) { err.value = "사번과 비밀번호를 입력하세요"; return; }
  busy.value = true;
  try { await cv.signIn(id.value, pw.value); } catch (e) { err.value = authErrorMessage(e); } finally { busy.value = false; }
}
</script>

<template>
  <main class="auth">
    <section class="auth-hero">
      <div class="auth-stars" aria-hidden="true"><i /><i /><i /></div>
      <div class="auth-brand">
        <Logo :size="88" />
        <h1>Telescope</h1>
        <p>공시 · IR · 리포트 · 뉴스로 보는<br />경영실적 다면 분석</p>
      </div>
    </section>

    <section class="auth-panel">
      <form v-if="view === 'login'" class="auth-form" @submit.prevent="submit">
        <h2>로그인</h2>
        <p class="auth-sub">사번과 비밀번호를 입력하세요</p>
        <label>사번<input v-model="id" autocomplete="username" placeholder="예: 1001" @input="err = ''" /></label>
        <label>비밀번호<input v-model="pw" type="password" autocomplete="current-password" placeholder="비밀번호" @input="err = ''" /></label>
        <p v-if="err" class="auth-err" role="alert">{{ err }}</p>
        <button class="auth-submit" :disabled="busy">{{ busy ? "로그인 중…" : "로그인" }}</button>
        <p class="auth-switch">계정이 없으신가요? <button type="button" @click="view = 'signup'; err = ''">회원가입</button></p>
      </form>
      <SignupForm v-else @back="view = 'login'" />
    </section>
  </main>
</template>
