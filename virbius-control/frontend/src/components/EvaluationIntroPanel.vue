<template>
  <div class="ev-intro">
    <el-alert
      v-if="status.license?.mode === 'expired'"
      type="error" :closable="false" show-icon style="margin-bottom:12px"
      :title="t('ev.license-expired')" :description="status.license?.detail || ''" />
    <el-alert
      v-else-if="!status.configured"
      type="info" :closable="false" show-icon style="margin-bottom:12px"
      :title="t('ev.intro-not-configured')" />
    <el-alert
      v-else
      type="warning" :closable="false" show-icon style="margin-bottom:12px"
      :title="t('ev.intro-unreachable')" :description="diagnostic" />

    <h3 class="ev-intro-title">{{ t('ev.intro-title') }}</h3>
    <p class="v-hint">{{ t('ev.intro-desc') }}</p>

    <ul class="ev-caps">
      <li>{{ t('ev.intro-cap-static') }}</li>
      <li>{{ t('ev.intro-cap-probe') }}</li>
      <li>{{ t('ev.intro-cap-mcp') }}</li>
      <li>{{ t('ev.intro-cap-replay') }}</li>
      <li>{{ t('ev.intro-cap-rescan') }}</li>
    </ul>

    <h4 class="ev-intro-title">{{ t('ev.intro-steps') }}</h4>
    <ol class="ev-steps">
      <li>{{ t('ev.intro-step-1') }}</li>
      <li>{{ t('ev.intro-step-2') }}
        <pre class="mono ev-code">VIRBIUS_EVALUATION_BASE_URL=http://virbius-evaluation:8084
VIRBIUS_EVALUATION_API_TOKEN=&lt;与评估服务 VE_API_TOKEN 同值&gt;</pre>
      </li>
      <li>{{ t('ev.intro-step-3') }}</li>
    </ol>

    <div class="v-row" style="margin-top:16px">
      <el-button size="small" @click="$emit('retry')">{{ t('ev.intro-retry') }}</el-button>
      <a v-if="promoEnabled" class="ev-cta" :href="promoHref" target="_blank"
         rel="noopener noreferrer">
        <el-button type="primary" size="small">{{ t('ev.intro-cta') }}</el-button>
      </a>
      <span v-else class="v-hint">{{ t('ev.intro-nolink') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useI18n } from 'vue-i18n';
import type { EvaluationStatus } from '@/api/evaluation';

const props = defineProps<{ status: EvaluationStatus; diagnostic?: string | null }>();
defineEmits<{ retry: [] }>();

const { t } = useI18n();

const diagnostic = computed(() => props.diagnostic || props.status.license?.detail || '');

/** 商业引导 CTA：仅 promo 开启且配置了落地页时渲染；外链带 UTM（不含任何本机信息）。 */
const promoEnabled = computed(() => props.status.promoEnabled && !!props.status.promoUrl);
const promoHref = computed(() => {
  const url = props.status.promoUrl || '';
  if (!url) return '';
  try {
    const u = new URL(url);
    u.searchParams.set('utm_source', 'virbiusagent');
    u.searchParams.set('utm_medium', 'ui');
    return u.toString();
  } catch {
    return url;
  }
});
</script>

<style scoped>
.ev-intro-title { margin: 16px 0 8px; font-size: 14px; }
.ev-caps { margin: 8px 0 0 18px; padding: 0; font-size: 13px; line-height: 1.9; }
.ev-steps { margin: 8px 0 0 18px; padding: 0; font-size: 13px; line-height: 1.9; }
.ev-code {
  margin: 6px 0; padding: 8px 10px; font-size: 12px; line-height: 1.6;
  background: var(--el-fill-color-light); border-radius: 6px; overflow-x: auto;
}
.ev-cta { text-decoration: none; }
</style>
