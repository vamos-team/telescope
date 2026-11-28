<script setup lang="ts">
// 회원가입 폼(로그인 화면 오른쪽). 한 칸씩 단계로 진행: 맞게 입력하면 체크가 뜨고, Enter나 다음 칸으로 넘어가면 아래에 다음 칸이 나타남.
// 순서: 사원번호 → 이름 → 비밀번호 → 비밀번호 확인 → 부서 → 직무. 부서 · 직무는 목록에서 고르며 고르지 않아도 가입할 수 있음.
// 역할은 보내지 않음(서버가 항상 USER). 화면 검증은 안내용이고 최종 검증은 서버가 함
import { computed, nextTick, reactive, ref } from "vue";
import { DEPTS, JOBS, useCanvas } from "../stores/canvas";
import { authErrorMessage } from "../sync/http";

const emit = defineEmits<{ back: [] }>();
const cv = useCanvas();
const f = reactive({ employeeNo: "", name: "", password: "", confirm: "", dept: "", job: "" });
const err = ref(""), busy = ref(false);
const MIN_LENGTH = 8, MAX_BYTES = 72;  // backend PasswordPolicy와 같은 값(BCrypt 상한)

type Key = "employeeNo" | "name" | "password" | "confirm";
const STEPS: { key: Key; label: string; type: string; auto: string; ph: string }[] = [
  { key: "employeeNo", label: "사원번호", type: "text", auto: "username", ph: "사원번호를 입력해 주세요" },
  { key: "name", label: "이름", type: "text", auto: "name", ph: "이름을 입력해 주세요" },
  { key: "password", label: "비밀번호", type: "password", auto: "new-password", ph: `${MIN_LENGTH}자 이상` },
  { key: "confirm", label: "비밀번호 확인", type: "password", auto: "new-password", ph: "한 번 더 입력해 주세요" },
];
/** 칸별로 맞는지(체크 표시). 틀렸을 때 보여 줄 안내 문구를 함께 돌려줌 */
function check(key: Key): { ok: boolean; hint: string } {
  const v = f[key];
  if (key === "employeeNo") return { ok: !!v.trim(), hint: "사원번호를 입력해 주세요" };
  if (key === "name") return { ok: !!v.trim(), hint: "이름을 입력해 주세요" };
  if (key === "password") {
    if (v.length < MIN_LENGTH) return { ok: false, hint: `${MIN_LENGTH}자 이상 입력해 주세요` };
    return { ok: new TextEncoder().encode(v).length <= MAX_BYTES, hint: "비밀번호가 너무 깁니다" };
  }
  return { ok: !!v && v === f.password, hint: "비밀번호가 일치하지 않습니다" };
}
const shown = ref(1);  // 지금까지 나타난 입력 칸 수. 부서 · 직무 단계는 그 뒤 번호(5, 6)
const touched = reactive<Record<Key, boolean>>({ employeeNo: false, name: false, password: false, confirm: false });
const allOk = computed(() => STEPS.every((s) => check(s.key).ok));
const inputs = ref<HTMLInputElement[]>([]);

/** 맞게 입력한 칸에서 넘어가면 다음 단계를 보여 주고 그 칸으로 커서를 옮김 */
async function advance(i: number, focusNext: boolean) {
  touched[STEPS[i].key] = true;
  if (!check(STEPS[i].key).ok) return;
  if (shown.value === i + 1) { shown.value = i + 2; await reveal(); }
  if (focusNext) { await nextTick(); inputs.value[i + 1]?.focus({ preventScroll: true }); }
}
/** 새로 나타난 단계가 화면 밖이면 부드럽게 보이는 곳까지 내림 */
const form = ref<HTMLFormElement>();
async function reveal() {
  await nextTick();
  form.value?.querySelector(".su-steps > :last-child")?.scrollIntoView({ block: "nearest", behavior: "smooth" });
}
/** 부서 · 직무 목록이 펼쳐져 있는지. 고르면 접고 고른 값만 칸으로 보여 줌. 그 칸을 누르면 다시 펼침 */
const open = reactive({ dept: true, job: true });
function pick(kind: "dept" | "job", v: string) {
  f[kind] = f[kind] === v ? "" : v;  // 다시 누르면 선택 해제
  open[kind] = !f[kind];
  if (kind === "dept" && shown.value < 6) { shown.value = 6; void reveal(); }
}

async function submit() {
  if (busy.value) return;
  STEPS.forEach((s) => (touched[s.key] = true));
  if (!allOk.value) { err.value = "입력 내용을 확인해 주세요"; return; }
  busy.value = true; err.value = "";
  try {
    await cv.signUp({ employeeNo: f.employeeNo.trim(), password: f.password, name: f.name.trim(),
      dept: f.dept || undefined, job: f.job || undefined });
  } catch (e) {
    err.value = authErrorMessage(e);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <form ref="form" class="auth-form signup" @submit.prevent="submit" @input="err = ''">
    <h2>회원가입</h2>
    <p class="auth-sub">가입하면 바로 시작할 수 있어요</p>

    <TransitionGroup name="su" tag="div" class="su-steps">
      <template v-for="(s, i) in STEPS" :key="s.key">
        <label v-if="i < shown" class="su-step" :class="{ ok: check(s.key).ok, bad: touched[s.key] && !check(s.key).ok }">
          <span class="su-label">{{ s.label }}</span>
          <span class="su-field">
            <input ref="inputs" v-model="f[s.key]" :type="s.type" :autocomplete="s.auto" :placeholder="s.ph"
              :autofocus="i === 0" @keydown.enter.prevent="advance(i, true)" @blur="advance(i, false)" />
            <svg class="su-tick" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10.5l3.2 3.2L15 7" /></svg>
          </span>
          <small v-if="touched[s.key] && !check(s.key).ok" class="su-hint">{{ check(s.key).hint }}</small>
        </label>
      </template>

      <div v-if="shown >= 5" key="org" class="su-row">
        <div class="su-step">
          <span class="su-label">부서</span>
          <Transition name="su" mode="out-in">
            <button v-if="!open.dept" key="chosen" type="button" class="su-chosen" :aria-label="'부서 바꾸기: ' + f.dept" @click="open.dept = true">
              <span>{{ f.dept }}</span><svg class="su-tick" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10.5l3.2 3.2L15 7" /></svg>
            </button>
            <div v-else key="list" class="su-pick" role="listbox" aria-label="부서">
              <button v-for="d in DEPTS" :key="d" type="button" role="option" :aria-selected="f.dept === d" :class="{ on: f.dept === d }" @click="pick('dept', d)">
                {{ d }}<svg class="su-tick" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10.5l3.2 3.2L15 7" /></svg>
              </button>
            </div>
          </Transition>
        </div>
        <Transition name="su">
          <div v-if="shown >= 6" class="su-step">
            <span class="su-label">직무</span>
            <Transition name="su" mode="out-in">
              <button v-if="!open.job" key="chosen" type="button" class="su-chosen" :aria-label="'직무 바꾸기: ' + f.job" @click="open.job = true">
              <span>{{ f.job }}</span><svg class="su-tick" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10.5l3.2 3.2L15 7" /></svg>
            </button>
            <div v-else key="list" class="su-pick" role="listbox" aria-label="직무">
              <button v-for="j in JOBS" :key="j" type="button" role="option" :aria-selected="f.job === j" :class="{ on: f.job === j }" @click="pick('job', j)">
                {{ j }}<svg class="su-tick" viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10.5l3.2 3.2L15 7" /></svg>
              </button>
            </div>
            </Transition>
          </div>
        </Transition>
      </div>
    </TransitionGroup>

    <p v-if="err" class="auth-err" role="alert">{{ err }}</p>
    <Transition name="su">
      <button v-if="shown >= 5" class="auth-submit" :disabled="busy || !allOk">{{ busy ? "가입 중…" : "가입하기" }}</button>
    </Transition>
    <p class="auth-switch">이미 계정이 있으신가요? <button type="button" @click="emit('back')">로그인</button></p>
  </form>
</template>
