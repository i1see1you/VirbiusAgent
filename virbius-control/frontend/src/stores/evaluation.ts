import { defineStore } from 'pinia';
import { ref } from 'vue';
import { getStatus, type EvaluationStatus } from '@/api/evaluation';

/**
 * 评估集成状态（默认打开：status 未加载时按"可见"处理，避免导航闪烁）。
 *
 * 单一来源：布局用它决定是否显示「评估」入口，页面用它决定 ready / 引导页 / 只读。
 */
export const useEvaluationStore = defineStore('evaluation', () => {
  const status = ref<EvaluationStatus | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);

  async function load(refresh = false): Promise<void> {
    loading.value = true;
    try {
      status.value = await getStatus(refresh);
      error.value = null;
    } catch (e: any) {
      error.value = String(e?.message || e);
      if (!status.value) {
        // 状态接口不可用（网络/未部署）：保守落到"未配置引导态"，不清空已得状态
        status.value = {
          enabled: true,
          configured: false,
          reachable: false,
          promoEnabled: true,
          promoUrl: ''
        };
      }
    } finally {
      loading.value = false;
    }
  }

  return { status, loading, error, load };
});
