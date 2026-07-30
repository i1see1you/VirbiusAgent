<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('rollout.title') }}</h2>
    <p class="v-hint" v-html="t('rollout.desc')"></p>

    <!-- Machine canary deploy -->
    <div class="v-section">
      <h3>{{ t('rollout.machine-deploy-title') }}</h3>
      <p class="v-hint" v-html="t('rollout.machine-deploy-desc')"></p>
      <div class="kpi-grid" id="drStatusBar">
        <div class="kpi-card"><div class="label">{{ t('dr.status') }}</div><div class="value" style="font-size:14px">{{ active ? active.state : t('dr.no-active') }}</div></div>
        <div class="kpi-card"><div class="label">{{ t('dr.canary') }}</div><div class="value" style="font-size:14px">{{ active ? (active.canary_percent || 0) + '%' : '-' }}</div></div>
        <div class="kpi-card"><div class="label">{{ t('dr.bundle') }}</div><div class="value" style="font-size:14px">{{ active ? (active.bundle_id || '-') : '-' }}</div></div>
      </div>
      <div class="v-row" style="flex-wrap:wrap;gap:6px">
        <el-input v-model="drDescription" :placeholder="t('rollout.desc-placeholder')" style="width:240px" />
        <el-button :disabled="!!active" @click="openVersionModal('cloud', t('dr.prepare-engine'))">{{ t('rollout.btn-prepare-engine') }}</el-button>
        <el-button :disabled="!!active" @click="openVersionModal('gateway', t('dr.prepare-gateway'))">{{ t('rollout.btn-prepare-gateway') }}</el-button>
        <el-button :disabled="!!active" @click="openVersionModal('edge', t('dr.prepare-edge'))">{{ t('rollout.btn-prepare-edge') }}</el-button>
        <el-button :disabled="!!active" style="background:#9333ea;color:#fff;border-color:#9333ea" @click="openVersionModal('falco', t('dr.prepare-falco'))">{{ t('rollout.btn-prepare-falco') }}</el-button>
        <el-button :disabled="!!active" style="background:#6366f1;color:#fff;border-color:#6366f1" @click="openVersionModal('', t('dr.prepare-all'))">{{ t('rollout.btn-prepare-all') }}</el-button>
        <el-button :disabled="!canUpgrade" @click="drUpgrade">{{ t('rollout.btn-upgrade') }}</el-button>
        <el-button :disabled="!canPause" @click="drPause">{{ t('rollout.btn-pause') }}</el-button>
        <el-button :disabled="!canRollback" type="danger" @click="drRollback">{{ t('rollout.btn-rollback') }}</el-button>
        <el-button :disabled="!canFinalize" @click="drFinalize">{{ t('rollout.btn-finalize') }}</el-button>
        <el-button @click="drRefresh">{{ t('rollout.btn-refresh') }}</el-button>
      </div>

      <div v-if="active" style="margin-top:12px">
        <el-table :data="[active]" size="small" border stripe>
          <el-table-column :label="t('rollout.header-id')" prop="deploy_id" />
          <el-table-column :label="t('rollout.header-status')"><template #default="{ row }"><el-tag size="small">{{ row.state }}</el-tag></template></el-table-column>
          <el-table-column :label="t('rollout.header-bundle')" prop="bundle_id" />
          <el-table-column :label="t('rollout.header-canary')"><template #default="{ row }">{{ (row.canary_percent || 0) + '%' }}</template></el-table-column>
          <el-table-column :label="t('rollout.header-engine-canary')" prop="canary_engine_revision" />
          <el-table-column :label="t('rollout.header-engine-stable')" prop="stable_engine_revision" />
          <el-table-column :label="t('rollout.header-gw-canary')" prop="canary_gateway_revision" />
          <el-table-column :label="t('rollout.header-gw-stable')" prop="stable_gateway_revision" />
          <el-table-column :label="t('rollout.header-edge-canary')" prop="canary_edge_revision" />
          <el-table-column :label="t('rollout.header-edge-stable')" prop="stable_edge_revision" />
          <el-table-column :label="t('rollout.header-operator')" prop="operator" />
          <el-table-column :label="t('rollout.header-notes')" prop="note" />
        </el-table>
        <h4 style="font-size:13px;margin:12px 0 6px">{{ t('rollout.node-dist') }}</h4>
        <div id="drNodeDistribution" class="kpi-grid" style="margin-bottom:12px">
          <div v-if="!active.pool_distribution" class="kpi-card" style="grid-column:1/-1"><div class="value">{{ t('rollout.no-active-deploy') }}</div></div>
          <template v-else>
            <div v-for="(pools, layer) in active.pool_distribution" :key="layer" class="kpi-card">
              <div class="label">{{ layer }}</div>
              <div class="value">{{ t('dr.nodes', [Object.values(pools).reduce((a:any,b:any)=>a+b, 0)]) }}</div>
              <span v-for="(cnt, pool) in pools" :key="String(pool)" class="tag" :style="String(pool)==='canary'?'background:#16a34a;color:#fff':''">{{ pool }}: {{ cnt }}</span>
            </div>
          </template>
        </div>
        <h4 style="font-size:13px;margin:12px 0 6px">{{ t('rollout.block-rate-chart') }}</h4>
        <div class="chart-wrap" style="margin-bottom:12px">
          <Line v-if="drChartData" :data="drChartData" :options="drChartOpts" />
          <p v-else class="v-hint">{{ t('rollout.no-metrics') }}</p>
        </div>
        <h4 style="font-size:13px;margin:12px 0 6px">{{ t('rollout.events') }}</h4>
        <el-table :data="active.events || []" size="small" border stripe>
          <el-table-column :label="t('rollout.header-time')"><template #default="{ row }">{{ fmtTime(row.created_at) }}</template></el-table-column>
          <el-table-column :label="t('rollout.header-type')"><template #default="{ row }"><el-tag size="small">{{ row.event_type }}</el-tag></template></el-table-column>
          <el-table-column :label="t('rollout.header-from')"><template #default="{ row }">{{ row.from_state }}{{ row.from_percent != null ? '@' + row.from_percent + '%' : '' }}</template></el-table-column>
          <el-table-column :label="t('rollout.header-to')"><template #default="{ row }">{{ row.to_state }}{{ row.to_percent != null ? '@' + row.to_percent + '%' : '' }}</template></el-table-column>
          <el-table-column :label="t('rollout.header-desc')"><template #default="{ row }">{{ row.note || row.reason || '' }}</template></el-table-column>
          <el-table-column :label="t('rollout.header-operator2')" prop="operator" />
        </el-table>
      </div>
      <div v-else class="v-hint">{{ t('rollout.no-active-deploy') }}</div>

      <h4 style="font-size:13px;margin:12px 0 6px">{{ t('rollout.history') }}</h4>
      <el-table :data="history" size="small" border stripe>
        <el-table-column :label="t('rollout.header-id')" prop="deploy_id" />
        <el-table-column :label="t('rollout.header-bundle')" prop="bundle_id" />
        <el-table-column :label="t('rollout.header-status')"><template #default="{ row }"><el-tag size="small">{{ row.state }}</el-tag></template></el-table-column>
        <el-table-column :label="t('rollout.header-start')"><template #default="{ row }">{{ fmtTime(row.started_at) }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-end')"><template #default="{ row }">{{ fmtTime(row.finalized_at) }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-operator')" prop="operator" />
      </el-table>
    </div>

    <!-- Deploy status bar -->
    <div class="v-section">
      <h3>{{ t('rollout.status-deploy') }}</h3>
      <div id="deployStatusBar" class="kpi-grid">
        <div v-for="(st, layer) in deployStatus" :key="layer" class="kpi-card">
          <div class="label">
            <span :style="{ display:'inline-block', width:'8px', height:'8px', borderRadius:'50%', background: st.has_unpublished ? '#ef4444' : '#22c55e', marginRight:'4px' }"></span>
            {{ layerLabels[layer] || layer }}
          </div>
          <div class="value" style="font-size:0.82rem">{{ deployStatusText(st) }}</div>
          <div style="font-size:0.72rem;color:#94a3b8">{{ st.deployed_at ? fmtTimeAgo(st.deployed_at) : '-' }}</div>
          <div v-if="st.has_unpublished && st.pending_rules?.length" style="font-size:0.72rem;color:#64748b;margin-top:2px">
            <template v-for="(ids, grp) in groupPending(st.pending_rules)" :key="grp">
              <span style="font-size:0.68rem;color:#64748b;margin-right:0.25rem">[{{ pendingLabel(grp) }}]</span>
              <code style="font-size:0.7rem" v-for="id in ids.slice(0,2)" :key="id">{{ id }}</code>
              <span v-if="ids.length > 2" style="font-size:0.68rem;color:#64748b">{{ t('rollout.et', ids.length) }}</span>
              &nbsp;
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Rule rollout dashboard -->
    <div class="v-row" style="margin-top:16px">
      <label>{{ t('rollout.header-rule') }}
        <el-select v-model="ruleId" style="width:280px" @change="refreshDashboard">
          <el-option value="" :label="t('rollout.select-rule')" />
          <el-option v-for="r in allRules" :key="r.rule_id" :value="r.rule_id" :label="`${r.rule_id} [${r.layer}] ${stateLabel(r.rollout_state, r.canary_percent)}`" />
        </el-select>
      </label>
      <el-button @click="refreshDashboard">{{ t('rollout.refresh-dashboard') }}</el-button>
      <span class="v-hint" style="margin:0">{{ t('rollout.auto-refresh') }}</span>
      <span v-if="roMeta" id="roBadge">
        <el-tag :type="roTagType" size="small">{{ roMeta.rollout_state || 'draft' }}</el-tag>
        <el-tag v-if="roMeta.canary_percent != null" size="small">{{ roMeta.canary_percent }}%</el-tag>
        <span class="v-hint" style="margin-left:4px">{{ roMeta.layer }}/{{ roMeta.runtime }}</span>
      </span>
    </div>

    <div class="flow-strip">
      <template v-for="(step, i) in flowSteps" :key="step">
        <span v-if="i > 0" class="flow-arrow">-></span>
        <span class="flow-step" :class="flowClass(step)">{{ flowLabel(step) }}</span>
      </template>
      <span v-if="roMeta?.rollout_state === 'disabled'" class="flow-arrow">|</span>
      <span v-if="roMeta?.rollout_state === 'disabled'" class="flow-step active">{{ t('rollout.btn-disable') }}</span>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-review-24h') }}</div><div class="value">{{ roTotals.review ?? 0 }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-block-24h') }}</div><div class="value">{{ roTotals.block ?? 0 }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-challenge-24h') }}</div><div class="value">{{ roTotals.challenge ?? 0 }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-total-requests') }}</div><div class="value">{{ roTotals.total_requests ?? 0 }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-hit-rate') }}</div><div class="value">{{ fmtPct(roTotals.hit_rate) }}</div></div>
      <div class="kpi-card"><div class="label">{{ t('rollout.kpi-review-rate') }}</div><div class="value">{{ fmtPct(roTotals.review_rate) }}</div></div>
    </div>

    <div class="v-section">
      <h3>{{ t('rollout.hourly-metrics') }}</h3>
      <div class="big-kpi-card"><div class="label">{{ t('rollout.block-rate-24h') }}</div><div class="value" :class="rateCls">{{ fmtPct(roTotals.block_rate) }}</div></div>
      <div class="chart-wrap"><Line v-if="combinedData" :data="combinedData" :options="combinedOpts" /></div>
    </div>

    <div class="v-section">
      <h3>{{ t('rollout.timeline') }}</h3>
      <el-table :data="timeline" size="small" border stripe>
        <el-table-column :label="t('rollout.header-time')"><template #default="{ row }">{{ fmtTime(row.effective_at) }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-status')"><template #default="{ row }"><el-tag size="small">{{ row.rollout_state }}</el-tag></template></el-table-column>
        <el-table-column :label="t('rollout.header-canary')"><template #default="{ row }">{{ row.canary_percent ?? '-' }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-trigger')" prop="trigger" />
        <el-table-column :label="t('rollout.header-operator')" prop="operator" />
        <el-table-column :label="t('rollout.header-rev')" prop="rule_revision" />
      </el-table>
    </div>

    <div class="v-section">
      <h3>{{ t('rollout.upgrade-title') }}</h3>
      <div class="v-row" style="flex-wrap:wrap;align-items:center;gap:8px">
        <el-button v-if="canRoApply" type="primary" @click="roApply">{{ t('rollout.btn-next') }}</el-button>
        <el-checkbox v-model="roForce">{{ t('rollout.force-bypass') }}</el-checkbox>
        <el-input v-model="roForceComment" :placeholder="t('rollout.placeholder-force')" style="width:260px" />
      </div>
      <p class="v-hint">{{ roHint }}</p>
    </div>

    <div class="v-section">
      <h3>{{ t('rollout.actions-title') }}</h3>
      <div class="v-row">
        <el-button v-if="roMeta?.rollout_state === 'draft'" @click="roAction('publish')">{{ t('rollout.btn-publish') }}</el-button>
        <el-button v-if="roExec && roMeta?.rollout_state !== 'dry_run'" @click="roAction('rollback')">{{ t('rollout.btn-rollback2') }}</el-button>
        <el-button v-if="roMeta && roMeta.rollout_state !== 'disabled'" type="danger" @click="roAction('disable')">{{ t('rollout.btn-disable') }}</el-button>
        <el-button v-if="roMeta?.rollout_state === 'disabled'" @click="roAction('recover')">{{ t('rollout.btn-recover') }}</el-button>
        <el-button v-if="roExec && roMeta?.rollout_state !== 'full'" @click="roLadder('start')">{{ t('rollout.btn-ladder-start') }}</el-button>
        <el-button v-if="roExec" @click="roLadder('pause')">{{ t('rollout.btn-ladder-pause') }}</el-button>
      </div>
    </div>

    <div class="v-section">
      <h3>{{ t('rollout.audit-samples') }}</h3>
      <el-table :data="samples" size="small" border stripe>
        <el-table-column :label="t('rollout.header-time')"><template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template></el-table-column>
        <el-table-column label="trace_id"><template #default="{ row }"><el-button v-if="row.trace_id" link type="primary" @click="openTrace(row.trace_id)"><code>{{ row.trace_id }}</code></el-button></template></el-table-column>
        <el-table-column :label="t('rollout.header-action')" prop="effective_action" />
        <el-table-column :label="t('rollout.header-reason')" prop="reason_code" />
        <el-table-column :label="t('rollout.header-risk')" prop="max_risk_score" />
        <el-table-column :label="t('rollout.header-rollout')"><template #default="{ row }">{{ stateLabel(row.rollout_state, row.canary_percent) }}</template></el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="versionModalVisible" :title="t('dr.version-modal-title')" width="520px">
      <p class="v-hint">{{ t('dr.version-modal-desc') }}</p>
      <el-input v-model="versionInput" style="margin-bottom:8px" />
      <p class="v-hint">{{ t('dr.version-modal-hint') }}</p>
      <div v-if="diffSummary" class="v-hint" style="font-weight:600">{{ diffSummary }}</div>
      <div v-for="d in diffList" :key="d.rule_id" style="font-size:12px;display:flex;gap:6px;align-items:center;padding:2px 0">
        <el-tag size="small" :type="d.change === 'added' ? 'success' : d.change === 'removed' ? 'danger' : 'warning'">{{ d.change }}</el-tag>
        <span>{{ d.rule_id }}</span>
        <el-tag size="small" type="info">{{ d.layer }}</el-tag>
      </div>
      <template #footer>
        <el-button @click="versionModalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="confirmPrepare">{{ t('dr.btn-confirm-deploy') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="traceModalVisible" :title="t('rollout.trace-detail')" width="780px">
      <p class="v-hint">{{ traceHint }}</p>
      <el-table :data="traceRows" size="small" border stripe>
        <el-table-column :label="t('rollout.header-time')"><template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-layer')" prop="layer" />
        <el-table-column :label="t('rollout.header-action')" prop="effective_action" />
        <el-table-column label="rule"><template #default="{ row }">{{ row.rule_id }}@{{ row.rule_revision ?? '' }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-rollout')"><template #default="{ row }">{{ stateLabel(row.rollout_state, row.canary_percent) }}</template></el-table-column>
        <el-table-column :label="t('rollout.header-risk')" prop="max_risk_score" />
      </el-table>
      <template #footer><el-button @click="traceModalVisible = false">{{ t('rollout.btn-close') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler } from 'chart.js';
import { Line } from 'vue-chartjs';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field, fmtTime, fmtTimeAgo, parseUtc, inExecutionPlane } from '@/utils/format';

ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Tooltip, Legend, Filler);

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const FLOW_STEPS = ['draft', 'dry_run', 'canary', 'full'];
const active = ref<any>(null);
const history = ref<any[]>([]);
const drDescription = ref('');
const versionModalVisible = ref(false);
const versionInput = ref('');
const pendingPrepare = ref<{ layer: string; label: string } | null>(null);
const diffSummary = ref('');
const diffList = ref<any[]>([]);

const ruleId = ref('');
const allRules = ref<any[]>([]);
const roMeta = ref<any>(null);
const roTotals = ref<any>({});
const timeline = ref<any[]>([]);
const samples = ref<any[]>([]);
const roForce = ref(false);
const roForceComment = ref('');
const canaryLadder = ref([5, 20, 50, 100]);
const combinedData = ref<any>(null);
const traceModalVisible = ref(false);
const traceHint = ref('');
const traceRows = ref<any[]>([]);
const deployStatus = ref<any>({});
const drChartData = ref<any>(null);
const layerLabels: Record<string, string> = { cloud: 'cloud', gateway: 'gateway', edge: 'edge' };
let timer: any = null;

const drState = computed(() => (active.value?.state || '').toLowerCase());
const canUpgrade = computed(() => active.value && ['pending', 'canary', 'paused'].includes(drState.value));
const canPause = computed(() => active.value && drState.value === 'canary');
const canRollback = computed(() => active.value && !['finalized', 'rolled_back'].includes(drState.value));
const canFinalize = computed(() => active.value && (active.value.canary_percent || 0) === 100);
const roExec = computed(() => roMeta.value && inExecutionPlane(roMeta.value.rollout_state));
const canRoApply = computed(() => roMeta.value && !['draft', 'disabled'].includes(roMeta.value.rollout_state) && roMeta.value.rollout_state !== 'full');
const rateCls = computed(() => {
  const r = roTotals.value.block_rate;
  if (r == null) return '';
  if (r < 0.01) return 'rate-low'; if (r < 0.05) return 'rate-mid'; return 'rate-high';
});
const roTagType = computed(() => {
  const s = roMeta.value?.rollout_state;
  if (s === 'disabled') return 'danger';
  if (s === 'draft') return 'warning';
  if (s === 'full') return 'success';
  return 'info';
});
const flowSteps = computed(() => FLOW_STEPS);
const roHint = computed(() => {
  if (!roMeta.value) return t('rollout.select-rule-hint');
  const st = roMeta.value.rollout_state;
  if (st === 'draft') return t('rollout.draft-hint');
  if (st === 'disabled') return t('rollout.disabled-hint');
  if (st === 'full') return t('rollout.full-hint');
  const target = evaluateTarget(roMeta.value);
  if (!target) return t('rollout.no-target-hint');
  return t('rollout.next-step-hint', [stateLabel(target.target_state, target.canary_percent)]);
});

function fmtPct(n: any) { if (n == null || isNaN(n)) return 'N/A'; return (n * 100).toFixed(2) + '%'; }
function stateLabel(st: string, pct: any) { return st === 'canary' && pct != null ? `canary@${pct}%` : (st || 'draft'); }
function deployStatusText(st: any) {
  if (!st.deployed_at) {
    const cnt = st.pending_rules?.length || 0;
    return cnt > 0 ? t('rollout.not-deployed-count', [cnt]) : t('rollout.not-deployed');
  }
  if (st.has_unpublished) return t('rollout.pending-deploy', [st.pending_rules?.length || 0]);
  return t('rollout.synced');
}
function groupPending(rules: any[]) {
  const groups: Record<string, string[]> = {};
  (rules || []).forEach((r: any) => {
    const g = r.rollout_state || 'unknown';
    if (!groups[g]) groups[g] = [];
    groups[g].push(r.rule_id);
  });
  return groups;
}
function pendingLabel(g: string) {
  const m: Record<string, string> = { dry_run: t('ro-deploy.pending'), canary: t('ro-deploy.canary'), full: t('ro-deploy.full'), disabled: t('ro-deploy.disabled') };
  return m[g] || g;
}

function flowClass(step: string) {
  if (!roMeta.value) return step === 'draft' ? 'active' : '';
  const st = roMeta.value.rollout_state;
  const cur = st === 'disabled' ? 'draft' : (st || 'draft');
  const order: any = { draft: 0, dry_run: 1, canary: 2, full: 3 };
  const idx = order[cur] ?? 0;
  const i = FLOW_STEPS.indexOf(step);
  if (step === cur) return 'active';
  if (i < idx) return 'done';
  return '';
}
function flowLabel(step: string) {
  if (step === 'canary' && roMeta.value?.rollout_state === 'canary' && roMeta.value?.canary_percent != null) return `canary ${roMeta.value.canary_percent}%`;
  return step;
}

function evaluateTarget(meta: any) {
  if (!meta) return null;
  const st = meta.rollout_state;
  if (st === 'dry_run') return { target_state: 'canary', canary_percent: canaryLadder.value[0] };
  if (st === 'canary') {
    const idx = canaryLadder.value.indexOf(meta.canary_percent != null ? meta.canary_percent : canaryLadder.value[0]);
    const next = idx < 0 ? 0 : idx + 1;
    if (next >= canaryLadder.value.length) return null;
    const step = canaryLadder.value[next];
    if (step >= 100) return { target_state: 'full', canary_percent: null };
    return { target_state: 'canary', canary_percent: step };
  }
  return null;
}

const combinedOpts: any = {
  responsive: true, maintainAspectRatio: false, spanGaps: true,
  interaction: { mode: 'index', intersect: false },
  scales: {
    y: { type: 'linear', position: 'left', title: { display: true, text: 'total_requests' } },
    y1: { type: 'linear', position: 'right', title: { display: true, text: 'action count' }, grid: { drawOnChartArea: false } }
  },
  plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 12, font: { size: 10 } } } }
};

const drChartOpts: any = {
  responsive: true, maintainAspectRatio: false, spanGaps: true,
  interaction: { mode: 'index', intersect: false },
  scales: {
    y: { type: 'linear', position: 'left', title: { display: true, text: 'block_rate %' }, min: 0, ticks: { callback: (v: any) => v + '%' } },
    y1: { type: 'linear', position: 'right', title: { display: true, text: 'total_requests' }, grid: { drawOnChartArea: false } }
  },
  plugins: { legend: { position: 'bottom', labels: { boxWidth: 12, padding: 12, font: { size: 10 } } } }
};

function renderDrBlockRateChart(metrics: any) {
  if (!metrics) { drChartData.value = null; return; }
  const series = metrics.series || [];
  const series1m = metrics.series_1m || [];
  const cutoff = Date.now() - 2 * 3600 * 1000;
  const hourPoints = series.filter((p: any) => { const d = parseUtc(p.bucket); return d && d.getTime() < cutoff; });
  const merged = hourPoints.concat(series1m).sort((a: any, b: any) => (parseUtc(a.bucket)?.getTime() ?? 0) - (parseUtc(b.bucket)?.getTime() ?? 0));
  if (!merged.length) { drChartData.value = null; return; }
  const labels = merged.map((p: any) => { const d = parseUtc(p.bucket); return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''; });
  drChartData.value = {
    labels,
    datasets: [
      { label: 'block_rate (%)', data: merged.map((p: any) => { const t = p.total_requests || 0; return t > 0 ? ((p.block || 0) / t * 100) : null; }), yAxisID: 'y', borderColor: '#ef4444', backgroundColor: 'rgba(239,68,68,0.1)', fill: true, tension: 0.2, pointRadius: 0 },
      { label: 'total_requests', data: merged.map((p: any) => p.total_requests || 0), yAxisID: 'y1', borderColor: '#94a3b8', backgroundColor: 'rgba(148,163,184,0.08)', fill: true, tension: 0.2, pointRadius: 0 }
    ]
  };
}

async function drRefresh() {
  try {
    const [act, list, overview, drMetrics] = await Promise.all([
      admin<any>('/deploy-rollout/active').catch(() => null),
      admin<any[]>('/deploy-rollout/list').catch(() => []),
      admin<any>('/dashboard/overview').catch(() => ({ deploy_status: {} })),
      admin<any>('/deploy-rollout/metrics?hours=24').catch(() => null)
    ]);
    active.value = act && act.deploy_id ? act : null;
    history.value = list || [];
    deployStatus.value = (overview as any)?.deploy_status || {};
    renderDrBlockRateChart(drMetrics);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function openVersionModal(layer: string, label: string) {
  pendingPrepare.value = { layer, label };
  versionInput.value = '';
  diffSummary.value = ''; diffList.value = [];
  versionModalVisible.value = true;
  try {
    const bid = session.bundleId;
    const [ver, diff] = await Promise.all([
      admin<any>('/deploy-rollout/next-version?bundle_id=' + encodeURIComponent(bid)).catch(() => null),
      admin<any>('/deploy-rollout/diff-rules?bundle_id=' + encodeURIComponent(bid) + (layer ? '&layer=' + encodeURIComponent(layer) : '')).catch(() => null)
    ]);
    if (ver?.version) versionInput.value = ver.version;
    if (diff) {
      const s = diff.summary || { added: 0, removed: 0, modified: 0 };
      const parts = [];
      if (s.added > 0) parts.push(t('dr.diff-added') + s.added);
      if (s.removed > 0) parts.push(t('dr.diff-removed') + s.removed);
      if (s.modified > 0) parts.push(t('dr.diff-modified') + s.modified);
      diffSummary.value = t('dr.diff-based-on', [diff.base_version || t('dr.first-release')]) + (parts.length ? ': ' + parts.join(', ') : t('dr.diff-no-changes'));
      const rows: any[] = [];
      Object.entries(diff.layers || {}).forEach(([ly, rules]: any) => rules.forEach((r: any) => rows.push({ ...r, layer: r.layer || ly })));
      diffList.value = rows;
    }
  } catch { /* ignore */ }
}

async function confirmPrepare() {
  if (!pendingPrepare.value) return;
  const { layer, label } = pendingPrepare.value;
  try {
    const data = await admin<any>('/deploy-rollout/prepare', { method: 'POST', body: JSON.stringify({ bundle_id: session.bundleId, bundle_version: versionInput.value.trim() || undefined, layer, description: drDescription.value.trim() }) });
    feedback.log(t('dr.prepared-ok', [label, data.deploy_id, data.bundle_id || 'auto']), 'ok');
    versionModalVisible.value = false;
    await drRefresh();
  } catch (e: any) { feedback.log(t('dr.prepare-fail', [e.message]), 'err'); }
}

async function withActiveDid(fn: (did: string) => Promise<any>) {
  const act = await admin<any>('/deploy-rollout/active').catch(() => null);
  const did = act?.deploy_id;
  if (!did) { feedback.log(t('dr.no-active-deploy'), 'err'); return; }
  await fn(did);
}
async function drUpgrade() { try { await withActiveDid(async did => { const r = await admin('/deploy-rollout/' + did + '/upgrade', { method: 'POST', body: JSON.stringify({ note: 'UI upgrade' }) }); feedback.log(t('dr.upgrade-ok', [r.state, r.canary_percent]), 'ok'); }); await drRefresh(); } catch (e: any) { feedback.log(t('dr.upgrade-fail', [e.message]), 'err'); } }
async function drPause() { try { await withActiveDid(async did => { await admin('/deploy-rollout/' + did + '/pause', { method: 'POST', body: JSON.stringify({ note: 'UI pause' }) }); feedback.log(t('dr.paused-ok'), 'ok'); }); await drRefresh(); } catch (e: any) { feedback.log(t('dr.pause-fail', [e.message]), 'err'); } }
async function drRollback() { try { await ElMessageBox.confirm(t('dr.rollback-confirm'), { type: 'warning' }); } catch { return; } try { await withActiveDid(async did => { await admin('/deploy-rollout/' + did + '/rollback', { method: 'POST', body: JSON.stringify({ note: 'UI rollback' }) }); feedback.log(t('dr.rolled-back-ok'), 'ok'); }); await drRefresh(); } catch (e: any) { feedback.log(t('dr.rollback-fail', [e.message]), 'err'); } }
async function drFinalize() { try { await ElMessageBox.confirm(t('dr.finalize-confirm'), { type: 'warning' }); } catch { return; } try { await withActiveDid(async did => { await admin('/deploy-rollout/' + did + '/finalize', { method: 'POST', body: JSON.stringify({ note: 'UI finalize' }) }); feedback.log(t('dr.finalized-ok'), 'ok'); }); await drRefresh(); } catch (e: any) { feedback.log(t('dr.finalize-fail', [e.message]), 'err'); } }

async function loadAllRules() {
  const layers = ['cloud', 'gateway', 'edge'];
  const all: any[] = [];
  for (const layer of layers) {
    try { const rs = await admin<any[]>('/rules?layer=' + layer); rs.forEach(r => all.push(r)); } catch { /* ignore */ }
  }
  allRules.value = all.sort((a, b) => a.rule_id.localeCompare(b.rule_id));
}

function renderCombinedChart(series: any[], series1m: any[]) {
  const cutoff = Date.now() - 2 * 3600 * 1000;
  const hourPoints = (series || []).filter(p => { const d = parseUtc(p.bucket); return d && d.getTime() < cutoff; });
  const merged = hourPoints.concat(series1m || []).sort((a, b) => (parseUtc(a.bucket)?.getTime() ?? 0) - (parseUtc(b.bucket)?.getTime() ?? 0));
  if (!merged.length) { combinedData.value = null; return; }
  const labels = merged.map(p => { const d = parseUtc(p.bucket); return d ? d.toLocaleString(undefined, { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''; });
  combinedData.value = { labels, datasets: [
    { label: 'total_requests', data: merged.map(p => p.total_requests ?? 0), yAxisID: 'y', borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.1)', fill: true, tension: 0.2, pointRadius: 0 },
    { label: 'review', data: merged.map(p => p.review ?? 0), yAxisID: 'y1', borderColor: '#fbbf24', tension: 0.2, pointRadius: 0 },
    { label: 'block', data: merged.map(p => p.block ?? 0), yAxisID: 'y1', borderColor: '#ef4444', tension: 0.2, pointRadius: 0 },
    { label: 'challenge', data: merged.map(p => p.challenge ?? 0), yAxisID: 'y1', borderColor: '#a855f7', tension: 0.2, pointRadius: 0 },
    { label: 'allow', data: merged.map(p => p.allow ?? 0), yAxisID: 'y1', borderColor: '#22c55e', tension: 0.2, pointRadius: 0 }
  ]};
}

async function refreshDashboard() {
  await loadAllRules();
  try { const pol = await admin<any>('/rollout-policy').catch(() => null); if (pol?.canary_ladder?.length) canaryLadder.value = pol.canary_ladder; } catch { /* ignore */ }
  if (!ruleId.value) {
    roMeta.value = null; roTotals.value = {}; timeline.value = []; samples.value = []; combinedData.value = null;
    return;
  }
  try {
    const meta = await admin<any>('/rules/' + encodeURIComponent(ruleId.value));
    roMeta.value = meta;
    const [metrics, tl, sp] = await Promise.all([
      admin<any>('/rules/' + encodeURIComponent(ruleId.value) + '/metrics?hours=24').catch(() => ({ series: [], totals: {} })),
      admin<any[]>('/rules/' + encodeURIComponent(ruleId.value) + '/rollout/timeline').catch(() => []),
      admin<any[]>('/rules/' + encodeURIComponent(ruleId.value) + '/audit-samples?limit=30').catch(() => [])
    ]);
    roTotals.value = metrics.totals || {};
    renderCombinedChart(metrics.series || [], metrics.series_1m || []);
    timeline.value = tl || [];
    samples.value = sp || [];
  } catch (e: any) { feedback.log(e.message, 'err'); }
  await drRefresh();
}

async function roApply() {
  if (!ruleId.value || !roMeta.value) return;
  const target = evaluateTarget(roMeta.value);
  if (!target) { feedback.log(t('rollout.no-next-target'), 'warn'); return; }
  if (roForce.value && !roForceComment.value.trim()) { feedback.log(t('rollout.force-comment-required'), 'warn'); return; }
  const rolloutState = target.target_state;
  const canaryPercent = rolloutState === 'full' ? null : target.canary_percent;
  const body = { rollout_state: rolloutState, canary_percent: canaryPercent, force: roForce.value, comment: roForce.value ? roForceComment.value.trim() : null };
  try {
    await admin('/rules/' + encodeURIComponent(ruleId.value) + '/rollout', { method: 'PATCH', body: JSON.stringify(body) });
    await refreshDashboard();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function roAction(kind: string) {
  if (!ruleId.value) return;
  try { await admin('/rules/' + encodeURIComponent(ruleId.value) + '/rollout/' + kind, { method: 'POST' }); await refreshDashboard(); }
  catch (e: any) { feedback.log(e.message, 'err'); }
}
async function roLadder(kind: string) {
  if (!ruleId.value) return;
  try { await admin('/rules/' + encodeURIComponent(ruleId.value) + '/rollout/ladder/' + kind, { method: 'POST' }); }
  catch (e: any) { feedback.log(e.message, 'err'); }
}

async function openTrace(traceId: string) {
  traceModalVisible.value = true;
  traceHint.value = t('rollout.trace-hint', [traceId]);
  traceRows.value = [];
  try {
    const detail = await admin<any>('/audit/trace/' + encodeURIComponent(traceId));
    traceRows.value = (detail.db_events || []).sort((a: any, b: any) => String(a.intercepted_at || '').localeCompare(String(b.intercepted_at || '')));
    if (!traceRows.value.length) traceHint.value = t('rollout.no-audit-records');
  } catch (e: any) { traceHint.value = e.message; }
}

onMounted(() => { refreshDashboard(); timer = setInterval(refreshDashboard, 5000); });
onUnmounted(() => { if (timer) clearInterval(timer); });
watch(() => session.tenant, refreshDashboard);


</script>
