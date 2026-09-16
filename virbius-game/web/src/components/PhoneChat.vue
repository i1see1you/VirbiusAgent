<script setup>
import { computed, nextTick, ref, watch } from "vue";

const props = defineProps({
  title: { type: String, default: "桌面聊天" },
  subtitle: { type: String, default: "" },
  log: { type: Array, default: () => [] },
  sending: { type: Boolean, default: false },
  compose: { type: Boolean, default: false },
  error: { type: String, default: "" },
});

const emit = defineEmits(["send"]);

const logEl = ref(null);
const draft = ref("");
const rows = computed(() =>
  props.sending ? [...props.log, { role: "bot", text: "正在回复…", typing: true }] : props.log,
);

watch(
  () => [props.log.length, props.sending],
  async () => {
    await nextTick();
    if (logEl.value) logEl.value.scrollTop = logEl.value.scrollHeight;
  },
  { immediate: true },
);

function submit() {
  const text = draft.value.trim();
  if (!text || props.sending) return;
  emit("send", text);
  draft.value = "";
}
</script>

<template>
  <div class="phone" :aria-label="compose ? '受害者聊天' : '受害者手机上的聊天输出'">
    <div class="phone-bezel">
      <div class="phone-screen">
        <div class="phone-status" aria-hidden="true">
          <span>9:41</span>
          <i class="phone-island"></i>
          <span>LTE</span>
        </div>
        <section class="wx-chat">
          <header class="wx-nav">
            <span class="wx-nav-back" aria-hidden="true"></span>
            <div class="wx-nav-title">
              <h2>{{ title }}</h2>
            </div>
            <span class="wx-nav-more" aria-hidden="true"></span>
          </header>
          <div ref="logEl" class="wx-log" aria-live="polite">
            <div v-for="(m, i) in rows" :key="i" class="wx-row" :class="m.role">
              <!-- 客服：机器人 -->
              <svg v-if="m.role !== 'me'" class="wx-avatar" viewBox="0 0 36 36" aria-hidden="true">
                <rect width="36" height="36" rx="4" fill="#2f3b4a" />
                <circle cx="18" cy="5.5" r="1.7" fill="#7dd3fc" />
                <rect x="17.2" y="6.5" width="1.6" height="4.5" rx="0.6" fill="#94a3b8" />
                <rect x="8" y="11" width="20" height="19" rx="4.5" fill="#dbe4ee" />
                <rect x="10.5" y="16" width="15" height="7.5" rx="2.2" fill="#0f172a" />
                <circle cx="15.2" cy="19.8" r="1.7" fill="#38bdf8" />
                <circle cx="20.8" cy="19.8" r="1.7" fill="#38bdf8" />
                <rect x="14" y="26" width="8" height="1.8" rx="0.9" fill="#64748b" />
              </svg>
              <!-- 用户默认头像 -->
              <svg v-else class="wx-avatar" viewBox="0 0 36 36" aria-hidden="true">
                <rect width="36" height="36" rx="4" fill="#9bb6cc" />
                <circle cx="18" cy="13.5" r="6.2" fill="#f4f7fa" />
                <ellipse cx="18" cy="33" rx="12" ry="11" fill="#f4f7fa" />
              </svg>
              <div class="wx-bubble" :class="{ 'wx-typing': m.typing }">{{ m.text }}</div>
            </div>
          </div>
          <form v-if="compose" class="wx-bar" @submit.prevent="submit">
            <i class="wx-ico wx-ico-voice" aria-hidden="true"></i>
            <input
              class="wx-field"
              v-model="draft"
              :disabled="sending"
              :placeholder="sending ? '判定中…' : '发送消息'"
              autocomplete="off"
              maxlength="10000"
            />
            <button class="wx-send" type="submit" :disabled="sending || !draft.trim()">发送</button>
          </form>
          <div v-else class="wx-bar" aria-hidden="true">
            <i class="wx-ico wx-ico-voice"></i>
            <span class="wx-field"></span>
            <i class="wx-ico wx-ico-face"></i>
            <i class="wx-ico wx-ico-plus"></i>
          </div>
          <p v-if="compose && error" class="wx-err">{{ error }}</p>
        </section>
      </div>
    </div>
  </div>
</template>
