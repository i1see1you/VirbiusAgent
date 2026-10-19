<template>
  <div class="v-card">
    <div class="ai-title-row">
      <h2 class="v-card-title">{{ t('ai.title') }}</h2>
      <el-tooltip placement="top" :show-after="200" :z-index="4200" popper-class="ai-help-popper">
        <template #content>
          <p>{{ t('ai.desc-short') }}</p>
          <p v-html="t('ai.desc')"></p>
        </template>
        <button type="button" class="ai-help" :aria-label="t('common.learn-more')">?</button>
      </el-tooltip>
    </div>

    <el-tabs v-model="tab">
      <!-- A3 integrity -->
      <el-tab-pane :label="t('ai.tab-verify')" name="verify">
        <div class="v-row">
          <label>{{ t('ai.tc-session') }}
            <el-input v-model="tcSession" style="width:300px" :placeholder="t('common.optional')" @keydown.enter="verifyTraceChain" />
          </label>
          <el-button type="primary" @click="verifyTraceChain">{{ t('ai.tc-run') }}</el-button>
          <el-button @click="loadChainStatus">{{ t('ai.chain-load') }}</el-button>
        </div>

        <div v-if="tcResult" class="v-section">
          <h3>{{ t('ai.tc-result') }}</h3>
          <p>
            <el-tag size="small" :type="tcResult.passed ? 'success' : 'danger'">
              {{ tcResult.passed ? t('ai.tc-pass') : t('ai.tc-fail') }}
            </el-tag>
            <span v-if="tcResult.passed" style="margin-left:8px">{{ t('ai.tc-verified-n', [tcResult.verified_events, tcResult.total_events]) }}</span>
            <span v-else style="margin-left:8px">{{ t('ai.tc-break', [tcResult.break_seq]) }} — {{ tcResult.reason }}</span>
          </p>
        </div>

        <div v-if="tcTenant" class="v-section">
          <h3>{{ t('ai.tc-tenant') }}</h3>
          <p class="v-hint">{{ t('ai.tc-tenant-summary', [tcTenant.verified_sessions, tcTenant.sessions]) }}</p>
          <el-table v-if="(tcTenant.broken || []).length" :data="tcTenant.broken" size="small" border stripe>
            <el-table-column :label="t('ai.tc-session-col')" prop="session_id" show-overflow-tooltip />
            <el-table-column :label="t('ai.tc-break-seq')" prop="break_seq" width="110" />
            <el-table-column :label="t('ai.tc-reason-col')" prop="reason" show-overflow-tooltip />
          </el-table>
        </div>

        <div v-if="chainStatus" class="v-section">
          <h3>{{ t('ai.chain-status') }}</h3>
          <pre class="ai-json">{{ pretty(chainStatus) }}</pre>
        </div>

        <div class="v-section">
          <h3>{{ t('ai.anchor-title') }}</h3>
          <div class="v-row">
            <label>{{ t('ai.anchor-date') }}
              <el-input v-model="anchorDate" style="width:200px" placeholder="2026-10-14" />
            </label>
            <el-button @click="sealAnchor">{{ t('ai.anchor-seal') }}</el-button>
            <span v-if="anchorResult" class="v-hint" style="margin:0">{{ t('ai.anchor-sealed', [anchorResult]) }}</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- A6 Decision BOM -->
      <el-tab-pane :label="t('ai.tab-bom')" name="bom">
        <div class="v-row">
          <label>{{ t('ai.bom-trace-id') }}
            <el-input v-model="bomTraceId" style="width:300px" @keydown.enter="loadBom" />
          </label>
          <el-button type="primary" @click="loadBom">{{ t('ai.bom-load') }}</el-button>
        </div>
        <div v-if="bom" class="v-section">
          <h3>{{ t('ai.bom-events', [ (bom.audit_events || []).length ]) }}</h3>
          <el-table v-if="(bom.audit_events || []).length" :data="bom.audit_events" size="small" border stripe>
            <el-table-column :label="t('ac.header-time')" width="170">
              <template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('ac.header-action')" prop="effective_action" width="90" />
            <el-table-column :label="t('ac.header-rule')" prop="rule_id" show-overflow-tooltip />
            <el-table-column :label="t('ac.header-reason')" prop="reason_code" width="150" />
            <el-table-column :label="t('ac.header-risk')" prop="max_risk_score" width="70" />
          </el-table>
          <details class="v-hint-more">
            <summary>{{ t('ai.raw') }}</summary>
            <pre class="ai-json">{{ pretty(bom) }}</pre>
          </details>
        </div>
      </el-tab-pane>

      <!-- A7 export -->
      <el-tab-pane :label="t('ai.tab-export')" name="export">
        <div class="v-row">
          <label>{{ t('ai.exp-min-risk') }}
            <el-input v-model="exp.min_risk" style="width:110px" placeholder="50" />
          </label>
          <label>{{ t('ai.exp-session') }}
            <el-input v-model="exp.session_id" style="width:180px" />
          </label>
          <label>{{ t('ai.exp-trace') }}
            <el-input v-model="exp.trace_id" style="width:180px" />
          </label>
          <label>{{ t('ai.exp-action') }}
            <el-input v-model="exp.effective_action" style="width:110px" placeholder="deny" />
          </label>
        </div>
        <div class="v-row">
          <label>{{ t('ai.exp-from') }}
            <el-input v-model="exp.from" style="width:220px" placeholder="2026-10-01T00:00:00Z" />
          </label>
          <label>{{ t('ai.exp-to') }}
            <el-input v-model="exp.to" style="width:220px" placeholder="2026-10-31T23:59:59Z" />
          </label>
          <el-button type="primary" @click="createExport">{{ t('ai.exp-create') }}</el-button>
          <el-button @click="loadExports">{{ t('common.refresh') }}</el-button>
        </div>

        <div class="v-section">
          <h3>{{ t('ai.exp-list') }}</h3>
          <el-table :data="expJobs" size="small" border stripe :empty-text="t('common.no-data')">
            <el-table-column :label="t('ai.exp-job-id')" show-overflow-tooltip>
              <template #default="{ row }"><code>{{ row.job_id }}</code></template>
            </el-table-column>
            <el-table-column :label="t('ai.exp-status')" prop="status" width="100" />
            <el-table-column :label="t('ai.exp-bytes')" prop="file_bytes" width="110" />
            <el-table-column :label="t('ai.exp-requester')" prop="requester" width="130" />
            <el-table-column :label="t('al.header-time')" width="170">
              <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
            </el-table-column>
            <el-table-column :label="t('ai.exp-actions')" width="130">
              <template #default="{ row }">
                <el-button v-if="row.status === 'done'" size="small" @click="download(row.job_id)">{{ t('ai.exp-download') }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <!-- C5 session checkpoint -->
      <el-tab-pane :label="t('ai.tab-checkpoint')" name="checkpoint">
        <div class="v-row">
          <label>{{ t('ai.cp-session') }}
            <el-input v-model="cpSession" style="width:300px" @keydown.enter="loadCheckpoint" />
          </label>
          <el-button type="primary" @click="loadCheckpoint">{{ t('ai.cp-load') }}</el-button>
        </div>
        <div v-if="cp" class="v-section">
          <div class="ai-kv">
            <div v-for="(v, k) in cp" :key="k" class="ai-kv-item">
              <span class="ai-kv-key">{{ k }}</span>
              <span class="ai-kv-val">{{ v }}</span>
            </div>
          </div>
        </div>
        <p v-else-if="cpErr" class="v-hint">{{ cpErr }}</p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { admin } from '@/api/client';
import { useSessionStore } from '@/stores/session';
import { fmtTime } from '@/utils/format';

const { t } = useI18n();
const session = useSessionStore();

const tab = ref('verify');
const pretty = (o: any) => JSON.stringify(o, null, 2);

// A3 trace-chain verify
const tcSession = ref('');
const tcResult = ref<any>(null);
const tcTenant = ref<any>(null);
const chainStatus = ref<any>(null);
const anchorDate = ref('');
const anchorResult = ref('');

async function verifyTraceChain() {
  const s = tcSession.value.trim();
  try {
    if (s) {
      tcResult.value = await admin(`/audit/trace-chain/verify?session_id=${encodeURIComponent(s)}`);
      tcTenant.value = null;
    } else {
      tcTenant.value = await admin('/audit/trace-chain/verify');
      tcResult.value = null;
    }
  } catch (e: any) { ElMessage.error(e.message); }
}

async function loadChainStatus() {
  try { chainStatus.value = await admin('/audit/chain/status'); } catch (e: any) { ElMessage.error(e.message); }
}

async function sealAnchor() {
  try {
    const body = anchorDate.value.trim() ? JSON.stringify({ date: anchorDate.value.trim() }) : '{}';
    const d = await admin<any>('/audit/daily-anchor/seal', { method: 'POST', body });
    anchorResult.value = d?.sealed_date || '';
    ElMessage.success(t('ai.anchor-sealed', [anchorResult.value]));
  } catch (e: any) { ElMessage.error(e.message); }
}

// A6 decision BOM
const bomTraceId = ref('');
const bom = ref<any>(null);
async function loadBom() {
  const id = bomTraceId.value.trim();
  if (!id) return;
  try { bom.value = await admin(`/audit/decision-bom/${encodeURIComponent(id)}`); } catch (e: any) { ElMessage.error(e.message); }
}

// A7 export
const exp = ref({ min_risk: '', session_id: '', trace_id: '', effective_action: '', from: '', to: '' });
const expJobs = ref<any[]>([]);

async function createExport() {
  try {
    const body: any = {};
    if (exp.value.min_risk.trim()) body.min_risk = Number(exp.value.min_risk.trim());
    if (exp.value.session_id.trim()) body.session_id = exp.value.session_id.trim();
    if (exp.value.trace_id.trim()) body.trace_id = exp.value.trace_id.trim();
    if (exp.value.effective_action.trim()) body.effective_action = exp.value.effective_action.trim();
    if (exp.value.from.trim()) body.from = exp.value.from.trim();
    if (exp.value.to.trim()) body.to = exp.value.to.trim();
    await admin('/audit/export', { method: 'POST', body: JSON.stringify(body) });
    ElMessage.success(t('ai.exp-created'));
    loadExports();
  } catch (e: any) { ElMessage.error(e.message); }
}

async function loadExports() {
  try { expJobs.value = await admin<any[]>('/audit/export?limit=50'); } catch (e: any) { ElMessage.error(e.message); }
}

async function download(jobId: string) {
  const url = `/api/v1/admin/tenants/${encodeURIComponent(session.tenant)}/audit/export/${encodeURIComponent(jobId)}/file`;
  const headers: Record<string, string> = {};
  if (session.apiKey) headers['Authorization'] = 'Bearer ' + session.apiKey;
  try {
    const res = await fetch(url, { credentials: 'same-origin', headers });
    if (!res.ok) { ElMessage.error('HTTP ' + res.status); return; }
    const blob = await res.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = jobId + '.enc';
    a.click();
    URL.revokeObjectURL(a.href);
  } catch (e: any) { ElMessage.error(e.message); }
}

// C5 checkpoint
const cpSession = ref('');
const cp = ref<any>(null);
const cpErr = ref('');
async function loadCheckpoint() {
  const s = cpSession.value.trim();
  if (!s) return;
  try { cp.value = await admin(`/sessions/${encodeURIComponent(s)}/checkpoint`); cpErr.value = ''; }
  catch (e: any) { cp.value = null; cpErr.value = e.message; }
}

onMounted(loadExports);
watch(() => session.tenant, loadExports);
</script>
<style scoped>
.ai-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.ai-title-row .v-card-title { margin: 0; }
.ai-help {
  width: 22px; height: 22px; flex-shrink: 0; border-radius: 50%;
  border: 1px solid var(--v-border); background: #f8fafc; color: var(--v-muted);
  cursor: pointer; font-size: 12px; line-height: 1; padding: 0;
}
.ai-help:hover, .ai-help:focus-visible { color: var(--v-primary); border-color: var(--v-primary); outline: none; }
.ai-json { max-height: 320px; overflow: auto; background: #f8fafc; border: 1px solid var(--v-border); border-radius: 6px; padding: 10px; font-size: 12px; }
.ai-kv { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 8px 16px; }
.ai-kv-item { display: flex; justify-content: space-between; gap: 12px; border-bottom: 1px dashed var(--v-border); padding: 4px 0; }
.ai-kv-key { color: var(--v-muted); }
.ai-kv-val { font-weight: 600; }
</style>
<style>
.ai-help-popper { max-width: 420px; line-height: 1.55; }
.ai-help-popper p { margin: 0; }
.ai-help-popper p + p { margin-top: 8px; }
</style>
