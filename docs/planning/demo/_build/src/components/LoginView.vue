<script setup lang="ts">
// 첫 화면: 사번 + 비밀번호로 로그인 (목업: 비밀번호는 아무 값이나 통과). 데모 계정(1001 이재무)이 미리 입력되어 있어 바로 로그인 가능
// 부서는 사번의 구성원 정보에서 정해짐. 실제 서비스는 사내 SSO
import { ref } from "vue";
import { useCanvas, memberById } from "../stores/canvas";
import Logo from "./Logo.vue";

const cv = useCanvas();
const DEMO_ID = "1001";  // 미리 입력하는 데모 계정
const demo = memberById(DEMO_ID)!;
const id = ref(DEMO_ID), pw = ref("demo"), err = ref("");
function submit() {
  if (!id.value.trim() || !pw.value) { err.value = "사번과 비밀번호를 입력하세요"; return; }
  if (cv.login(id.value) === false) err.value = "사번을 확인하세요";
}
</script>

<template>
  <main class="login">
    <form class="lcard" @submit.prevent="submit">
      <Logo :size="52" />
      <h1>Telescope 캔버스</h1>
      <p>부서 캔버스에서 공시·IR·뉴스 근거를 같이 모으고 결과물을 만듭니다.</p>
      <label>사번<input v-model="id" inputmode="numeric" autocomplete="username" placeholder="예: 1001" @input="err = ''" /></label>
      <label>비밀번호<input v-model="pw" type="password" autocomplete="current-password" placeholder="데모는 아무 값이나 입력" @input="err = ''" /></label>
      <p v-if="err" class="lerr" role="alert">{{ err }}</p>
      <button class="primary">로그인</button>
      <small class="sso">데모 계정({{ demo.id }} {{ demo.name }}, {{ demo.dept }} · {{ demo.job }})이 미리 입력되어 있습니다. 실제 서비스는 사내 계정(SSO)으로 들어옵니다.</small>
    </form>
  </main>
</template>
