<script setup lang="ts">
// 회원가입 폼(로그인 화면 오른쪽). 역할은 보내지 않음(서버가 항상 USER). 화면 검증은 안내용이고 최종 검증은 서버가 함
import { reactive, ref } from "vue";
import { useCanvas } from "../stores/canvas";
import { authErrorMessage } from "../sync/http";

const emit = defineEmits<{ back: [] }>();
const cv = useCanvas();
const f = reactive({ employeeNo: "", password: "", confirm: "", name: "", dept: "", job: "" });
const err = ref(""), busy = ref(false);

const MIN_LENGTH = 8, MAX_BYTES = 72;  // backend PasswordPolicy와 같은 값(BCrypt 상한)

function invalid(): string {
  if (!f.employeeNo.trim() || !f.name.trim() || !f.password) return "사번, 이름, 비밀번호를 입력하세요";
  if (f.password.length < MIN_LENGTH) return `비밀번호는 ${MIN_LENGTH}자 이상이어야 합니다`;
  if (new TextEncoder().encode(f.password).length > MAX_BYTES) return "비밀번호가 너무 깁니다";
  if (f.password !== f.confirm) return "비밀번호 확인이 일치하지 않습니다";
  return "";
}

async function submit() {
  if (busy.value) return;
  err.value = invalid();
  if (err.value) return;
  busy.value = true;
  try {
    await cv.signUp({ employeeNo: f.employeeNo.trim(), password: f.password, name: f.name.trim(),
      dept: f.dept.trim() || undefined, job: f.job.trim() || undefined });
  } catch (e) {
    err.value = authErrorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <form class="auth-form" @submit.prevent="submit" @input="err = ''">
    <h2>회원가입</h2>
    <p class="auth-sub">가입하면 바로 로그인됩니다</p>
    <label>사번<input v-model="f.employeeNo" autocomplete="username" placeholder="예: 1001" /></label>
    <label>이름<input v-model="f.name" autocomplete="name" placeholder="이름" /></label>
    <label>비밀번호<input v-model="f.password" type="password" autocomplete="new-password" :placeholder="`${MIN_LENGTH}자 이상`" /></label>
    <label>비밀번호 확인<input v-model="f.confirm" type="password" autocomplete="new-password" placeholder="비밀번호 다시 입력" /></label>
    <div class="auth-row">
      <label><span>부서 <small>선택</small></span><input v-model="f.dept" placeholder="예: 재무팀" /></label>
      <label><span>직무 <small>선택</small></span><input v-model="f.job" placeholder="예: 재무기획" /></label>
    </div>
    <p v-if="err" class="auth-err" role="alert">{{ err }}</p>
    <button class="auth-submit" :disabled="busy">{{ busy ? "가입 중…" : "가입하기" }}</button>
    <p class="auth-switch">이미 계정이 있으신가요? <button type="button" @click="emit('back')">로그인</button></p>
  </form>
</template>
