<template>
  <div class="async-preview">
    <div class="async-preview-header" @click="collapsed = !collapsed">
      <span class="async-preview-title">{{ t('rules.async-preview') }}</span>
      <span class="async-preview-toggle">{{ collapsed ? '▶' : '▼' }}</span>
    </div>
    <div v-show="!collapsed" class="async-preview-body">
      <pre class="async-preview-code" :class="{ 'async-preview-error': !isValid }">{{ previewText }}</pre>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  message: string;
  type: 'redis_stream' | 'webhook';
  streamKey?: string;
  webhookUrl?: string;
}>();

const { t } = useI18n();
const collapsed = ref(false);

const isValid = computed(() => {
  if (!props.message?.trim()) return true;
  try { JSON.parse(props.message); return true; } catch { return false; }
});

const previewText = computed(() => {
  let message: any = (props.message || '').trim();
  try { message = JSON.parse(message); } catch { /* keep raw */ }
  const cfg: any = { type: props.type };
  if (props.type === 'redis_stream') {
    if (props.streamKey) cfg.stream_key = props.streamKey;
    cfg.message = message || {};
  } else {
    if (props.webhookUrl) cfg.url = props.webhookUrl;
    cfg.body = message || {};
  }
  return JSON.stringify(cfg, null, 2);
});
</script>

<style scoped>
.async-preview {
  margin-top: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}
.async-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  background: #f1f5f9;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
  font-weight: 500;
  color: #475569;
}
.async-preview-header:hover {
  background: #e2e8f0;
}
.async-preview-toggle {
  font-size: 10px;
  color: #94a3b8;
}
.async-preview-body {
  padding: 8px 12px;
  background: #1e293b;
}
.async-preview-code {
  margin: 0;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.5;
  color: #e2e8f0;
  white-space: pre-wrap;
  word-break: break-all;
}
.async-preview-code.async-preview-error {
  color: #fca5a5;
}
</style>
