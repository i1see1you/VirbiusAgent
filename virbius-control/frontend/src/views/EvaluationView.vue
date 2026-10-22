<template>
  <div class="v-card">
    <div class="v-row" style="justify-content:space-between;align-items:flex-start">
      <div>
        <h2 class="v-card-title">{{ t('ev.title') }}</h2>
        <p class="v-hint">{{ t('ev.desc') }}</p>
      </div>
      <el-button size="small" @click="settingsOpen = true">{{ t('ev.set-open') }}</el-button>
    </div>
    <EvaluationSettingsPanel v-model="settingsOpen" @saved="refreshStatus" />

    <div v-if="!status" class="v-hint">{{ t('ev.status-loading') }}</div>
    <EvaluationIntroPanel v-else-if="!ready" :status="status" :diagnostic="statusError"
                          @retry="refreshStatus" />
    <template v-else>
      <el-alert v-if="licenseBanner" :type="licenseBanner.type" :title="licenseBanner.title"
                :description="licenseBanner.description" :closable="false" show-icon
                style="margin-bottom:12px" />
      <el-alert v-if="!writable" type="warning" :closable="false" show-icon
                :title="t('ev.license-readonly')" style="margin-bottom:12px" />

    <el-tabs v-model="tab">
      <!-- ① 报告 -->
      <el-tab-pane :label="t('ev.tab-reports')" name="reports">
        <div class="v-row">
          <el-button size="small" @click="loadReports">{{ t('ev.refresh') }}</el-button>
          <span class="v-hint">{{ t('ev.reports-hint') }}</span>
        </div>
        <el-table :data="reports" size="small" border stripe highlight-current-row
                  style="margin-top:12px" :empty-text="t('ev.empty')" @row-click="openReport">
          <el-table-column prop="report_id" :label="t('ev.col-report')" width="220" />
          <el-table-column prop="agent_name" :label="t('ev.col-agent')" width="160" />
          <el-table-column :label="t('ev.col-target')" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.target_fingerprint || row.target_path }}
            </template>
          </el-table-column>
          <el-table-column :label="t('ev.col-score')" width="90">
            <template #default="{ row }">{{ row.summary?.risk_score }}</template>
          </el-table-column>
          <el-table-column :label="t('ev.col-grade')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="gradeType(row.summary?.grade)">{{ row.summary?.grade }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="scanned_at" :label="t('ev.col-time')" width="200" />
        </el-table>

        <template v-if="detail">
          <h3 style="margin:16px 0 8px">
            {{ detail.report_id }} · {{ detail.summary?.risk_score }} · {{ detail.summary?.grade }}
          </h3>
          <el-table :data="detail.findings" size="small" border stripe>
            <el-table-column type="expand">
              <template #default="{ row }">
                <div style="padding:8px 16px">
                  <div><strong>{{ t('ev.evidence') }}</strong></div>
                  <div v-for="(e, i) in row.evidence || []" :key="i" class="ev-evidence">
                    <span class="mono">{{ e.ref }}</span>
                    <span v-if="e.snippet"> · {{ e.snippet }}</span>
                  </div>
                  <div v-if="row.virbius_config?.length" style="margin-top:8px">
                    <strong>{{ t('ev.prescription') }}</strong>
                    <div v-for="(c, i) in row.virbius_config" :key="i" class="ev-evidence">{{ c }}</div>
                  </div>
                  <div v-if="row.commands?.length" style="margin-top:8px">
                    <el-button size="small" @click="copy(row.commands.join('\n'))">
                      {{ t('ev.copy-commands') }}
                    </el-button>
                    <pre class="mono ev-evidence">{{ row.commands.join('\n') }}</pre>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="risk_name" :label="t('ev.col-risk')" min-width="280" />
            <el-table-column :label="t('ev.col-severity')" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="sevType(row.severity)">{{ row.severity }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="check_id" :label="t('ev.col-check')" width="220" />
            <el-table-column :label="t('ev.col-owasp')" width="140">
              <template #default="{ row }">{{ (row.owasp || []).join(', ') }}</template>
            </el-table-column>
            <el-table-column :label="t('ev.col-confidence')" width="110">
              <template #default="{ row }">{{ row.confidence || '-' }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <!-- ② 发起 -->
      <el-tab-pane :label="t('ev.tab-run')" name="run">
        <div class="v-row">
          <el-select v-model="runType" style="width:200px">
            <el-option value="scan" :label="t('ev.run-scan')" />
            <el-option value="http" :label="t('ev.run-http')" />
            <el-option value="mcp" :label="t('ev.run-mcp')" />
            <el-option value="replay" :label="t('ev.run-replay')" />
            <el-option value="skill" :label="t('ev.run-skill')" />
            <el-option value="jsonl" :label="t('ev.run-jsonl')" />
          </el-select>
          <el-input v-model="agentName" style="width:220px" :placeholder="t('ev.agent-name')" />
          <el-input v-model="baselineId" style="width:250px"
                    :placeholder="t('ev.baseline-id')" />
        </div>

        <div v-if="runType === 'scan'" class="v-row" style="margin-top:12px">
          <el-input v-model="targetPath" style="width:520px" :placeholder="t('ev.target-path')" />
        </div>

        <template v-else-if="runType === 'http'">
          <div class="v-row" style="margin-top:12px">
            <el-input v-model="targetUrl" style="width:520px" :placeholder="t('ev.target-url')" />
            <el-input v-model="model" style="width:200px" :placeholder="t('ev.model')" />
          </div>
        </template>

        <template v-else-if="runType === 'mcp'">
          <div class="v-row" style="margin-top:12px">
            <el-input v-model="mcpCmd" style="width:520px" :placeholder="t('ev.mcp-cmd')" />
          </div>
          <el-checkbox v-model="allowDangerous" style="margin-top:8px">
            {{ t('ev.mcp-gate') }}
          </el-checkbox>
          <p class="v-hint">{{ t('ev.mcp-gate-hint') }}</p>
        </template>

        <template v-else-if="runType === 'replay'">
          <div class="v-row" style="margin-top:12px">
            <el-input v-model="replayTenant" style="width:200px" :placeholder="t('ev.tenant')" />
            <el-input v-model="replayApp" style="width:200px" :placeholder="t('ev.app')" />
            <el-input v-model="replaySince" style="width:260px" :placeholder="t('ev.since')" />
          </div>
        </template>

        <template v-else-if="runType === 'skill'">
          <div class="v-row" style="margin-top:12px">
            <el-input v-model="skillPath" style="width:520px"
                      :placeholder="t('ev.skill-path')" />
          </div>
          <p class="v-hint">{{ t('ev.skill-path-hint') }}</p>
        </template>

        <template v-else-if="runType === 'jsonl'">
          <div class="v-row" style="margin-top:12px">
            <el-input v-model="jsonlTenant" style="width:180px"
                      :placeholder="t('ev.tenant')" />
            <el-input v-model="jsonlApp" style="width:180px"
                      :placeholder="t('ev.app')" />
          </div>
          <div class="v-row" style="margin-top:8px">
            <el-upload :auto-upload="false" :show-file-list="false"
                       accept=".jsonl,.json,.txt"
                       :on-change="onJsonlFile">
              <el-button size="small">{{ t('ev.jsonl-file') }}</el-button>
            </el-upload>
            <span v-if="jsonlFileName" class="v-hint">{{ jsonlFileName }}</span>
          </div>
          <el-checkbox v-model="jsonlAllowEmpty" style="margin-top:8px">
            {{ t('ev.jsonl-allow-empty') }}
          </el-checkbox>
          <p class="v-hint">{{ t('ev.jsonl-hint') }}</p>
        </template>

        <div class="v-row" style="margin-top:16px">
          <el-button type="primary" :loading="running" :disabled="!writable" @click="run">
            {{ t('ev.run-btn') }}
          </el-button>
          <span v-if="lastResult" class="v-hint">
            {{ lastResult.report_id }} · {{ lastResult.summary?.risk_score }} ·
            {{ lastResult.summary?.grade }}
          </span>
        </div>
      </el-tab-pane>

      <!-- ③ 复测闭环 -->
      <el-tab-pane :label="t('ev.tab-rescan')" name="rescan" lazy>
        <RescanDiffPanel />
      </el-tab-pane>
    </el-tabs>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import RescanDiffPanel from '@/components/RescanDiffPanel.vue';
import EvaluationIntroPanel from '@/components/EvaluationIntroPanel.vue';
import EvaluationSettingsPanel from '@/components/EvaluationSettingsPanel.vue';
import { useEvaluationStore } from '@/stores/evaluation';
import {
  createProbe, createReplay, createScan, createSkillScan, getReport,
  importReplay, listReports,
  type ReportMeta, type ScanReport
} from '@/api/evaluation';

const { t } = useI18n();
const evalStore = useEvaluationStore();

const status = computed(() => evalStore.status);
const statusError = computed(() => evalStore.error);
const ready = computed(() => !!status.value?.configured && !!status.value?.reachable);
/** 许可到期/非法时只读（历史报告、导出、复测查看不受影响）。 */
const writable = computed(() => {
  const lic = status.value?.license;
  if (!lic) return true;
  if (lic.mode === 'expired' || lic.mode === 'invalid') return false;
  return lic.active !== false;
});

const licenseBanner = computed<{ type: 'info' | 'warning' | 'error'; title: string; description: string } | null>(() => {
  const lic = status.value?.license;
  if (!lic) return null;
  const detail = lic.detail || '';
  switch (lic.mode) {
    case 'trial':
      return { type: 'info', title: t('ev.license-trial', { days: lic.days_left ?? 0 }), description: detail };
    case 'grace':
      return { type: 'warning', title: t('ev.license-grace'), description: detail };
    case 'expired':
      return { type: 'error', title: t('ev.license-expired'), description: detail };
    case 'invalid':
      return { type: 'error', title: t('ev.license-invalid'), description: detail };
    case 'unknown':
      return { type: 'warning', title: t('ev.license-unknown'), description: detail };
    default:
      return null;
  }
});

const tab = ref('reports');
const settingsOpen = ref(false);
const reports = ref<ReportMeta[]>([]);
const detail = ref<ScanReport | null>(null);

const runType = ref<'scan' | 'http' | 'mcp' | 'replay' | 'skill' | 'jsonl'>('scan');
const agentName = ref('');
const baselineId = ref('');
const targetPath = ref('');
const targetUrl = ref('');
const model = ref('');
const mcpCmd = ref('');
const allowDangerous = ref(false);
const replayTenant = ref('');
const replayApp = ref('');
const replaySince = ref('');
const skillPath = ref('');
const jsonlTenant = ref('local');
const jsonlApp = ref('');
const jsonlContent = ref('');
const jsonlFileName = ref('');
const jsonlAllowEmpty = ref(false);
const running = ref(false);
const lastResult = ref<ScanReport | null>(null);

function gradeType(grade?: string): string {
  switch (grade) {
    case 'blocked': return 'danger';
    case 'review': return 'warning';
    case 'caution': return '';
    default: return 'success';
  }
}

function sevType(sev?: string): string {
  switch (sev) {
    case 'critical': return 'danger';
    case 'high': return 'warning';
    case 'medium': return '';
    default: return 'info';
  }
}

async function loadReports() {
  try {
    reports.value = await listReports();
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

async function openReport(row: ReportMeta) {
  try {
    detail.value = await getReport(row.report_id);
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

async function onJsonlFile(file: any) {
  if (!file?.raw) return;
  jsonlFileName.value = file.name || '';
  try {
    jsonlContent.value = (await file.raw.text()).trim();
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(t('ev.copied'));
  } catch {
    ElMessage.warning(t('ev.copy-failed'));
  }
}

async function run() {
  running.value = true;
  try {
    const baseline = baselineId.value || undefined;
    const agent = agentName.value || undefined;
    let report: ScanReport;
    if (runType.value === 'scan') {
      report = await createScan({
        target_path: targetPath.value, agent_name: agent, baseline_report_id: baseline
      });
    } else if (runType.value === 'http') {
      report = await createProbe({
        target_type: 'http', url: targetUrl.value, model: model.value || undefined,
        agent_name: agent, baseline_report_id: baseline
      });
    } else if (runType.value === 'mcp') {
      report = await createProbe({
        target_type: 'mcp', mcp_cmd: mcpCmd.value.trim().split(/\s+/).filter(Boolean),
        allow_dangerous: allowDangerous.value,
        agent_name: agent, baseline_report_id: baseline
      });
    } else if (runType.value === 'skill') {
      report = await createSkillScan({
        target_path: skillPath.value, agent_name: agent, baseline_report_id: baseline
      });
    } else if (runType.value === 'jsonl') {
      report = await importReplay({
        format: 'jsonl',
        tenant: jsonlTenant.value || 'local',
        app: jsonlApp.value,
        events: jsonlContent.value,
        baseline_report_id: baseline,
        allow_empty: jsonlAllowEmpty.value
      });
    } else {
      report = await createReplay({
        tenant: replayTenant.value, app: replayApp.value,
        since: replaySince.value || undefined, baseline_report_id: baseline
      });
    }
    lastResult.value = report;
    ElMessage.success(`${report.report_id} · ${report.summary?.grade}`);
    await loadReports();
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  } finally {
    running.value = false;
  }
}

async function refreshStatus() {
  await evalStore.load(true);
  if (ready.value) await loadReports();
}

onMounted(async () => {
  await evalStore.load();
  if (ready.value) await loadReports();
});
</script>

<style scoped>
.ev-evidence {
  font-size: 12px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
  word-break: break-all;
}
</style>
