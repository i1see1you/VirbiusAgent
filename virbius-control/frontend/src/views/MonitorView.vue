<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('monitor.title') }}</h2>
    <p class="v-hint">{{ t('monitor.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('hint.monitor')"></p>
    </details>

    <div class="v-row">
      <el-button v-for="h in [24, 168, 720]" :key="h" :type="hours === h ? 'primary' : 'default'" size="small" @click="setHours(h)">{{ t('monitor.time-' + (h === 24 ? '24h' : h === 168 ? '7d' : '30d')) }}</el-button>
      <el-button size="small" @click="exportDash">{{ t('monitor.btn-export') }}</el-button>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-total-requests') }}</div><div class="value">{{ fmtNum(totals.total_requests) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-block-rate') }}</div><div class="value">{{ fmtPct(blockRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-review-rate') }}</div><div class="value">{{ fmtPct(reviewRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-degraded-rate') }}</div><div class="value">{{ fmtPct(degRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-active-rules') }}</div><div class="value">{{ activeRules }}</div></div>
    </div>

    <div class="v-section"><h3>{{ t('monitor.overall-traffic') }}</h3><div class="chart-wrap"><Bar v-if="trafficData" :data="trafficData" :options="stackedOpts" /><p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p></div></div>
    <div class="v-section"><h3>{{ t('monitor.overall-block-rate') }}</h3><div class="chart-wrap"><Line v-if="blockRateData" :data="blockRateData" :options="blockRateOpts" /><p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p></div></div>
    <div class="v-section">
      <h3>{{ t('monitor.rule-block-rate') }}</h3>
      <el-select v-model="selectedRule" style="width:240px;margin-bottom:8px">
        <el-option value="" :label="t('monitor.select-rule')" />
        <el-option v-for="r in allRules" :key="r" :value="r" :label="r" />
      </el-select>
      <div class="chart-wrap"><Line v-if="ruleChartData" :data="ruleChartData" :options="ruleOpts" /><p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p></div>
    </div>
    <div class="v-section">
      <h3>{{ t('monitor.rule-ranking') }}</h3>
      <el-table :data="ranking" size="small" border stripe :empty-text="t('monitor.empty-table')">
        <el-table-column :label="t('monitor.ranking-header-rule')" prop="rule_id" />
        <el-table-column :label="t('monitor.ranking-header-hits')"><template #default="{ row }">{{ fmtNum(row.total_hits) }}</template></el-table-column>
        <el-table-column :label="t('monitor.ranking-header-block')"><template #default="{ row }">{{ fmtNum(row.block) }}</template></el-table-column>
        <el-table-column :label="t('monitor.ranking-header-review')"><template #default="{ row }">{{ fmtNum(row.review) }}</template></el-table-column>
        <el-table-column :label="t('monitor.ranking-header-challenge')"><template #default="{ row }">{{ fmtNum(row.challenge) }}</template></el-table-column>
        <el-table-column :label="t('monitor.ranking-header-hit-rate')"><template #default="{ row }">{{ fmtPct(row.hit_rate) }}</template></el-table-column>
        <el-table-column :label="t('monitor.ranking-header-block-rate')"><template #default="{ row }">{{ fmtPct(row.block_rate) }}</template></el-table-column>
        <el-table-column :label="t('monitor.kpi-total-requests')"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
      </el-table>
    </div>
    <div class="v-section">
      <h3>{{ t('monitor.scene-traffic') }}</h3>
      <div class="chart-wrap" style="max-width:320px;margin:0 auto 12px"><Doughnut v-if="sceneData" :data="sceneData" :options="doughnutOpts" /></div>
      <el-table :data="scenes" size="small" border stripe :empty-text="t('monitor.empty-table')">
        <el-table-column :label="t('monitor.scene-header-scene')" prop="scene" />
        <el-table-column :label="t('monitor.scene-header-layer')" prop="layer" />
        <el-table-column :label="t('monitor.scene-header-requests')"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
      </el-table>
    </div>
    <div class="v-section"><h3>{{ t('monitor.degradation-title') }}</h3><div class="chart-wrap"><Line v-if="degData" :data="degData" :options="degOpts" /><p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p></div></div>
    <div class="v-section">
      <h3>{{ t('monitor.event-timeline') }}</h3>
      <el-table :data="events" size="small" border stripe :empty-text="t('monitor.empty-table')">
        <el-table-column :label="t('monitor.event-header-time')"><template #default="{ row }">{{ fmtTime(row.effective_at) }}</template></el-table-column>
        <el-table-column :label="t('monitor.event-header-rule')" prop="rule_id" />
        <el-table-column :label="t('monitor.event-header-state')" prop="rollout_state" width="100" />
        <el-table-column :label="t('monitor.event-header-rev')" prop="rule_revision" width="70" />
        <el-table-column :label="t('monitor.event-header-trigger')" prop="trigger" />
        <el-table-column :label="t('monitor.event-header-operator')"><template #default="{ row }">{{ row.operator || '-' }}</template></el-table-column>
      </el-table>
    </div>
    <div class="v-section">
      <h3>{{ t('monitor.ingest-health') }}</h3>
      <div style="font-size:13px;line-height:1.9">
        <template v-if="ingest && ingest.enabled !== undefined">
          <el-tag size="small" :type="ingest.enabled ? 'success' : 'danger'">{{ ingest.enabled ? t('monitor.ingest-ok') : 'disabled' }}</el-tag>
          stream: {{ ingest.stream_key || '-' }}<br />
          Redis: <el-tag size="small" :type="ingest.redis_ok ? 'success' : 'danger'">{{ ingest.redis_ok ? 'OK' : 'ERR' }}</el-tag><br />
          DB events (24h): {{ fmtNum(ingest.db_events_24h || 0) }}<br />
          Last poll: {{ ingest.last_poll_at || '-' }}
        </template>
        <span v-else class="v-hint">{{ t('monitor.no-data') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler } from 'chart.js';
import { Bar, Line, Doughnut } from 'vue-chartjs';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { fmtTime, parseUtc } from '@/utils/format';

ChartJS.register(CategoryScale, LinearScale, BarElement, PointElement, LineElement, ArcElement, Tooltip, Legend, Filler);

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const hours = ref(24);
const metrics = ref<any>(null);
const ranking = ref<any[]>([]);
const scenes = ref<any[]>([]);
const deg = ref<any>(null);
const events = ref<any[]>([]);
const ingest = ref<any>(null);
const allRules = ref<string[]>([]);
const selectedRule = ref('');
let timer: any = null;

const totals = computed(() => metrics.value?.totals || {});
const totalReq = computed(() => totals.value.total_requests || 0);
const blockRate = computed(() => totalReq.value > 0 ? (totals.value.block || 0) / totalReq.value : 0);
const reviewRate = computed(() => totalReq.value > 0 ? (totals.value.review || 0) / totalReq.value : 0);
const degRate = computed(() => totalReq.value > 0 ? (totals.value.cnt_degraded || 0) / totalReq.value : 0);
const activeRules = computed(() => new Set((metrics.value?.series || []).filter((s: any) => (s.total_requests || 0) > 0).map((s: any) => s.rule_id)).size);

function fmtPct(v: any) { return (v == null || isNaN(v)) ? '-' : (v * 100).toFixed(2) + '%'; }
function fmtNum(n: any) { if (n == null) return '0'; if (n >= 1e8) return (n / 1e8).toFixed(1) + '亿'; if (n >= 1e4) return (n / 1e4).toFixed(1) + '万'; return n.toLocaleString(); }

const legendOpts: any = { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 10, font: { size: 10 } } } };
function bucketLabels(series: any[]) {
  return series.map(p => { const d = parseUtc(p.bucket); return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''; });
}
function filterRecent(series: any[], ms: number) {
  const cutoff = Date.now() - ms;
  return series.filter(p => { const d = parseUtc(p.bucket); return d && d.getTime() < cutoff; });
}

const trafficData = computed(() => {
  const s = filterRecent(metrics.value?.series || [], 60000);
  if (!s.length) return null;
  return { labels: bucketLabels(s), datasets: [
    { label: 'allow', data: s.map(p => p.allow || 0), backgroundColor: 'rgba(34,197,94,0.7)', borderColor: '#22c55e', borderWidth: 1 },
    { label: 'review', data: s.map(p => p.review || 0), backgroundColor: 'rgba(251,191,36,0.7)', borderColor: '#fbbf24', borderWidth: 1 },
    { label: 'block', data: s.map(p => p.block || 0), backgroundColor: 'rgba(239,68,68,0.7)', borderColor: '#ef4444', borderWidth: 1 },
    { label: 'challenge', data: s.map(p => p.challenge || 0), backgroundColor: 'rgba(168,85,247,0.7)', borderColor: '#a855f7', borderWidth: 1 }
  ]};
});
const stackedOpts: any = { responsive: true, maintainAspectRatio: false, scales: { x: { stacked: true }, y: { stacked: true, beginAtZero: true } }, plugins: legendOpts, interaction: { mode: 'index', intersect: false } };

const blockRateData = computed(() => {
  const s = filterRecent(metrics.value?.series || [], 60000);
  if (!s.length) return null;
  return { labels: bucketLabels(s), datasets: [{ label: t('monitor.overall-block-rate'), data: s.map(p => p.total_requests > 0 ? (p.block || 0) / p.total_requests : 0), borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.1)', fill: true, tension: 0.3, pointRadius: 0 }] };
});
const blockRateOpts: any = { responsive: true, maintainAspectRatio: false, scales: { y: { beginAtZero: true, max: 1, ticks: { callback: (v: any) => fmtPct(v) } } }, plugins: { tooltip: { callbacks: { label: (c: any) => fmtPct(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

const ruleChartData = computed(() => {
  const series = metrics.value?.series || [];
  const ruleMap: Record<string, any[]> = {};
  series.forEach((s: any) => (ruleMap[s.rule_id] ||= []).push(s));
  allRules.value = Object.keys(ruleMap).sort();
  const sel = selectedRule.value || allRules.value[0];
  if (!sel) return null;
  const rs = filterRecent(ruleMap[sel] || [], 60000).sort((a, b) => (parseUtc(a.bucket)?.getTime() ?? 0) - (parseUtc(b.bucket)?.getTime() ?? 0));
  if (!rs.length) return null;
  return { labels: bucketLabels(rs), datasets: [
    { label: t('monitor.rule-block-rate'), data: rs.map(p => p.total_requests > 0 ? (p.block || 0) / p.total_requests : 0), yAxisID: 'y', borderColor: '#ef4444', tension: 0.3, pointRadius: 0 },
    { label: t('monitor.kpi-total-requests'), data: rs.map(p => p.total_requests || 0), yAxisID: 'y1', borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.3, pointRadius: 0 }
  ]};
});
const ruleOpts: any = { responsive: true, maintainAspectRatio: false, scales: { y: { beginAtZero: true, max: 1, position: 'left', ticks: { callback: (v: any) => fmtPct(v) } }, y1: { beginAtZero: true, position: 'right', grid: { drawOnChartArea: false } } }, plugins: { tooltip: { callbacks: { label: (c: any) => c.datasetIndex === 0 ? fmtPct(c.parsed.y) : String(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

const sceneData = computed(() => {
  if (!scenes.value.length) return null;
  const m: Record<string, number> = {};
  scenes.value.forEach(s => m[s.scene] = (m[s.scene] || 0) + s.total_requests);
  const labels = Object.keys(m);
  return { labels, datasets: [{ data: labels.map(l => m[l]), backgroundColor: ['#3b82f6', '#22c55e', '#f59e0b', '#ef4444', '#a855f7', '#06b6d4', '#ec4899', '#14b8a6'].slice(0, labels.length) }] };
});
const doughnutOpts: any = { responsive: true, maintainAspectRatio: false, plugins: legendOpts };

const degData = computed(() => {
  const s = deg.value?.series || [];
  if (!s.length) return null;
  return { labels: s.map((x: any) => fmtTime(x.bucket)), datasets: [{ label: t('monitor.degradation-title'), data: s.map((x: any) => x.degraded_rate || 0), borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,0.1)', fill: true, tension: 0.3, pointRadius: 0 }] };
});
const degOpts: any = { responsive: true, maintainAspectRatio: false, scales: { y: { beginAtZero: true, ticks: { callback: (v: any) => fmtPct(v) } } }, plugins: { tooltip: { callbacks: { label: (c: any) => fmtPct(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

async function load() {
  if (!session.tenant) return;
  try {
    const [m, rk, st, dg, ev, ih] = await Promise.all([
      admin<any>('/deploy-rollout/metrics?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/rule-ranking?hours=' + hours.value + '&limit=20').catch(() => null),
      admin<any>('/monitor/scene-traffic?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/degradation?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/event-timeline?hours=' + (hours.value > 48 ? hours.value : 48) + '&limit=20').catch(() => null),
      admin<any>('/audit/ingest-status').catch(() => null)
    ]);
    metrics.value = m;
    ranking.value = rk?.ranking || [];
    scenes.value = st?.scenes || [];
    deg.value = dg;
    events.value = ev?.events || [];
    ingest.value = ih;
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function setHours(h: number) { hours.value = h; load(); }

async function exportDash() {
  try {
    const [m, rk, st, dg, ev] = await Promise.all([
      admin<any>('/deploy-rollout/metrics?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/rule-ranking?hours=' + hours.value + '&limit=20').catch(() => null),
      admin<any>('/monitor/scene-traffic?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/degradation?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/event-timeline?hours=48&limit=20').catch(() => null)
    ]);
    const dump = JSON.stringify({ exportedAt: new Date().toISOString(), metrics: m, ranking: rk, sceneTraffic: st, degradation: dg, events: ev }, null, 2);
    await navigator.clipboard.writeText(dump);
    ElMessage.success(t('monitor.export-success'));
  } catch (e: any) { ElMessage.error(t('monitor.export-fail', [e.message])); }
}

onMounted(() => { load(); timer = setInterval(load, 30000); });
onUnmounted(() => { if (timer) clearInterval(timer); });
watch(() => session.tenant, load);

</script>
