<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('ac.title') }}</h2>
    <p class="v-hint">{{ t('ac.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('ac.desc')"></p>
    </details>

    <div class="v-row">
      <label>trace_id
        <el-input v-model="traceId" :placeholder="t('audit.placeholder-trace')" style="width:360px"
          @keydown.enter="search" />
      </label>
      <el-button type="primary" @click="search">{{ t('ac.btn-search') }}</el-button>
    </div>

    <p class="v-hint">{{ summary }}</p>

    <div class="v-section">
      <h3 v-html="t('ac.tb-audit-title', [dbCount])"></h3>
      <el-table :data="paginatedEvents" size="small" border stripe :empty-text="t('ac.empty')">
        <el-table-column :label="t('rollout.header-time')" width="170">
          <template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template>
        </el-table-column>
        <el-table-column :label="t('rollout.header-layer')" prop="layer" width="90" />
        <el-table-column :label="t('ac.header-scene')" prop="scene" width="120" />
        <el-table-column :label="t('rollout.header-action')" prop="effective_action" width="90" />
        <el-table-column :label="t('ac.header-rule')" prop="rule_id" />
        <el-table-column :label="t('rollout.header-reason')" prop="reason_code" width="130" />
        <el-table-column :label="t('rollout.header-risk')" prop="max_risk_score" width="70" />
        <el-table-column :label="t('rollout.header-rollout')" width="110">
          <template #default="{ row }">{{ rolloutLabel(row.rollout_state, row.canary_percent) }}</template>
        </el-table-column>
        <el-table-column :label="t('rollout.header-user-id')" prop="user_id" />
      </el-table>
      <el-pagination v-if="total > size" small background layout="prev, pager, next"
        v-model:current-page="page" :page-size="size" :total="total"
        @current-change="scrollTop" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { fmtTime } from '@/utils/format';

const { t } = useI18n();
const route = useRoute();
const feedback = useFeedbackStore();
const session = useSessionStore();

const page = ref(1);
const size = ref(50);
const total = ref(0);
const traceId = ref('');
const dbCount = ref(0);
const events = ref<any[]>([]);
const paginatedEvents = computed(() => events.value.slice((page.value - 1) * size.value, page.value * size.value));
const summary = ref('');

function scrollTop() { document.querySelector('.v-scroll')?.scrollTo(0, 0); }

function rolloutLabel(st: string, pct: any): string {
  const s = st || '';
  const label = t('ro-state.' + s);
  if (s === 'canary' && pct != null) return label + ' (' + pct + '%)';
  return label || s;
}

async function searchRecent(limit = 100) {
  summary.value = t('common.loading');
  try {
    const data = await admin<any>(`/audit/recent?limit=${limit}`);
    dbCount.value = data?.db_count ?? 0;
    events.value = data?.db_events || [];
    total.value = events.value.length; page.value = 1;
    summary.value = data?.note || (events.value.length ? '' : t('ac.no-db-records'));
  } catch (e: any) { summary.value = e.message; events.value = []; }
}

async function search() {
  if (!traceId.value.trim()) { searchRecent(100); return; }
  summary.value = t('ac.searching');
  try {
    const data = await admin<any>('/audit/trace/' + encodeURIComponent(traceId.value.trim()));
    dbCount.value = data?.db_count ?? 0;
    events.value = data?.db_events || [];
    total.value = events.value.length; page.value = 1;
    summary.value = data?.note || (events.value.length ? '' : t('ac.no-db-records'));
  } catch (e: any) { summary.value = e.message; events.value = []; }
}

onMounted(() => {
  const q = route.query.trace_id;
  if (q) { traceId.value = String(q); search(); }
  else searchRecent(100);
});
watch(() => session.tenant, () => searchRecent(100));
</script>
