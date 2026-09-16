<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { api } from "../api.js";

const providers = ref([]);
const provider = ref("deepseek");
const model = ref("");
const base = ref("");
const key = ref("");
const showKey = ref(false);
const hasKey = ref(false);
const keyHint = ref("");
const ready = ref(false);
const error = ref("");
const ok = ref("");
const busy = ref(false);
const hydrating = ref(false);

const spec = computed(() => providers.value.find((p) => p.id === provider.value) || null);

watch(provider, (id, prev) => {
  if (hydrating.value || !prev || id === prev) return;
  const p = providers.value.find((x) => x.id === id);
  if (!p) return;
  model.value = p.model;
  base.value = p.base;
  key.value = "";
});

async function load() {
  error.value = "";
  hydrating.value = true;
  try {
    const data = await api("/api/settings");
    providers.value = data.providers || [];
    provider.value = data.provider || "deepseek";
    model.value = data.model || "";
    base.value = data.base || "";
    hasKey.value = !!data.has_key;
    keyHint.value = data.key_hint || "";
    ready.value = !!data.ready;
  } finally {
    hydrating.value = false;
  }
}

async function save() {
  error.value = "";
  ok.value = "";
  if (!key.value.trim() && !hasKey.value) {
    error.value = "先填 API Key";
    return;
  }
  busy.value = true;
  try {
    const data = await api("/api/settings", {
      method: "POST",
      body: {
        provider: provider.value,
        model: model.value,
        base: base.value,
        key: key.value.trim(),
      },
    });
    hasKey.value = !!data.has_key;
    keyHint.value = data.key_hint || "";
    ready.value = !!data.ready;
    key.value = "";
    ok.value = "已保存。打关时立刻用新配置，不用重启。";
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

onMounted(async () => {
  try {
    await load();
  } catch (e) {
    error.value = e.message;
  }
});
</script>

<template>
  <main class="page page--narrow">
    <form class="form-card" @submit.prevent="save">
      <p class="kicker">模型</p>
      <h1>设置</h1>
      <p class="lead">打关用的大模型。选一家、填 Key，保存后立刻用来判。</p>

      <label>
        模型类型
        <select v-model="provider">
          <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
      </label>

      <label>
        模型名
        <input v-model="model" autocomplete="off" :placeholder="spec?.model || ''" />
      </label>

      <label>
        接口地址
        <input v-model="base" autocomplete="off" :placeholder="spec?.base || ''" />
      </label>

      <label>
        API Key
        <span class="key-row">
          <input
            v-model="key"
            :type="showKey ? 'text' : 'password'"
            autocomplete="off"
            :placeholder="hasKey ? '已保存 ' + keyHint + '，留空不改' : '粘贴 Key'"
          />
          <button class="key-toggle" type="button" @click="showKey = !showKey">
            {{ showKey ? "隐藏" : "显示" }}
          </button>
        </span>
      </label>

      <p v-if="error" class="err">{{ error }}</p>
      <p v-else-if="ok" class="ok-msg">{{ ok }}</p>
      <p v-else class="form-foot">{{ ready ? "当前可以用。" : "还没配好，保存之后才能打关。" }}</p>

      <button class="btn btn--gold" type="submit" :disabled="busy">{{ busy ? "保存中" : "保存" }}</button>
    </form>
  </main>
</template>
