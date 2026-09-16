<script setup>
import { ref } from "vue";
import { RouterLink, useRouter } from "vue-router";
import { api } from "../api.js";

const router = useRouter();
const username = ref("");
const password = ref("");
const error = ref("");
const busy = ref(false);

async function submit() {
  error.value = "";
  busy.value = true;
  try {
    await api("/api/register", { method: "POST", body: { username: username.value, password: password.value } });
    router.push("/play/solace");
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <main class="page page--narrow">
    <form class="form-card" @submit.prevent="submit">
      <p class="kicker">新玩家</p>
      <h1>注册</h1>
      <p class="lead">用户名 3–20 位字母数字下划线。攻击文本会记入本地库。</p>
      <label>
        用户名
        <input v-model="username" autocomplete="username" required />
      </label>
      <label>
        密码（至少 6 位）
        <input v-model="password" type="password" autocomplete="new-password" required minlength="6" />
      </label>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="btn btn--gold" type="submit" :disabled="busy">{{ busy ? "注册中" : "注册并开始" }}</button>
      <p class="form-foot">已有账号？<RouterLink to="/login">登录</RouterLink></p>
    </form>
  </main>
</template>
