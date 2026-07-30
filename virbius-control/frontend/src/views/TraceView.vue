<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('trace.title') }}</h2>
    <p class="v-hint" v-html="t('trace.desc')"></p>

    <div class="v-row">
      <el-input v-model="toolName" :placeholder="t('trace.placeholder-tool')" style="width:160px" />
      <el-select v-model="stepType" style="width:140px">
        <el-option value="" :label="t('trace.type-all')" />
        <el-option value="input" label="input" />
        <el-option value="reasoning" label="reasoning" />
        <el-option value="tool_call" label="tool_call" />
        <el-option value="tool_result" label="tool_result" />
        <el-option value="output" label="output" />
      </el-select>
      <el-select v-model="decision" style="width:140px">
        <el-option value="" :label="t('trace.decision-all')" />
        <el-option value="allow" label="allow" />
        <el-option value="block" label="block" />
        <el-option value="challenge" label="challenge" />
      </el-select>
      <el-input-number v-model="limit" :min="1" :max="500" style="width:120px" />
      <el-button type="primary" @click="search">{{ t('trace.btn-search') }}</el-button>
      <el-button @click="search">{{ t('trace.btn-refresh') }}</el-button>
    </div>

    <el-table :data="results" size="small" border stripe style="margin-bottom:24px" @row-click="onRowClick">
      <el-table-column :label="t('trace.header-trace-id')">
        <template #default="{ row }">{{ (row.trace_id || '').slice(0, 12) }}…</template>
      </el-table-column>
      <el-table-column :label="t('trace.header-session')">
        <template #default="{ row }">{{ (row.session_id || '').slice(0, 12) }}…</template>
      </el-table-column>
      <el-table-column :label="t('trace.header-type')">
        <template #default="{ row }"><el-tag size="small">{{ row.step_type || '-' }}</el-tag></template>
      </el-table-column>
      <el-table-column :label="t('trace.header-tool')" prop="tool_name" />
      <el-table-column :label="t('trace.header-decision')">
        <template #default="{ row }">
          <el-tag v-if="row.tool_decision" size="small" :type="row.tool_decision === 'allow' ? 'success' : 'danger'">{{ row.tool_decision }}</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('trace.header-risk')">
        <template #default="{ row }">{{ row.risk_score ?? '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('trace.header-duration')">
        <template #default="{ row }">{{ row.tool_duration_ms != null ? row.tool_duration_ms + 'ms' : '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('trace.header-time')">
        <template #default="{ row }">{{ fmtTime(row.occurred_at) }}</template>
      </el-table-column>
    </el-table>

    <h3 style="font-size:15px;margin:16px 0 8px">
      {{ t('trace.timeline-title') }} · <code>{{ timelineSession || '-' }}</code>
    </h3>
    <div v-if="!timeline.length" class="v-hint">{{ t('trace.timeline-hint') }}</div>
    <div v-for="g in groupedTimeline" :key="g.traceId" class="v-card" style="background:#f8fafc;margin-bottom:12px">
      <h4 style="font-size:13px;margin:0 0 8px;color:#334155">Trace: {{ g.traceId.slice(0, 16) }}… ({{ g.steps.length }} steps)</h4>
      <div v-for="(s, i) in g.steps" :key="i">
        <div class="v-card" style="padding:8px 12px;margin:4px 0" :style="{ borderLeft: '3px solid ' + stepColor(s.step_type) }">
          <div style="display:flex;align-items:center;gap:6px">
            <span>{{ stepIcon(s.step_type) }}</span>
            <strong style="font-size:13px">{{ s.step_type }}</strong>
            <span style="margin-left:auto;color:#94a3b8;font-size:12px">#{{ s.step_seq }}</span>
          </div>
          <div style="font-size:12px;color:#475569;margin-top:4px">
            <span v-if="s.tool_name"> · {{ s.tool_name }}</span>
            <span v-if="s.tool_duration_ms != null"> · {{ s.tool_duration_ms }}ms</span>
            <span v-if="s.rule_id"> · rule: {{ s.rule_id }}</span>
          </div>
          <div style="margin-top:4px;display:flex;gap:6px">
            <el-tag v-if="s.tool_decision" size="small" :type="s.tool_decision === 'allow' ? 'success' : 'danger'">{{ s.tool_decision }}</el-tag>
            <el-tag v-if="s.risk_score > 0" size="small" :type="riskLevel(s.risk_score) === 'high' ? 'danger' : riskLevel(s.risk_score) === 'mid' ? 'warning' : 'success'">风险 {{ s.risk_score }}</el-tag>
            <el-tag v-if="s.tool_status" size="small" :type="s.tool_status === 'success' ? 'success' : 'danger'">{{ s.tool_status }}</el-tag>
          </div>
          <pre v-if="s.tool_args" class="mono" style="font-size:11px;background:#f1f5f9;padding:6px;border-radius:4px;overflow:auto;margin:4px 0">{{ formatArgs(s.tool_args) }}</pre>
          <div style="font-size:11px;color:#94a3b8">{{ fmtTime(s.occurred_at) }}</div>
        </div>
        <div v-if="i < g.steps.length - 1" style="text-align:center;color:#cbd5e1">↓</div>
      </div>
    </div>

    <div class="v-section">
      <h3>{{ t('trace.ingest-title') }}</h3>
      <div class="kpi-grid">
        <div class="kpi-card"><div class="label">启用</div><div class="value">{{ ingest.enabled ? '✅ 是' : '❌ 否' }}</div></div>
        <div class="kpi-card"><div class="label">Redis</div><div class="value">{{ ingest.redis_ok ? '✅ 已连接' : '❌ 未连接' }}</div></div>
        <div class="kpi-card"><div class="label">Stream</div><div class="value" style="font-size:13px">{{ ingest.stream_key || '-' }}</div></div>
        <div class="kpi-card"><div class="label">积压</div><div class="value">{{ ingest.stream_length ?? '-' }}</div></div>
        <div class="kpi-card"><div class="label">最近轮询</div><div class="value" style="font-size:12px">{{ ingest.last_poll_at ? fmtTime(ingest.last_poll_at) : '-' }}</div></div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { fmtTime } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const toolName = ref('');
const stepType = ref('');
const decision = ref('');
const limit = ref(50);
const results = ref<any[]>([]);
const timeline = ref<any[]>([]);
const timelineSession = ref('');
const ingest = ref<any>({});

const groupedTimeline = computed(() => {
  const m: Record<string, any[]> = {};
  timeline.value.forEach(s => { (m[s.trace_id || 'unknown'] ||= []).push(s); });
  return Object.entries(m).map(([traceId, steps]) => ({ traceId, steps }));
});

function stepIcon(t: string) { return ({ input: '📥', reasoning: '🧠', tool_call: '🔧', tool_result: '📤', output: '🏁' } as any)[t] || '❓'; }
function stepColor(t: string) { return ({ tool_call: '#2563eb', tool_result: '#16a34a', input: '#8b5cf6', reasoning: '#f59e0b', output: '#64748b' } as any)[t] || '#cbd5e1'; }
function riskLevel(s: number) { return s >= 70 ? 'high' : s >= 30 ? 'mid' : 'low'; }
function formatArgs(a: string) { try { return JSON.stringify(JSON.parse(a), null, 2); } catch { return a; } }

async function search() {
  const params = new URLSearchParams();
  if (toolName.value.trim()) params.set('tool_name', toolName.value.trim());
  if (stepType.value) params.set('step_type', stepType.value);
  if (decision.value) params.set('tool_decision', decision.value);
  params.set('limit', String(limit.value));
  try {
    results.value = await admin<any[]>('/trace/search?' + params.toString()) || [];
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function onRowClick(row: any) {
  if (!row.session_id) return;
  timelineSession.value = row.session_id;
  try {
    timeline.value = await admin<any[]>('/trace/session/' + encodeURIComponent(row.session_id) + '/timeline') || [];
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function loadIngest() {
  try { ingest.value = await admin<any>('/trace/ingest-status') || {}; } catch { /* ignore */ }
}

onMounted(() => { search(); loadIngest(); });
watch(() => session.tenant, () => { search(); loadIngest(); });
</script>
