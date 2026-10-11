<template>
  <div class="v-card">
    <div class="integrate-title-row">
      <h2 class="v-card-title">{{ t('integrate.title') }}</h2>
      <el-tooltip placement="top" :show-after="200" :z-index="4200" popper-class="integrate-help-popper">
        <template #content>
          <p>{{ t('integrate.desc-short') }}</p>
          <p v-html="t('integrate.desc')"></p>
        </template>
        <button type="button" class="integrate-help" :aria-label="t('common.learn-more')">?</button>
      </el-tooltip>
    </div>

    <div class="v-row">
      <el-select v-model="appId" filterable :placeholder="t('integrate.app')" style="width:260px" @change="reloadAll">
        <el-option v-for="a in apps" :key="a.app_id" :label="a.app_id" :value="a.app_id" />
      </el-select>
      <el-button type="primary" @click="reloadAll">{{ t('integrate.refresh') }}</el-button>
      <span class="v-hint">{{ t('integrate.advisory-note') }}</span>
    </div>

    <div v-if="!appId" class="v-hint" style="margin:12px 0">{{ t('integrate.need-app') }}</div>

    <el-tabs v-else v-model="tab">
      <el-tab-pane :label="t('integrate.tab-timeline')" name="timeline">
        <div class="v-row">
          <el-select v-model="rangeHours" style="width:140px" @change="loadObservations">
            <el-option :value="1" :label="t('integrate.range-1h')" />
            <el-option :value="24" :label="t('integrate.range-24h')" />
            <el-option :value="168" :label="t('integrate.range-7d')" />
          </el-select>
          <el-button size="small" @click="loadObservations">{{ t('integrate.refresh') }}</el-button>
          <span class="v-hint">{{ t('integrate.timeline-hint') }}</span>
        </div>

        <div v-if="!calls.length" class="v-hint" style="margin:12px 0">{{ t('integrate.empty') }}</div>
        <div v-for="call in calls" :key="call.call_id" class="integrate-call">
          <div class="integrate-call-head">
            <strong>{{ call.tool_name || t('integrate.unknown-tool') }}</strong>
            <el-tag size="small" :type="statusType(call.status)">{{ call.status }}</el-tag>
            <span class="integrate-meta">· {{ call.device_id || '-' }} · {{ call.session_id || '-' }}</span>
            <span v-if="call.duration_ms != null" class="integrate-meta">· {{ call.duration_ms }}ms</span>
            <span class="integrate-meta integrate-right">{{ fmtTime(call.started_at || call.observed_at) }}</span>
          </div>
          <pre v-if="call.args_repr" class="mono integrate-pre">{{ call.args_repr }}</pre>
          <pre v-if="call.result_repr" class="mono integrate-pre">{{ call.result_repr }}</pre>
          <div v-if="call.error" class="integrate-error">{{ call.error }}</div>
        </div>

        <h3 class="integrate-h3">{{ t('integrate.overlay-title') }}</h3>
        <p class="v-hint">{{ t('integrate.overlay-note') }}</p>
        <el-table :data="auditOverlay" size="small" border stripe :empty-text="t('integrate.empty')">
          <el-table-column :label="t('integrate.overlay-layer')" show-overflow-tooltip>
            <template #default="{ row }">{{ row.layer || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.overlay-action')">
            <template #default="{ row }">
              <el-tag v-if="row.effective_action" size="small" :type="row.effective_action === 'allow' ? 'success' : 'danger'">{{ row.effective_action }}</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('integrate.overlay-rule')" show-overflow-tooltip>
            <template #default="{ row }">{{ row.rule_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.overlay-device')" show-overflow-tooltip>
            <template #default="{ row }">{{ row.device_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.overlay-time')">
            <template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('integrate.tab-coverage')" name="coverage">
        <h3 class="integrate-h3">{{ t('integrate.cov-warnings-title') }}</h3>
        <div v-if="!warnings.length" class="v-hint" style="margin:8px 0">{{ t('integrate.cov-none') }}</div>
        <el-table v-else :data="warnings" size="small" border stripe :empty-text="t('integrate.empty')" class="integrate-warn-table">
          <el-table-column :label="t('integrate.cov-tool')" prop="tool_name" show-overflow-tooltip />
          <el-table-column :label="t('integrate.cov-risk')">
            <template #default="{ row }"><el-tag size="small" type="danger">{{ row.risk_class }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-recommended')">
            <template #default="{ row }">{{ row.recommended_mode || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-bound')">
            <template #default="{ row }">{{ row.bound_mode || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-confidence')">
            <template #default="{ row }">{{ row.confidence || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-rules')" prop="matched_rules" show-overflow-tooltip />
          <el-table-column :label="t('integrate.cov-mcp')" show-overflow-tooltip>
            <template #default="{ row }">{{ row.mcp_name || '-' }}</template>
          </el-table-column>
        </el-table>

        <h3 class="integrate-h3">{{ t('integrate.cov-all-title') }}</h3>
        <el-table :data="tools" size="small" border stripe :empty-text="t('integrate.empty')">
          <el-table-column :label="t('integrate.cov-tool')" prop="tool_name" show-overflow-tooltip />
          <el-table-column :label="t('integrate.cov-risk')">
            <template #default="{ row }">
              <el-tag size="small" :type="row.risk_class === 'high' ? 'danger' : row.risk_class === 'medium' ? 'warning' : 'success'">{{ row.risk_class }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-recommended')">
            <template #default="{ row }">{{ row.recommended_mode || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-bound')">
            <template #default="{ row }">{{ row.bound_mode || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.cov-last-seen')">
            <template #default="{ row }">{{ fmtTime(row.last_seen) }}</template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('integrate.tab-health')" name="health">
        <el-table :data="devices" size="small" border stripe :empty-text="t('integrate.empty')">
          <el-table-column :label="t('integrate.health-device')" prop="device_id" show-overflow-tooltip />
          <el-table-column :label="t('integrate.health-state')">
            <template #default="{ row }">
              <el-tag size="small" :type="row.state === 'live' ? 'success' : row.state === 'stale' ? 'warning' : 'info'">{{ t('integrate.health-' + row.state) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('integrate.health-last-seen')">
            <template #default="{ row }">{{ ageText(row.last_seen_age_seconds) }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.health-last-event')">
            <template #default="{ row }">{{ ageText(row.last_event_age_seconds) }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.health-dropped')">
            <template #default="{ row }">{{ row.dropped_total ?? 0 }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.health-mode')">
            <template #default="{ row }">{{ row.mode || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('integrate.health-version')" prop="package_version" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { fmtTime } from '@/utils/format';
import {
  listApps,
  listObservations,
  getCoverage,
  getLiveness,
  recentAudit,
  type IntegrateApp,
  type ObservationEvent,
  type InventoryTool,
  type LivenessDevice
} from '@/api/integrate';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const tab = ref('timeline');
const apps = ref<IntegrateApp[]>([]);
const appId = ref('');
const rangeHours = ref(24);

const events = ref<ObservationEvent[]>([]);
const auditOverlay = ref<any[]>([]);
const tools = ref<InventoryTool[]>([]);
const warnings = ref<InventoryTool[]>([]);
const devices = ref<LivenessDevice[]>([]);

interface CallRow {
  call_id: string;
  tool_name?: string;
  status: string;
  device_id?: string;
  session_id?: string;
  duration_ms?: number | null;
  args_repr?: string | null;
  result_repr?: string | null;
  error?: string | null;
  started_at?: string;
  observed_at?: string;
}

const calls = computed<CallRow[]>(() => {
  const byId: Record<string, CallRow> = {};
  for (const e of events.value) {
    const row = (byId[e.call_id] ||= { call_id: e.call_id, status: 'started' });
    if (e.phase === 'started') {
      row.tool_name = e.tool_name || row.tool_name;
      row.device_id = e.device_id || row.device_id;
      row.session_id = e.session_id || row.session_id;
      row.args_repr = e.args_repr ?? row.args_repr;
      row.started_at = e.observed_at || row.started_at;
      if (row.status === 'started') row.status = 'started';
    } else {
      row.device_id = e.device_id || row.device_id;
      row.session_id = e.session_id || row.session_id;
      row.observed_at = e.observed_at || row.observed_at;
      row.duration_ms = e.duration_ms ?? row.duration_ms;
      row.result_repr = e.result_repr ?? row.result_repr;
      row.error = e.error ?? row.error;
      row.status = e.phase === 'error' ? 'error' : 'completed';
    }
  }
  return Object.values(byId).sort((a, b) => (b.observed_at || b.started_at || '').localeCompare(a.observed_at || a.started_at || ''));
});

function statusType(status: string) {
  if (status === 'completed') return 'success';
  if (status === 'error') return 'danger';
  return 'info';
}

function ageText(seconds?: number | null) {
  if (seconds == null) return '-';
  if (seconds < 60) return seconds + 's';
  if (seconds < 3600) return Math.floor(seconds / 60) + 'm';
  if (seconds < 86400) return Math.floor(seconds / 3600) + 'h';
  return Math.floor(seconds / 86400) + 'd';
}

async function loadApps() {
  try {
    const data = await listApps();
    apps.value = data.apps || [];
    if (!appId.value && apps.value.length) appId.value = apps.value[0].app_id;
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function loadObservations() {
  if (!appId.value) return;
  const since = new Date(Date.now() - rangeHours.value * 3600 * 1000).toISOString();
  try {
    const data = await listObservations(appId.value, { since, limit: '1000' });
    events.value = data.events || [];
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
  try {
    const audit = await recentAudit(200);
    auditOverlay.value = (audit && audit.db_events) || [];
  } catch {
    auditOverlay.value = [];
  }
}

async function loadCoverage() {
  if (!appId.value) return;
  try {
    const data = await getCoverage(appId.value);
    tools.value = data.tools || [];
    warnings.value = data.warnings || [];
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function loadLiveness() {
  if (!appId.value) return;
  try {
    const data = await getLiveness(appId.value);
    devices.value = data.devices || [];
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

function reloadAll() {
  loadObservations();
  loadCoverage();
  loadLiveness();
}

onMounted(async () => {
  await loadApps();
  reloadAll();
});
watch(() => session.tenant, async () => {
  appId.value = '';
  await loadApps();
  reloadAll();
});
</script>

<style scoped>
.integrate-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.integrate-title-row .v-card-title { margin: 0; }
.integrate-help {
  width: 22px; height: 22px; flex-shrink: 0; border-radius: 50%;
  border: 1px solid var(--v-border); background: #f8fafc; color: var(--v-muted);
  cursor: pointer; font-size: 12px; line-height: 1; padding: 0;
}
.integrate-help:hover, .integrate-help:focus-visible { color: var(--v-primary); border-color: var(--v-primary); outline: none; }
.integrate-h3 { font-size: 14px; margin: 18px 0 6px; color: #334155; }
.integrate-call { border: 1px solid var(--v-border); border-left: 3px solid #2563eb; border-radius: 6px; padding: 8px 12px; margin-bottom: 8px; background: #fafbfc; }
.integrate-call-head { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.integrate-meta { color: #94a3b8; font-size: 12px; }
.integrate-right { margin-left: auto; }
.integrate-pre { font-size: 11px; background: #f1f5f9; padding: 6px; border-radius: 4px; overflow: auto; margin: 4px 0 0; white-space: pre-wrap; }
.integrate-error { font-size: 12px; color: #b91c1c; margin-top: 4px; }
.integrate-warn-table { margin-bottom: 8px; }
</style>
<style>
.integrate-help-popper { max-width: 460px; line-height: 1.55; }
.integrate-help-popper p { margin: 0; }
.integrate-help-popper p + p { margin-top: 8px; }
</style>
