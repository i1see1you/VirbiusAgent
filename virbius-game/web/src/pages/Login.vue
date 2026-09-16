<script setup>
import { ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { api } from "../api.js";

const route = useRoute();
const router = useRouter();
const username = ref("");
const password = ref("");
const error = ref("");
const busy = ref(false);

async function submit() {
  error.value = "";
  busy.value = true;
  try {
    await api("/api/login", { method: "POST", body: { username: username.value, password: password.value } });
    router.push(typeof route.query.next === "string" ? route.query.next : "/play/solace");
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
      <p class="kicker">账号</p>
      <h1>登录</h1>
      <label>
        用户名
        <input v-model="username" autocomplete="username" required />
      </label>
      <label>
        密码
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="err">{{ error }}</p>
      <button class="btn btn--gold" type="submit" :disabled="busy">{{ busy ? "登录中" : "登录" }}</button>
      <p class="form-foot">没有账号？<RouterLink to="/register">注册</RouterLink></p>
    </form>
  </main>
</template>
