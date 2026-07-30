import { defineStore } from 'pinia';
import { ref } from 'vue';
import { ElMessage } from 'element-plus';

export const useFeedbackStore = defineStore('feedback', () => {
  const logMsg = ref('');
  const logLevel = ref<'info' | 'ok' | 'err' | 'warn'>('info');

  function log(x: any, level?: 'info' | 'ok' | 'err' | 'warn') {
    const msg = typeof x === 'string' ? x : JSON.stringify(x, null, 2);
    logMsg.value = msg;
    logLevel.value = level || 'info';
    if (level === 'err') ElMessage.error(msg);
  }

  function clear() {
    logMsg.value = '';
    logLevel.value = 'info';
  }

  return { logMsg, logLevel, log, clear };
});
