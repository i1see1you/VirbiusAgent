<template>
  <el-dialog v-model="visible" :title="t('rules.diff-title')" width="800px" :close-on-click-modal="false">
    <div class="diff-container">
      <div v-if="!hasChanges" class="diff-no-changes">
        {{ t('rules.diff-no-changes') }}
      </div>
      <div v-else class="diff-lines">
        <div v-for="(line, i) in diffLines" :key="i" class="diff-line" :class="lineClass(line)">
          <span class="diff-line-num">{{ line.lineNum }}</span>
          <span class="diff-line-prefix">{{ linePrefix(line) }}</span>
          <span class="diff-line-text">{{ line.text }}</span>
        </div>
      </div>
    </div>
    <template #footer>
      <el-button @click="cancel">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" @click="confirm">{{ t('rules.diff-confirm') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';

interface DiffLine {
  type: 'equal' | 'add' | 'remove';
  text: string;
  lineNum: number;
}

const props = defineProps<{
  oldText: string;
  newText: string;
}>();

const emit = defineEmits<{
  'confirm': [];
  'cancel': [];
}>();

const { t } = useI18n();
const visible = ref(false);

function computeDiff(oldStr: string, newStr: string): DiffLine[] {
  const oldLines = oldStr.split('\n');
  const newLines = newStr.split('\n');
  const result: DiffLine[] = [];

  const m = oldLines.length;
  const n = newLines.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => Array(n + 1).fill(0));

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
      }
    }
  }

  let i = m, j = n;
  const ops: DiffLine[] = [];
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      ops.unshift({ type: 'equal', text: oldLines[i - 1], lineNum: i });
      i--; j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      ops.unshift({ type: 'add', text: newLines[j - 1], lineNum: j });
      j--;
    } else if (i > 0) {
      ops.unshift({ type: 'remove', text: oldLines[i - 1], lineNum: i });
      i--;
    }
  }

  let lineNum = 0;
  for (const op of ops) {
    if (op.type === 'add') {
      lineNum++;
      result.push({ ...op, lineNum });
    } else if (op.type === 'equal') {
      lineNum++;
      result.push({ ...op, lineNum });
    } else {
      result.push({ ...op, lineNum: op.lineNum });
    }
  }
  return result;
}

const diffLines = computed(() => computeDiff(props.oldText, props.newText));
const hasChanges = computed(() => diffLines.value.some(l => l.type !== 'equal'));

function lineClass(line: DiffLine): string {
  return 'diff-' + line.type;
}

function linePrefix(line: DiffLine): string {
  if (line.type === 'add') return '+';
  if (line.type === 'remove') return '-';
  return ' ';
}

function open() {
  visible.value = true;
}

function confirm() {
  visible.value = false;
  emit('confirm');
}

function cancel() {
  visible.value = false;
  emit('cancel');
}

defineExpose({ open });
</script>

<style scoped>
.diff-container {
  max-height: 500px;
  overflow: auto;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #f8fafc;
}
.diff-no-changes {
  padding: 24px;
  text-align: center;
  color: #64748b;
  font-size: 14px;
}
.diff-lines {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  line-height: 1.6;
}
.diff-line {
  display: flex;
  padding: 0 8px;
  white-space: pre;
}
.diff-line-num {
  width: 40px;
  text-align: right;
  padding-right: 8px;
  color: #94a3b8;
  user-select: none;
  flex-shrink: 0;
}
.diff-line-prefix {
  width: 16px;
  text-align: center;
  flex-shrink: 0;
  font-weight: bold;
}
.diff-line-text {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.diff-equal {
  background: #f8fafc;
  color: #334155;
}
.diff-add {
  background: #dcfce7;
  color: #166534;
}
.diff-add .diff-line-prefix {
  color: #22c55e;
}
.diff-remove {
  background: #fee2e2;
  color: #991b1b;
}
.diff-remove .diff-line-prefix {
  color: #ef4444;
}
</style>
