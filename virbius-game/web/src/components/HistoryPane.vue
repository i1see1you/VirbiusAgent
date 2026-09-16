<script setup>
import { computed, ref, watch } from "vue";
import Icon from "./Icon.vue";

const PAGE = 5;
const props = defineProps({
  attempts: { type: Array, default: () => [] },
  selectedId: { type: [Number, String], default: null },
  pass: { type: Number, default: 75 },
});
const emit = defineEmits(["select"]);

const page = ref(1);
const openReply = ref(null);

const pages = computed(() => Math.max(1, Math.ceil(props.attempts.length / PAGE)));
const slice = computed(() => {
  const p = Math.min(page.value, pages.value);
  const start = (p - 1) * PAGE;
  return props.attempts.slice(start, start + PAGE);
});

watch(
  () => props.attempts[0]?.id,
  () => {
    page.value = 1;
    openReply.value = null;
  },
);

function clip(text) {
  const s = String(text || "");
  return s.length <= 60 ? s : s.slice(0, 60) + "…";
}

function ago(iso) {
  const t = Date.parse(iso);
  if (!Number.isFinite(t)) return "";
  const mins = Math.floor((Date.now() - t) / 60000);
  if (mins < 1) return "刚刚";
  if (mins < 60) return `${mins} 分钟前`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} 小时前`;
  return `${Math.floor(hours / 24)} 天前`;
}

function tone(row) {
  if (row.passed || row.score >= props.pass) return "ok";
  if (row.score >= 40) return "mid";
  return "low";
}

function pick(row) {
  emit("select", row);
}

function toggleReply(id, ev) {
  ev.stopPropagation();
  openReply.value = openReply.value === id ? null : id;
}
</script>

<template>
  <section class="hist" aria-label="攻击历史">
    <p v-if="!attempts.length" class="hist-empty">这一档还没有攻击记录。</p>
    <div
      v-for="row in slice"
      :key="row.id"
      class="hist-card"
      :class="{ on: row.id === selectedId }"
      role="button"
      tabindex="0"
      :aria-pressed="row.id === selectedId"
      @click="pick(row)"
      @keydown.enter.prevent="pick(row)"
      @keydown.space.prevent="pick(row)"
    >
      <div class="hist-top">
        <p class="hist-text">{{ clip(row.attack) }}</p>
        <span class="hist-score" :data-tone="tone(row)">{{ row.score }}</span>
      </div>
      <div class="hist-meta">
        <Icon name="clock" />
        <span>{{ ago(row.created_at) }}</span>
        <span aria-hidden="true">·</span>
        <span>第 {{ String(row.level).replace("L", "") }} 档</span>
        <button type="button" class="hist-see" @click="toggleReply(row.id, $event)">
          {{ openReply === row.id ? "收起回复" : "查看回复" }}
        </button>
      </div>
      <p v-if="openReply === row.id" class="hist-reply">{{ row.reply }}</p>
    </div>
    <div v-if="pages > 1" class="hist-pager">
      <button type="button" :disabled="page <= 1" @click="page -= 1">上一页</button>
      <span>{{ page }} / {{ pages }}</span>
      <button type="button" :disabled="page >= pages" @click="page += 1">下一页</button>
    </div>
  </section>
</template>
