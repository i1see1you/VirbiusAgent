<template>
  <div class="dlp-regex-test">
    <div class="dlp-regex-header" @click="collapsed = !collapsed">
      <span>{{ t('dlp.regex-test-title') }}</span>
      <span class="dlp-regex-toggle">{{ collapsed ? '▶' : '▼' }}</span>
    </div>
    <div v-show="!collapsed" class="dlp-regex-body">
      <div class="dlp-regex-row">
        <label>{{ t('dlp.regex-pattern') }}</label>
        <el-input v-model="pattern" :placeholder="t('dlp.regex-pattern-placeholder')"
          style="font-family:ui-monospace,monospace;font-size:12px" />
      </div>
      <div class="dlp-regex-row">
        <label>{{ t('dlp.regex-test-input') }}</label>
        <el-input v-model="testInput" type="textarea" :rows="3"
          :placeholder="t('dlp.regex-test-placeholder')" />
      </div>
      <div class="dlp-regex-result" :class="resultClass">
        <div v-if="!pattern" class="dlp-regex-empty">{{ t('dlp.regex-enter-pattern') }}</div>
        <div v-else-if="error" class="dlp-regex-error">{{ error }}</div>
        <template v-else>
          <div class="dlp-regex-match-info">
            <span v-if="matches.length">{{ t('dlp.regex-match-count', [matches.length]) }}</span>
            <span v-else>{{ t('dlp.regex-no-match') }}</span>
          </div>
          <div v-if="matches.length" class="dlp-regex-matches">
            <div v-for="(m, i) in matches" :key="i" class="dlp-regex-match">
              <span class="match-index">#{{ i + 1 }}</span>
              <span class="match-text">{{ m.text }}</span>
              <span class="match-range">[{{ m.start }}-{{ m.end }}]</span>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useI18n } from 'vue-i18n';

const props = defineProps<{
  initialPattern?: string;
}>();

const { t } = useI18n();
const collapsed = ref(false);
const pattern = ref(props.initialPattern || '');
const testInput = ref('');
const error = ref('');

interface Match {
  text: string;
  start: number;
  end: number;
}

const matches = computed<Match[]>(() => {
  if (!pattern.value || !testInput.value) return [];
  try {
    const re = new RegExp(pattern.value, 'g');
    const result: Match[] = [];
    let m: RegExpExecArray | null;
    let safety = 0;
    while ((m = re.exec(testInput.value)) !== null && safety < 100) {
      result.push({ text: m[0], start: m.index, end: m.index + m[0].length });
      if (m[0].length === 0) re.lastIndex++;
      safety++;
    }
    error.value = '';
    return result;
  } catch (e: any) {
    error.value = e.message;
    return [];
  }
});

const resultClass = computed(() => {
  if (error.value) return 'dlp-regex-result-error';
  if (matches.value.length) return 'dlp-regex-result-match';
  if (pattern.value && testInput.value) return 'dlp-regex-result-nomatch';
  return '';
});

watch(() => props.initialPattern, (v) => {
  if (v !== undefined) pattern.value = v;
});
</script>

<style scoped>
.dlp-regex-test {
  margin-top: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}
.dlp-regex-header {
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
.dlp-regex-header:hover {
  background: #e2e8f0;
}
.dlp-regex-toggle {
  font-size: 10px;
  color: #94a3b8;
}
.dlp-regex-body {
  padding: 12px;
}
.dlp-regex-row {
  margin-bottom: 8px;
}
.dlp-regex-row label {
  display: block;
  font-size: 12px;
  color: #475569;
  margin-bottom: 4px;
}
.dlp-regex-result {
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 12px;
  min-height: 32px;
}
.dlp-regex-result.dlp-regex-result-match {
  background: #dcfce7;
  border: 1px solid #86efac;
}
.dlp-regex-result.dlp-regex-result-nomatch {
  background: #fef9c3;
  border: 1px solid #fde047;
}
.dlp-regex-result.dlp-regex-result-error {
  background: #fee2e2;
  border: 1px solid #fca5a5;
}
.dlp-regex-empty {
  color: #94a3b8;
}
.dlp-regex-error {
  color: #991b1b;
}
.dlp-regex-match-info {
  font-weight: 500;
  margin-bottom: 6px;
}
.dlp-regex-matches {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dlp-regex-match {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}
.match-index {
  color: #64748b;
  font-size: 11px;
  min-width: 24px;
}
.match-text {
  font-family: ui-monospace, monospace;
  background: rgba(255,255,255,0.6);
  padding: 1px 4px;
  border-radius: 2px;
  word-break: break-all;
}
.match-range {
  color: #94a3b8;
  font-size: 11px;
}
</style>
