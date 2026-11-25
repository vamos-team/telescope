<script setup lang="ts">
// 첫 화면: 사용자를 고르면 그 사람의 부서 캔버스로 들어간다. 부서는 구성원 정보에서 정해짐 (목업. 실제 서비스는 사내 SSO)
import { ref } from "vue";
import { useCanvas, ALL_MEMBERS } from "../stores/canvas";
import Logo from "./Logo.vue";

const cv = useCanvas();
const name = ref(ALL_MEMBERS[0].name);
</script>

<template>
  <main class="login">
    <form class="lcard" @submit.prevent="cv.login(name)">
      <Logo :size="52" />
      <h1>Telescope 캔버스</h1>
      <p>부서 캔버스에서 공시·IR·뉴스 근거를 같이 모으고 결과물을 만듭니다.</p>
      <label>사용자<select v-model="name"><option v-for="m in ALL_MEMBERS" :key="m.name" :value="m.name">{{ m.name }}</option></select></label>
      <button class="primary">로그인</button>
      <small class="sso">데모 로그인입니다. 실제 서비스는 사내 계정(SSO)으로 들어옵니다.</small>
    </form>
  </main>
</template>
