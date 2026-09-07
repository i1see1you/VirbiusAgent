<template>
  <div class="v-card monitor-page">
    <header class="monitor-head">
      <div class="monitor-head-copy">
        <h2 class="v-card-title">{{ t('monitor.title') }}</h2>
        <p class="v-hint">{{ t('monitor.scope-tenant', [tenantLabel]) }} · {{ t('monitor.desc-short') }}</p>
        <details class="v-hint-more">
          <summary>{{ t('common.learn-more') }}</summary>
          <p class="v-hint" v-html="t('hint.monitor')"></p>
        </details>
      </div>
      <div class="monitor-head-actions">
        <el-button-group>
          <el-button v-for="h in [24, 168, 720]" :key="h" :type="hours === h ? 'primary' : 'default'" size="small" @click="setHours(h)">{{ t('monitor.time-' + (h === 24 ? '24h' : h === 168 ? '7d' : '30d')) }}</el-button>
        </el-button-group>
        <el-button size="small" @click="exportDash">{{ t('monitor.btn-export') }}</el-button>
        <span v-if="lastRefreshAt" class="v-hint" style="margin:0">{{ t('monitor.last-refresh', [fmtTime(lastRefreshAt)]) }}</span>
      </div>
    </header>

    <div class="kpi-grid monitor-kpis">
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-total-requests') }}</div><div class="value">{{ hasSamples ? fmtNum(totalReq) : '—' }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-block-rate') }}</div><div class="value">{{ fmtPct(blockRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-review-rate') }}</div><div class="value">{{ fmtPct(reviewRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-degraded-rate') }}</div><div class="value">{{ fmtPct(degRate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('monitor.kpi-active-rules') }}</div><div class="value">{{ hasSamples ? activeRules : '—' }}</div></div>
    </div>

    <el-tabs v-model="activeTab" class="monitor-tabs">
      <el-tab-pane name="trends" :label="t('monitor.tab-trends')" lazy>
        <div class="monitor-grid">
          <section class="monitor-panel">
            <h3>{{ t('monitor.overall-traffic') }}</h3>
            <div class="chart-wrap">
              <Bar v-if="activeTab === 'trends' && trafficData" :data="trafficData" :options="stackedOpts" />
              <p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p>
            </div>
          </section>
          <section class="monitor-panel">
            <h3>{{ t('monitor.overall-block-rate') }}</h3>
            <div class="chart-wrap">
              <Line v-if="activeTab === 'trends' && blockRateData" :data="blockRateData" :options="blockRateOpts" />
              <p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p>
            </div>
          </section>
          <section class="monitor-panel monitor-panel-wide">
            <h3>{{ t('monitor.degradation-title') }}</h3>
            <div class="chart-wrap">
              <Line v-if="activeTab === 'trends' && degData" :data="degData" :options="degOpts" />
              <p v-else class="v-empty-hint">{{ t('monitor.empty-chart') }}</p>
            </div>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane name="rules" :label="t('monitor.tab-rules')" lazy>
        <div class="monitor-grid monitor-grid-single">
          <section class="monitor-panel">
            <div class="panel-head">
              <h3>{{ t('monitor.rule-block-rate') }}</h3>
              <el-select v-model="selectedRule" size="small" class="rule-select">
                <el-option value="" :label="t('monitor.select-rule')" />
                <el-option v-for="r in allRules" :key="r" :value="r" :label="r" />
              </el-select>
            </div>
            <div class="chart-wrap">
              <Line v-if="activeTab === 'rules' && ruleChartData" :data="ruleChartData" :options="ruleOpts" />
              <p v-else class="v-empty-hint">{{ selectedRule ? t('monitor.empty-rule') : t('monitor.empty-chart') }}</p>
            </div>
          </section>
          <section class="monitor-panel">
            <h3>{{ t('monitor.rule-ranking') }}</h3>
            <el-table :data="ranking" size="small" border stripe max-height="320" :empty-text="t('monitor.empty-table')">
              <el-table-column :label="t('monitor.ranking-header-rule')" prop="rule_id" show-overflow-tooltip />
              <el-table-column :label="t('monitor.ranking-header-hits')" width="72"><template #default="{ row }">{{ fmtNum(row.total_hits) }}</template></el-table-column>
              <el-table-column :label="t('monitor.ranking-header-block')" width="72"><template #default="{ row }">{{ fmtNum(row.block) }}</template></el-table-column>
              <el-table-column :label="t('monitor.ranking-header-review')" width="72"><template #default="{ row }">{{ fmtNum(row.review) }}</template></el-table-column>
              <el-table-column :label="t('monitor.ranking-header-challenge')" width="72"><template #default="{ row }">{{ fmtNum(row.challenge) }}</template></el-table-column>
              <el-table-column :label="t('monitor.ranking-header-hit-rate')" width="88"><template #default="{ row }">{{ fmtPct(row.hit_rate) }}</template></el-table-column>
              <el-table-column :label="t('monitor.ranking-header-block-rate')" width="88"><template #default="{ row }">{{ fmtPct(row.block_rate) }}</template></el-table-column>
              <el-table-column :label="t('monitor.kpi-total-requests')" width="88"><template #default="{ row }">{{ fmtNum(row.total_requests) }}</template></el-table-column>
            </el-table>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane name="scenes" :label="t('monitor.tab-scenes')" lazy>
        <div v-if="!sceneStats.detail.length" class="scene-empty">
          <svg class="scene-empty-icon" viewBox="0 0 48 48" fill="none" aria-hidden="true">
            <circle cx="24" cy="24" r="16" stroke="currentColor" stroke-width="2.5" />
            <path d="M24 8a16 16 0 0 1 16 16" stroke="#0369A1" stroke-width="2.5" stroke-linecap="round" />
            <circle cx="24" cy="24" r="6" stroke="currentColor" stroke-width="2" />
          </svg>
          <p class="scene-empty-title">{{ t('monitor.scene-empty-title') }}</p>
          <p class="scene-empty-desc">{{ t('monitor.scene-empty-desc') }}</p>
        </div>
        <div v-else class="scene-board">
          <div class="kpi-grid scene-kpis">
            <div class="kpi-card"><div class="label">{{ t('monitor.scene-count') }}</div><div class="value">{{ sceneStats.sceneCount }}</div></div>
            <div class="kpi-card"><div class="label">{{ t('monitor.scene-layers') }}</div><div class="value">{{ sceneStats.layerCount }}</div></div>
            <div class="kpi-card"><div class="label">{{ t('monitor.kpi-total-requests') }}</div><div class="value">{{ fmtNum(sceneStats.total) }}</div></div>
            <div class="kpi-card"><div class="label">{{ t('monitor.scene-top-share') }}</div><div class="value">{{ fmtPct(sceneStats.topShare) }}</div></div>
          </div>
          <div class="scene-split">
            <section class="monitor-panel">
              <h3>{{ t('monitor.scene-traffic') }}</h3>
              <div class="chart-wrap" :style="{ height: sceneChartH + 'px' }">
                <Bar v-if="activeTab === 'scenes' && sceneBarData" :data="sceneBarData" :options="sceneBarOpts" />
              </div>
            </section>
            <section class="monitor-panel">
              <h3>{{ t('monitor.scene-breakdown') }}</h3>
              <el-table :data="sceneStats.detail" size="small" border stripe max-height="320">
                <el-table-column :label="t('monitor.scene-header-scene')" min-width="120" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="scene-swatch" :style="{ background: row.color }"></span>{{ row.scene }}
                  </template>
                </el-table-column>
                <el-table-column :label="t('monitor.scene-header-layer')" width="88">
                  <template #default="{ row }"><el-tag size="small">{{ row.layer || '-' }}</el-tag></template>
                </el-table-column>
                <el-table-column :label="t('monitor.scene-header-requests')" width="88">
                  <template #default="{ row }">{{ fmtNum(row.total_requests) }}</template>
                </el-table-column>
                <el-table-column :label="t('monitor.scene-share')" min-width="140">
                  <template #default="{ row }">
                    <div class="share-cell">
                      <span class="share-bar" aria-hidden="true"><i :style="{ width: (row.share * 100) + '%', background: row.color }"></i></span>
                      <span>{{ fmtPct(row.share) }}</span>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
            </section>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane name="ops" :label="t('monitor.tab-ops')" lazy>
        <div class="monitor-grid monitor-grid-single">
          <section class="monitor-panel">
            <h3>{{ t('monitor.ingest-health') }}</h3>
            <dl v-if="ingest && ingest.enabled !== undefined" class="ingest-grid">
              <div>
                <dt>{{ t('monitor.ingest-header-status') }}</dt>
                <dd><el-tag size="small" :type="ingest.enabled ? 'success' : 'danger'">{{ ingest.enabled ? t('monitor.ingest-ok') : t('monitor.ingest-disabled') }}</el-tag></dd>
              </div>
              <div>
                <dt>{{ t('monitor.ingest-stream') }}</dt>
                <dd>{{ ingest.stream_key || '-' }}</dd>
              </div>
              <div>
                <dt>{{ t('monitor.ingest-redis') }}</dt>
                <dd><el-tag size="small" :type="ingest.redis_ok ? 'success' : 'danger'">{{ ingest.redis_ok ? t('monitor.ingest-ok') : t('monitor.ingest-stopped') }}</el-tag></dd>
              </div>
              <div>
                <dt>{{ t('monitor.ingest-db-events') }}</dt>
                <dd>{{ fmtNum(ingest.db_events_24h || 0) }}</dd>
              </div>
              <div>
                <dt>{{ t('monitor.ingest-last-poll') }}</dt>
                <dd>{{ ingest.last_poll_at || '-' }}</dd>
              </div>
            </dl>
            <span v-else class="v-hint">{{ t('monitor.no-data') }}</span>
          </section>
          <section class="monitor-panel">
            <h3>{{ t('monitor.event-timeline') }}</h3>
            <el-table :data="events" size="small" border stripe max-height="280" :empty-text="t('monitor.empty-table')">
              <el-table-column :label="t('monitor.event-header-time')" width="128"><template #default="{ row }">{{ fmtTime(row.effective_at) }}</template></el-table-column>
              <el-table-column :label="t('monitor.event-header-rule')" prop="rule_id" show-overflow-tooltip />
              <el-table-column :label="t('monitor.event-header-state')" prop="rollout_state" width="88" />
              <el-table-column :label="t('monitor.event-header-rev')" prop="rule_revision" width="56" />
              <el-table-column :label="t('monitor.event-header-trigger')" prop="trigger" width="80" />
              <el-table-column :label="t('monitor.event-header-operator')" width="80"><template #default="{ row }">{{ row.operator || '-' }}</template></el-table-column>
            </el-table>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { Chart as ChartJS, CategoryScale, LinearScale, BarElement, PointElement, LineElement, Tooltip, Legend, Filler } from 'chart.js';
import { Bar, Line } from 'vue-chartjs';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin, adminRoot } from '@/api/client';
import { field, fmtTime, parseUtc } from '@/utils/format';

ChartJS.register(CategoryScale, LinearScale, BarElement, PointElement, LineElement, Tooltip, Legend, Filler);

const HOURS = [24, 168, 720] as const;

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();
const route = useRoute();
const router = useRouter();

const hours = ref(24);
const activeTab = ref('trends');
const metrics = ref<any>(null);
const ranking = ref<any[]>([]);
const scenes = ref<any[]>([]);
const deg = ref<any>(null);
const events = ref<any[]>([]);
const ingest = ref<any>(null);
const ingestFailed = ref(false);
const selectedRule = ref('');
const ruleSeries = ref<any[]>([]);
const tenantName = ref('');
const lastRefreshAt = ref<string | null>(null);
let timer: any = null;
let syncingQuery = false;

const tenantLabel = computed(() =>
  tenantName.value ? t('monitor.tenant-named', [tenantName.value, session.tenant]) : session.tenant);

const totals = computed(() => metrics.value?.totals || {});
const totalReq = computed(() => Number(totals.value.total_requests || 0));
const hasSamples = computed(() => totalReq.value > 0 || ranking.value.some((r: any) => (r.total_requests || r.total_hits || 0) > 0));
const blockRate = computed(() => hasSamples.value && totalReq.value > 0 ? (totals.value.block || 0) / totalReq.value : null);
const reviewRate = computed(() => hasSamples.value && totalReq.value > 0 ? (totals.value.review || 0) / totalReq.value : null);
const degRate = computed(() => {
  const s = deg.value?.series || [];
  const tot = s.reduce((a: number, x: any) => a + (x.total_requests || 0), 0);
  const d = s.reduce((a: number, x: any) => a + (x.degraded || 0), 0);
  return tot > 0 ? d / tot : null;
});
const activeRules = computed(() => ranking.value.filter((r: any) => (r.total_hits || 0) > 0).length);
const allRules = computed(() => ranking.value.map((r: any) => r.rule_id).filter(Boolean));

const emptyKind = computed(() => {
  if (ingestFailed.value) return 'ingest-fail';
  if (ingest.value && ingest.value.enabled === false) return 'ingest-off';
  const ev24 = Number(ingest.value?.db_events_24h || 0);
  const hasRollup = (metrics.value?.series || []).length > 0;
  const hasRaw = (metrics.value?.series_1m || []).length > 0 || ranking.value.length > 0;
  if (ev24 > 0 && !hasRollup && !hasRaw) return 'aggregating';
  if (ev24 === 0 && !hasRollup && !hasRaw) return 'no-events';
  return 'no-traffic';
});
const emptyChartText = computed(() => emptyText(emptyKind.value));
const emptyTableText = computed(() => emptyKind.value === 'no-events' || emptyKind.value === 'ingest-fail' || emptyKind.value === 'ingest-off'
  ? emptyText(emptyKind.value) : t('monitor.empty-table'));

const ingestTone = computed(() => {
  if (ingestFailed.value || (ingest.value && ingest.value.enabled === false)) return 'err';
  if (ingest.value && Number(ingest.value.db_events_24h || 0) === 0) return 'warn';
  return 'ok';
});

function emptyText(kind: string) {
  if (kind === 'ingest-fail') return t('monitor.empty-ingest-fail');
  if (kind === 'ingest-off') return t('monitor.empty-ingest-off');
  if (kind === 'aggregating') return t('monitor.empty-aggregating');
  if (kind === 'no-events') return t('monitor.empty-no-events', [tenantLabel.value]);
  return t('monitor.empty-chart');
}

function fmtPct(v: any) {
  if (v == null || isNaN(v)) return '—';
  return (v * 100).toFixed(2) + '%';
}
function fmtNum(n: any) {
  if (n == null) return '0';
  if (n >= 1e8) return (n / 1e8).toFixed(1) + '亿';
  if (n >= 1e4) return (n / 1e4).toFixed(1) + '万';
  return n.toLocaleString();
}

function pointRadius(n: number) {
  return n < 4 ? 6 : n < 12 ? 3 : 1;
}
function lineSeries(series: any[], values: Array<number | null>) {
  const labels = bucketLabels(series);
  if (series.length === 1) {
    return { labels: ['', labels[0], ''], values: [null, values[0], null] };
  }
  return { labels, values };
}
function downsample(series: any[], h: number) {
  if (!series.length || h <= 24) return series;
  const map = new Map<string, any>();
  for (const p of series) {
    const d = parseUtc(p.bucket);
    if (!d) continue;
    const hour = new Date(d);
    hour.setMinutes(0, 0, 0);
    const key = hour.toISOString();
    const cur = map.get(key) || { bucket: key, review: 0, block: 0, challenge: 0, allow: 0, total_requests: 0 };
    cur.review += p.review || 0;
    cur.block += p.block || 0;
    cur.challenge += p.challenge || 0;
    cur.allow += p.allow || 0;
    cur.total_requests += p.total_requests || 0;
    map.set(key, cur);
  }
  return [...map.values()];
}
function overallSeries() {
  const s = metrics.value?.series || [];
  const s1 = metrics.value?.series_1m || [];
  return downsample(s.length ? s : s1, hours.value);
}

const tickFont = { size: 10 };
const legendOpts: any = { legend: { position: 'top', labels: { boxWidth: 8, padding: 4, font: tickFont } } };
function bucketLabels(series: any[]) {
  return series.map(p => {
    const d = parseUtc(p.bucket);
    return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '';
  });
}

const trafficData = computed(() => {
  const s = overallSeries();
  if (!s.length) return null;
  return {
    labels: bucketLabels(s),
    datasets: [
      { label: t('monitor.series-allow'), data: s.map(p => p.allow || 0), backgroundColor: 'rgba(34,197,94,0.7)', borderColor: '#22c55e', borderWidth: 1 },
      { label: t('monitor.series-review'), data: s.map(p => p.review || 0), backgroundColor: 'rgba(251,191,36,0.7)', borderColor: '#fbbf24', borderWidth: 1 },
      { label: t('monitor.series-block'), data: s.map(p => p.block || 0), backgroundColor: 'rgba(239,68,68,0.7)', borderColor: '#ef4444', borderWidth: 1 },
      { label: t('monitor.series-challenge'), data: s.map(p => p.challenge || 0), backgroundColor: 'rgba(168,85,247,0.7)', borderColor: '#a855f7', borderWidth: 1 }
    ]
  };
});
const stackedOpts: any = { responsive: true, maintainAspectRatio: false, scales: { x: { stacked: true, ticks: { maxTicksLimit: 5, font: tickFont, maxRotation: 0 } }, y: { stacked: true, beginAtZero: true, ticks: { font: tickFont } } }, plugins: legendOpts, interaction: { mode: 'index', intersect: false } };

const blockRateData = computed<any>(() => {
  const s = overallSeries();
  if (!s.length) return null;
  const line = lineSeries(s, s.map(p => p.total_requests > 0 ? (p.block || 0) / p.total_requests : 0));
  return { labels: line.labels, datasets: [{ label: t('monitor.overall-block-rate'), data: line.values, borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.1)', fill: true, tension: 0.3, spanGaps: true, clip: false, pointRadius: pointRadius(s.length), pointHoverRadius: 8, pointBackgroundColor: '#ef4444' }] };
});
const blockRateOpts: any = { responsive: true, maintainAspectRatio: false, layout: { padding: { top: 12 } }, scales: { x: { ticks: { maxTicksLimit: 5, font: tickFont, maxRotation: 0 } }, y: { beginAtZero: true, grace: '12%', ticks: { callback: (v: any) => fmtPct(v), font: tickFont } } }, plugins: { tooltip: { callbacks: { label: (c: any) => fmtPct(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

const ruleChartData = computed(() => {
  const rs = ruleSeries.value;
  if (!rs.length) return null;
  const rates = lineSeries(rs, rs.map(p => p.total_requests > 0 ? (p.block || 0) / p.total_requests : 0));
  const reqs = lineSeries(rs, rs.map(p => p.total_requests || 0));
  const r = pointRadius(rs.length);
  return {
    labels: rates.labels,
    datasets: [
      { label: t('monitor.rule-block-rate'), data: rates.values, yAxisID: 'y', borderColor: '#ef4444', tension: 0.3, spanGaps: true, clip: false as const, pointRadius: r, pointHoverRadius: 8, pointBackgroundColor: '#ef4444' },
      { label: t('monitor.kpi-total-requests'), data: reqs.values, yAxisID: 'y1', borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.3, spanGaps: true, clip: false as const, pointRadius: r, pointHoverRadius: 8, pointBackgroundColor: '#3b82f6' }
    ]
  };
});
const ruleOpts: any = { responsive: true, maintainAspectRatio: false, layout: { padding: { top: 12 } }, scales: { x: { ticks: { maxTicksLimit: 5, font: tickFont, maxRotation: 0 } }, y: { beginAtZero: true, grace: '12%', position: 'left', ticks: { callback: (v: any) => fmtPct(v), font: tickFont } }, y1: { beginAtZero: true, grace: '12%', position: 'right', grid: { drawOnChartArea: false }, ticks: { font: tickFont } } }, plugins: { tooltip: { callbacks: { label: (c: any) => c.datasetIndex === 0 ? fmtPct(c.parsed.y) : String(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

const SCENE_COLORS = ['#0369A1', '#0EA5E9', '#22C55E', '#F59E0B', '#EF4444', '#A855F7', '#06B6D4', '#14B8A6'];
const sceneStats = computed(() => {
  const rows = [...scenes.value].sort((a, b) => (b.total_requests || 0) - (a.total_requests || 0));
  const total = rows.reduce((sum, r) => sum + (r.total_requests || 0), 0);
  const byScene: Record<string, number> = {};
  const layers = new Set<string>();
  rows.forEach((r) => {
    const sceneName = r.scene || t('monitor.scene-unset');
    byScene[sceneName] = (byScene[sceneName] || 0) + (r.total_requests || 0);
    if (r.layer) layers.add(String(r.layer));
  });
  const ranked = Object.entries(byScene)
    .sort((a, b) => b[1] - a[1])
    .map(([scene, requests], i) => ({
      scene,
      requests,
      share: total > 0 ? requests / total : 0,
      color: SCENE_COLORS[i % SCENE_COLORS.length]
    }));
  const colorByScene = Object.fromEntries(ranked.map((r) => [r.scene, r.color]));
  const detail = rows.map((r) => {
    const sceneName = r.scene || t('monitor.scene-unset');
    return {
      ...r,
      scene: sceneName,
      share: total > 0 ? (r.total_requests || 0) / total : 0,
      color: colorByScene[sceneName] || SCENE_COLORS[0]
    };
  });
  return { total, sceneCount: ranked.length, layerCount: layers.size, topShare: ranked[0]?.share || 0, ranked, detail };
});
const sceneChartH = computed(() => Math.min(320, Math.max(180, sceneStats.value.ranked.length * 36 + 28)));
const sceneBarData = computed(() => {
  const ranked = sceneStats.value.ranked;
  if (!ranked.length) return null;
  return {
    labels: ranked.map((r) => r.scene),
    datasets: [{ data: ranked.map((r) => r.requests), backgroundColor: ranked.map((r) => r.color), borderWidth: 0, borderRadius: 3, barThickness: 16 }]
  };
});
const sceneBarOpts: any = {
  indexAxis: 'y',
  responsive: true,
  maintainAspectRatio: false,
  plugins: {
    legend: { display: false },
    tooltip: {
      callbacks: {
        label: (c: any) => {
          const row = sceneStats.value.ranked[c.dataIndex];
          return `${fmtNum(c.parsed.x)} (${fmtPct(row?.share)})`;
        }
      }
    }
  },
  scales: {
    x: { beginAtZero: true, ticks: { font: tickFont }, grid: { color: '#e2e8f0' } },
    y: { ticks: { font: tickFont }, grid: { display: false } }
  }
};

const degData = computed<any>(() => {
  const s = deg.value?.series || [];
  if (!s.length) return null;
  return { labels: s.map((x: any) => fmtTime(x.bucket)), datasets: [{ label: t('monitor.degradation-title'), data: s.map((x: any) => x.degraded_rate || 0), borderColor: '#f59e0b', backgroundColor: 'rgba(245,158,11,0.1)', fill: true, tension: 0.3, pointRadius: pointRadius(s.length) }] };
});
const degOpts: any = { responsive: true, maintainAspectRatio: false, scales: { x: { ticks: { maxTicksLimit: 5, font: tickFont, maxRotation: 0 } }, y: { beginAtZero: true, ticks: { callback: (v: any) => fmtPct(v), font: tickFont } } }, plugins: { tooltip: { callbacks: { label: (c: any) => fmtPct(c.parsed.y) } }, ...legendOpts }, interaction: { mode: 'index', intersect: false } };

function applyQuery() {
  const qh = Number(route.query.hours);
  if ((HOURS as readonly number[]).includes(qh) && qh !== hours.value) hours.value = qh;
  const qt = String(route.query.tenant || '').trim();
  if (qt && qt !== session.tenant) session.setTenant(qt);
}

function syncQuery() {
  if (syncingQuery) return;
  const nextTenant = session.tenant;
  const nextHours = String(hours.value);
  if (route.query.tenant === nextTenant && route.query.hours === nextHours) return;
  syncingQuery = true;
  router.replace({ query: { ...route.query, tenant: nextTenant, hours: nextHours } }).finally(() => { syncingQuery = false; });
}

async function resolveTenantName() {
  try {
    const data = await adminRoot<any[]>('/tenants');
    const row = (data || []).find((x: any) => (field(x, 'tenant_id', 'tenantId') || '') === session.tenant);
    tenantName.value = field(row, 'name') || '';
  } catch {
    tenantName.value = '';
  }
}

async function load() {
  if (!session.tenant) return;
  await resolveTenantName();
  try {
    const [m, rk, st, dg, ev, ih] = await Promise.all([
      admin<any>('/deploy-rollout/metrics?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/rule-ranking?hours=' + hours.value + '&limit=20').catch(() => null),
      admin<any>('/monitor/scene-traffic?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/degradation?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/event-timeline?hours=' + (hours.value > 48 ? hours.value : 48) + '&limit=20').catch(() => null),
      admin<any>('/audit/ingest-status').then(x => { ingestFailed.value = false; return x; }).catch(() => { ingestFailed.value = true; return null; })
    ]);
    metrics.value = m;
    ranking.value = rk?.ranking || [];
    scenes.value = st?.scenes || [];
    deg.value = dg;
    events.value = ev?.events || [];
    ingest.value = ih;
    lastRefreshAt.value = new Date().toISOString();
    if (allRules.value.length && !allRules.value.includes(selectedRule.value)) {
      selectedRule.value = allRules.value[0];
    }
    await loadRuleSeries();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function loadRuleSeries() {
  const id = selectedRule.value || allRules.value[0];
  if (!id) { ruleSeries.value = []; return; }
  const m = await admin<any>('/rules/' + encodeURIComponent(id) + '/metrics?hours=' + hours.value).catch(() => null);
  const s = (m?.series || []).length ? m.series : (m?.series_1m || []);
  ruleSeries.value = downsample(s, hours.value);
}

function setHours(h: number) { hours.value = h; syncQuery(); load(); }

function downloadText(filename: string, text: string) {
  const blob = new Blob([text], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

async function exportDash() {
  try {
    const [m, rk, st, dg, ev] = await Promise.all([
      admin<any>('/deploy-rollout/metrics?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/rule-ranking?hours=' + hours.value + '&limit=20').catch(() => null),
      admin<any>('/monitor/scene-traffic?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/degradation?hours=' + hours.value).catch(() => null),
      admin<any>('/monitor/event-timeline?hours=48&limit=20').catch(() => null)
    ]);
    const dump = JSON.stringify({
      exportedAt: new Date().toISOString(),
      tenant: session.tenant,
      hours: hours.value,
      metrics: m,
      ranking: rk,
      sceneTraffic: st,
      degradation: dg,
      events: ev
    }, null, 2);
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    downloadText(`monitor-${session.tenant}-${hours.value}h-${stamp}.json`, dump);
    ElMessage.success(t('monitor.export-success'));
  } catch (e: any) { ElMessage.error(t('monitor.export-fail', [e.message])); }
}

onMounted(() => {
  applyQuery();
  syncQuery();
  load();
  timer = setInterval(load, 30000);
});
onUnmounted(() => { if (timer) { clearInterval(timer); timer = null; } });
watch(() => session.tenant, () => { syncQuery(); load(); });
watch(selectedRule, loadRuleSeries);
watch(activeTab, () => { nextTick(() => window.dispatchEvent(new Event('resize'))); });
watch(() => [route.query.tenant, route.query.hours], () => {
  if (syncingQuery) return;
  applyQuery();
});
</script>

<style scoped>
.monitor-page.v-card { padding: 12px; }
.monitor-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 10px;
}
.monitor-head .v-card-title { margin: 0; }
.monitor-head .v-hint { margin: 2px 0 0; }
.monitor-head .v-hint-more { margin: 2px 0 0; }
.monitor-head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}
.monitor-kpis { gap: 6px; margin-bottom: 8px; }
.monitor-kpis :deep(.kpi-card) { padding: 6px 10px; }
.monitor-kpis :deep(.kpi-card .value) { font-size: 16px; }
.monitor-tabs :deep(.el-tabs__header) { margin: 0 0 8px; }
.monitor-tabs :deep(.el-tabs__item) { font-size: 13px; height: 36px; }
.monitor-tabs :deep(.el-tabs__nav-wrap::after) { height: 1px; }
.monitor-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.monitor-grid-single { grid-template-columns: 1fr; }
.monitor-panel {
  min-width: 0;
  border: 1px solid var(--v-border);
  border-radius: 6px;
  padding: 8px 10px 10px;
  background: #fff;
}
.monitor-panel h3 {
  font-size: 12px;
  font-weight: 600;
  margin: 0 0 6px;
  color: #334155;
}
.monitor-panel-wide { grid-column: 1 / -1; }
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.panel-head h3 { margin: 0; }
.rule-select { width: 160px; }
.chart-wrap {
  position: relative;
  width: 100%;
  height: 200px;
  margin: 0;
}
.chart-wrap :deep(canvas) {
  width: 100% !important;
  height: 100% !important;
  max-height: none;
}
.chart-wrap .v-empty-hint {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  padding: 0;
  text-align: center;
}
.chart-wrap-donut { height: 220px; }
.scene-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 280px;
  padding: 32px 20px;
  border: 1px solid var(--v-border);
  border-radius: 6px;
  background: #fff;
  text-align: center;
}
.scene-empty-icon {
  width: 48px;
  height: 48px;
  color: #94a3b8;
  margin-bottom: 12px;
}
.scene-empty-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}
.scene-empty-desc {
  margin: 0;
  max-width: 360px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--v-muted);
}
.scene-board { display: flex; flex-direction: column; gap: 8px; }
.scene-kpis { margin-bottom: 0; }
.scene-split {
  display: grid;
  grid-template-columns: minmax(240px, 42%) 1fr;
  gap: 8px;
  align-items: stretch;
}
.scene-swatch {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 6px;
  vertical-align: middle;
}
.share-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.share-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: #e2e8f0;
  overflow: hidden;
}
.share-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
}
.ingest-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
}
.ingest-grid dt {
  font-size: 11px;
  color: var(--v-muted);
  margin-bottom: 2px;
}
.ingest-grid dd {
  margin: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--v-text);
  word-break: break-all;
}
@media (max-width: 900px) {
  .monitor-head { flex-direction: column; }
  .monitor-grid { grid-template-columns: 1fr; }
  .scene-split, .ingest-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) {
  .monitor-page :deep(*) { transition: none !important; }
}
</style>
