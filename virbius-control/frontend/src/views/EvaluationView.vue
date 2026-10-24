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

    <el-dialog v-model="remOpen" :title="t('ev.rem-title')" width="780px">
      <div v-if="remLoading" class="v-hint">{{ t('ev.status-loading') }}</div>
      <template v-else-if="remPreview">
        <el-alert v-if="!writable" type="warning" :closable="false" show-icon
                  :title="t('ev.license-readonly')" style="margin-bottom:10px" />
        <div class="v-row" style="margin-bottom:10px; flex-wrap:wrap">
          <span class="v-hint">{{ t('ev.rem-app') }}</span>
          <el-input v-model="remApp" size="small" style="width:220px"
                    :placeholder="t('ev.rem-app-ph')" />
          <el-checkbox v-model="remAppConfirmed">{{ t('ev.rem-app-confirm') }}</el-checkbox>
        </div>
        <p v-if="remApp && remPreview.app_candidates.length && !remPreview.app_candidates.includes(remApp)"
           class="v-hint" style="color:var(--el-color-warning)">
          {{ t('ev.rem-app-unknown') }}
        </p>

        <div v-for="p in remPreview.plans" :key="p.risk_id + p.plan_id" class="rem-plan">
          <div class="v-row" style="justify-content:space-between">
            <strong>{{ p.title || p.plan_id }}</strong>
            <el-tag size="small" :type="p.executable ? '' : 'info'">
              {{ p.executable ? t('ev.rem-create-rule') : t('ev.rem-unsupported') }}
            </el-tag>
          </div>
          <p class="v-hint" style="margin:4px 0">{{ t('ev.rem-dryrun-hint') }}</p>
          <p v-if="!p.executable" class="v-hint">{{ p.executable_reason }}</p>
          <div v-for="(spec, name) in p.vars" :key="name" class="v-row" style="margin-top:6px">
            <span class="mono" style="min-width:110px">{{ name }}</span>
            <el-input v-model="remVars[p.risk_id + '|' + p.plan_id][name]" size="small"
                      style="width:340px" :placeholder="spec.hint || ''"
                      :disabled="!p.executable" />
            <el-tag v-if="spec.danger && spec.danger !== 'none'" size="small"
                    :type="spec.danger === 'mask' ? 'warning' : 'danger'">{{ spec.danger }}</el-tag>
            <span v-if="spec.bound_value" class="v-hint">{{ t('ev.rem-from-evidence') }}</span>
          </div>
          <p v-if="p.normalized" class="v-hint" style="margin:6px 0">{{ p.normalized_note }}</p>
          <details v-if="p.payload_preview" style="margin-top:6px">
            <summary class="v-hint">{{ t('ev.rem-payload') }}</summary>
            <pre class="mono ev-evidence">{{ JSON.stringify(p.payload_preview, null, 2) }}</pre>
          </details>
          <p v-if="p.existing_rule" class="v-hint" style="margin:6px 0">
            {{ t('ev.rem-existing', { rule: p.existing_rule.rule_id, state: p.existing_rule.rollout_state }) }}
          </p>
          <p v-if="p.equivalent_rule" class="v-hint" style="margin:6px 0;color:var(--el-color-warning)">
            {{ t('ev.rem-equivalent', { rule: p.equivalent_rule.rule_id, state: p.equivalent_rule.rollout_state }) }}
          </p>
          <p v-if="p.similar_rules?.length" class="v-hint" style="margin:6px 0">
            {{ t('ev.rem-similar', { rules: p.similar_rules.map(s => s.rule_id + '(' + s.rollout_state + ')').join(', ') }) }}
          </p>
        </div>

        <div v-if="remResults.length" style="margin-top:12px">
          <h4 style="margin:8px 0">{{ t('ev.rem-results') }}</h4>
          <el-table :data="remResults" size="small" border>
            <el-table-column prop="plan_id" label="plan" width="170" />
            <el-table-column :label="t('ev.rem-status')" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="remStatusType(row.status)">
                  {{ t('ev.rem-st-' + row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="rule_id" min-width="230">
              <template #default="{ row }">
                <span class="mono">{{ row.rule_id || '-' }}</span>
                <el-button v-if="row.rule_id" link size="small" @click="copy(row.rule_id)">
                  {{ t('ev.rem-copy-id') }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column min-width="240">
              <template #default="{ row }">
                <span class="v-hint">{{ row.error || row.hint || '' }}</span>
                <router-link v-if="row.rule_id && ['created','skipped_exists','skipped_covered','skipped_cap'].includes(row.status)"
                             to="/rules" class="v-hint" style="margin-left:6px">
                  {{ t('ev.rem-goto-rules') }}
                </router-link>
              </template>
            </el-table-column>
          </el-table>
          <p class="v-hint" style="margin-top:6px">{{ t('ev.rem-observe-hint') }}</p>
        </div>
      </template>
      <template #footer>
        <el-button size="small" @click="remOpen = false">{{ t('ev.rem-close') }}</el-button>
        <el-button size="small" type="primary" :loading="remApplying"
                   :disabled="!writable || !remAppConfirmed || !hasExecutablePlan"
                   @click="submitRemediation">
          {{ t('ev.rem-apply') }}
        </el-button>
      </template>
    </el-dialog>

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
          <el-input v-model="filterAgent" size="small" clearable style="width:180px"
                    :placeholder="t('ev.filter-agent')" @keyup.enter="reloadReports" />
          <el-select v-model="filterGrade" size="small" clearable style="width:150px"
                     :placeholder="t('ev.filter-grade')" @change="reloadReports">
            <el-option value="blocked" label="blocked" />
            <el-option value="review" label="review" />
            <el-option value="caution" label="caution" />
            <el-option value="pass" label="pass" />
          </el-select>
          <span class="v-hint">{{ t('ev.reports-hint') }}</span>
        </div>
        <el-table :data="reports" size="small" border stripe highlight-current-row
                  style="margin-top:12px" :empty-text="t('ev.empty')" @row-click="openReport"
                  :row-class-name="reportRowClass">
          <el-table-column prop="report_id" :label="t('ev.col-report')" width="220" />
          <el-table-column prop="agent_name" :label="t('ev.col-agent')" width="150" />
          <el-table-column :label="t('ev.col-target')" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.target_fingerprint || row.target_path }}
            </template>
          </el-table-column>
          <el-table-column :label="t('ev.col-score')" width="80">
            <template #default="{ row }">{{ row.summary?.risk_score }}</template>
          </el-table-column>
          <el-table-column :label="t('ev.col-grade')" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="gradeType(row.summary?.grade)">{{ row.summary?.grade }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('ev.accepted')" width="95">
            <template #default="{ row }">{{ row.suppressed_count ?? 0 }}</template>
          </el-table-column>
          <el-table-column prop="scanned_at" :label="t('ev.col-time')" width="190" />
        </el-table>
        <div class="v-row" style="margin-top:10px">
          <el-button size="small" :disabled="offset === 0" @click="prevPage">{{ t('ev.prev') }}</el-button>
          <span class="v-hint">{{ offset + 1 }}–{{ Math.min(offset + limit, total) }} / {{ total }}</span>
          <el-button size="small" :disabled="offset + limit >= total" @click="nextPage">{{ t('ev.next') }}</el-button>
        </div>

        <template v-if="detail">
          <h3 style="margin:16px 0 8px">
            {{ detail.report_id }} · {{ detail.summary?.risk_score }} · {{ detail.summary?.grade }}
            <el-tag v-if="acceptedCount" size="small" type="info" style="margin-left:8px">
              {{ t('ev.accepted-n', { n: acceptedCount }) }}
            </el-tag>
          </h3>
          <div class="v-row" style="margin-bottom:8px">
            <el-button size="small" @click="exportReport('json')">JSON</el-button>
            <el-button size="small" @click="exportReport('html')">HTML</el-button>
            <el-button size="small" @click="exportReport('sarif')">SARIF</el-button>
            <el-checkbox v-model="showSuppressed">{{ t('ev.show-suppressed') }}</el-checkbox>
            <el-input v-model="filterFindings" size="small" clearable style="width:300px"
                      :placeholder="t('ev.filter-findings')" />
          </div>
          <el-table :data="filteredFindings" size="small" border stripe
                    :row-class-name="findingRowClass" @expand-change="onExpandChange">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div style="padding:8px 16px">
                  <div v-if="row.risk_id" style="margin-bottom:6px">
                    <strong>{{ t('ev.col-risk-id') }}</strong>
                    <span class="mono" style="margin-left:6px">{{ row.risk_id }}</span>
                    <el-button link size="small" @click="copyDeepLink(row)">{{ t('ev.risk-copy-link') }}</el-button>
                    <el-button link size="small" @click="copySuppression(row)">{{ t('ev.risk-suppress') }}</el-button>
                  </div>
                  <div><strong>{{ t('ev.evidence') }}</strong></div>
                  <div v-for="(e, i) in row.evidence || []" :key="i" class="ev-evidence">
                    <span class="mono">{{ e.ref }}</span>
                    <span v-if="e.snippet"> · {{ e.snippet }}</span>
                  </div>
                  <div v-if="row.virbius_config?.length" style="margin-top:8px">
                    <strong>{{ t('ev.prescription') }}</strong>
                    <div v-for="(c, i) in row.virbius_config" :key="i" class="ev-evidence">{{ c }}</div>
                  </div>
                  <div v-if="row.suppressed" style="margin-top:8px">
                    <strong>{{ t('ev.suppression-reason') }}</strong>
                    <div class="ev-evidence">{{ row.suppression?.reason || '-' }}</div>
                  </div>
                  <div v-if="row.commands?.length" style="margin-top:8px">
                    <el-button size="small" @click="copy(row.commands.join('\n'))">
                      {{ t('ev.copy-commands') }}
                    </el-button>
                    <pre class="mono ev-evidence">{{ row.commands.join('\n') }}</pre>
                  </div>
                  <div v-if="row.remediations?.length && !row.suppressed" style="margin-top:8px">
                    <el-tag v-if="appliedState[row.risk_id]" size="small" type="success"
                            style="margin-right:8px">
                      {{ t('ev.rem-applied', {
                        rule: appliedState[row.risk_id].rule_id,
                        state: appliedState[row.risk_id].rollout_state
                      }) }}
                    </el-tag>
                    <el-button size="small" type="primary" :disabled="!writable"
                               @click="openRemediation(row)">
                      {{ t('ev.rem-open') }}
                    </el-button>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="risk_name" :label="t('ev.col-risk')" min-width="280" />
            <el-table-column :label="t('ev.col-severity')" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="sevType(row.severity)">{{ row.severity }}</el-tag>
                <el-tag v-if="row.suppressed" size="small" type="info" style="margin-left:4px">
                  {{ t('ev.suppressed') }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="check_id" :label="t('ev.col-check')" width="220" />
            <el-table-column :label="t('ev.col-risk-id')" width="150">
              <template #default="{ row }">
                <span class="mono">{{ row.risk_id }}</span>
              </template>
            </el-table-column>
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
          <div class="v-row" style="margin-top:8px">
            <el-input v-model="probeIdentity" style="width:240px"
                      :placeholder="t('ev.probe-identity')" />
            <span class="v-hint">{{ t('ev.probe-identity-hint') }}</span>
          </div>
          <div style="margin-top:8px">
            <el-input v-model="targetSpec" type="textarea" :rows="4" style="width:740px"
                      :placeholder="t('ev.target-spec')" />
            <p class="v-hint">{{ t('ev.target-spec-hint') }}</p>
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
          <el-button v-if="activeJob && !jobTerminal" size="small" @click="cancelCurrent">
            {{ t('ev.cancel') }}
          </el-button>
          <span v-if="lastResult" class="v-hint">
            {{ lastResult.report_id }} · {{ lastResult.summary?.risk_score }} ·
            {{ lastResult.summary?.grade }}
          </span>
        </div>
        <div v-if="activeJob" class="ev-job">
          <el-progress v-if="activeJob.progress?.total"
                       :percentage="Math.round((activeJob.progress.done / activeJob.progress.total) * 100)" />
          <span class="v-hint">
            {{ t('ev.job-status') }}: {{ activeJob.status }} · {{ activeJob.phase }}
            <template v-if="activeJob.progress?.total">
              · {{ activeJob.progress.done }}/{{ activeJob.progress.total }}
            </template>
            <template v-if="activeJob.progress?.current"> · {{ activeJob.progress.current }}</template>
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
import { computed, nextTick, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import RescanDiffPanel from '@/components/RescanDiffPanel.vue';
import EvaluationIntroPanel from '@/components/EvaluationIntroPanel.vue';
import EvaluationSettingsPanel from '@/components/EvaluationSettingsPanel.vue';
import { useEvaluationStore } from '@/stores/evaluation';
import {
  cancelJob, createProbe, createReplay, createScan, createSkillScan, downloadReport,
  getJob, getReport, importReplay, listReports, remediationApply, remediationPreview,
  type Finding as EvalFinding, type JobView, type RemediationPreview,
  type RemediationResultItem, type ReportMeta, type ScanReport
} from '@/api/evaluation';

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
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
const total = ref(0);
const limit = ref(20);
const offset = ref(0);
const filterAgent = ref('');
const filterGrade = ref('');
const showSuppressed = ref(true);
const activeJob = ref<JobView | null>(null);
const jobTerminal = computed(() => !!activeJob.value &&
  ['succeeded', 'failed', 'canceled'].includes(activeJob.value.status));
const detail = ref<ScanReport | null>(null);
const filterFindings = ref('');
const highlightRiskId = ref('');

const remOpen = ref(false);
const remLoading = ref(false);
const remApplying = ref(false);
const remPreview = ref<RemediationPreview | null>(null);
const remVars = ref<Record<string, Record<string, string>>>({});
const remApp = ref('');
const remAppConfirmed = ref(false);
const remResults = ref<RemediationResultItem[]>([]);
const appliedState = ref<Record<string, { rule_id: string; rollout_state: string }>>({});
const expandedChecked = ref<Record<string, boolean>>({});
const hasExecutablePlan = computed(() =>
  !!remPreview.value?.plans.some(p => p.executable));

function remStatusType(status: string): string {
  switch (status) {
    case 'created': return 'success';
    case 'skipped_cap': return 'warning';
    case 'needs_input': return 'warning';
    case 'failed': return 'danger';
    default: return 'info';
  }
}

async function openRemediation(row: EvalFinding) {
  if (!detail.value) return;
  remOpen.value = true;
  remLoading.value = true;
  remPreview.value = null;
  remResults.value = [];
  remVars.value = {};
  remApp.value = detail.value.agent_name || '';
  remAppConfirmed.value = false;
  try {
    const preview = await remediationPreview(detail.value.report_id, [row.risk_id]);
    for (const plan of preview.plans) {
      const key = plan.risk_id + '|' + plan.plan_id;
      const values: Record<string, string> = {};
      for (const [name, spec] of Object.entries(plan.vars || {})) {
        values[name] = spec.bound_value || spec.default_value || '';
      }
      remVars.value[key] = values;
      if (plan.existing_rule) {
        appliedState.value[plan.risk_id] = {
          rule_id: plan.existing_rule.rule_id,
          rollout_state: plan.existing_rule.rollout_state
        };
      }
    }
    remPreview.value = preview;
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
    remOpen.value = false;
  } finally {
    remLoading.value = false;
  }
}

async function submitRemediation() {
  if (!detail.value || !remPreview.value) return;
  const items = remPreview.value.plans
    .filter(p => p.executable)
    .map(p => ({
      risk_id: p.risk_id,
      plan_id: p.plan_id,
      variables: remVars.value[p.risk_id + '|' + p.plan_id] || {},
      app_id: remApp.value || undefined
    }));
  if (!items.length) return;
  remApplying.value = true;
  try {
    const resp = await remediationApply(detail.value.report_id, items);
    remResults.value = resp.results;
    for (const r of resp.results) {
      if (r.rule_id && (r.status === 'created' || r.status === 'skipped_exists')) {
        appliedState.value[r.risk_id] = {
          rule_id: r.rule_id,
          rollout_state: r.status === 'created' ? 'dry_run' : (r.existing_state || 'existing')
        };
      }
    }
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  } finally {
    remApplying.value = false;
  }
}

/** S1：展开 finding 时惰性反查是否已生成过规则（低频动作，不随页面加载全量反查）。 */
async function onExpandChange(row: EvalFinding, expandedRows: EvalFinding[]) {
  if (!detail.value || !expandedRows.includes(row)) return;
  if (!row.remediations?.length || row.suppressed) return;
  if (appliedState.value[row.risk_id] || expandedChecked.value[row.risk_id]) return;
  expandedChecked.value[row.risk_id] = true;
  try {
    const preview = await remediationPreview(detail.value.report_id, [row.risk_id]);
    const existing = preview.plans.find(p => p.existing_rule)?.existing_rule;
    if (existing) {
      appliedState.value[row.risk_id] = {
        rule_id: existing.rule_id,
        rollout_state: existing.rollout_state
      };
    }
  } catch {
    expandedChecked.value[row.risk_id] = false;
  }
}

const acceptedCount = computed(() =>
  (detail.value?.findings || []).filter(f => f.suppressed).length);
const visibleFindings = computed(() =>
  (detail.value?.findings || []).filter(f => showSuppressed.value || !f.suppressed));

/** 过滤：16 位 hex 视为 risk_id 精确匹配、8–15 位 hex 为前缀；其余匹配风险 ID/检测项/标题/证据。 */
const filteredFindings = computed(() => {
  const base = visibleFindings.value;
  const q = filterFindings.value.trim().toLowerCase();
  if (!q) return base;
  const hex = /^[0-9a-f]{8,16}$/.test(q);
  return base.filter(f => {
    const id = String(f.risk_id || '').toLowerCase();
    if (hex) return q.length === 16 ? id === q : id.startsWith(q);
    const hay = [
      f.risk_id,
      f.check_id,
      f.risk_name,
      ...((f.evidence || []).map(e => `${e.ref || ''} ${e.snippet || ''}`))
    ].join(' ').toLowerCase();
    return hay.includes(q);
  });
});

const runType = ref<'scan' | 'http' | 'mcp' | 'replay' | 'skill' | 'jsonl'>('scan');
const agentName = ref('');
const baselineId = ref('');
const targetPath = ref('');
const targetUrl = ref('');
const model = ref('');
const probeIdentity = ref('');
const targetSpec = ref('');
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
    const page = await listReports({
      agentName: filterAgent.value || undefined,
      grade: filterGrade.value || undefined,
      limit: limit.value,
      offset: offset.value
    });
    reports.value = page.items;
    total.value = page.total;
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

function reloadReports() {
  offset.value = 0;
  void loadReports();
}

function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value);
  void loadReports();
}

function nextPage() {
  offset.value += limit.value;
  void loadReports();
}

async function exportReport(format: 'json' | 'html' | 'sarif') {
  if (!detail.value) return;
  try {
    await downloadReport(detail.value.report_id, format);
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

function reportRowClass() {
  return '';
}

function findingRowClass({ row }: { row: { suppressed?: boolean; risk_id?: string } }) {
  const cls: string[] = [];
  if (row.suppressed) cls.push('ev-suppressed-row');
  if (highlightRiskId.value && row.risk_id === highlightRiskId.value) cls.push('ev-row-highlight');
  return cls.join(' ');
}

async function loadDetail(reportId: string, riskId?: string) {
  detail.value = await getReport(reportId);
  filterFindings.value = '';
  highlightRiskId.value = '';
  if (riskId) await focusRisk(riskId);
}

async function openReport(row: ReportMeta) {
  try {
    await loadDetail(row.report_id);
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

/** 深链定位：高亮 + 展开 + 滚动到目标 finding（risk_id 不存在时提示）。 */
async function focusRisk(riskId: string) {
  const row = (detail.value?.findings || []).find(f => f.risk_id === riskId);
  if (!row) {
    ElMessage.warning(t('ev.risk-not-found', { risk: riskId }));
    return;
  }
  highlightRiskId.value = riskId;
  await nextTick();
  // 深链首帧表格可能尚未挂载；轮询到目标行后点击展开图标（已展开则不重复点击）。
  let tries = 0;
  const reveal = () => {
    const rowEl = document.querySelector('.ev-row-highlight');
    const icon = rowEl?.querySelector('.el-table__expand-icon');
    if (!rowEl && tries++ < 40) { window.setTimeout(reveal, 50); return; }
    if (!rowEl) return;
    if (icon && !icon.classList.contains('el-table__expand-icon--expanded')) {
      (icon as HTMLElement).click();
    }
    rowEl.scrollIntoView({ block: 'center', behavior: 'smooth' });
  };
  window.setTimeout(reveal, 80);
}

function copyDeepLink(row: EvalFinding) {
  const report = detail.value?.report_id || '';
  const href = router.resolve({ path: '/evaluation', query: { report, risk: row.risk_id } }).href;
  void copy(`${window.location.origin}${window.location.pathname}${href}`);
}

/** 生成可直接粘贴到 .ve-suppress.yaml 的条目（reason 留 TODO，需人工填写）。 */
function copySuppression(row: EvalFinding) {
  const id = String(row.risk_id || '').slice(0, 8) || 'finding';
  const snippet = [
    `  - id: accept-${id}`,
    `    reason: "${t('ev.risk-suppress-reason')}"`,
    '    match:',
    `      risk_id: "${row.risk_id}"`
  ].join('\n');
  void copy(snippet);
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
  activeJob.value = null;
  try {
    const baseline = baselineId.value || undefined;
    const agent = agentName.value || undefined;
    if (runType.value === 'http' || runType.value === 'mcp') {
      const submit = await createProbe(runType.value === 'http'
        ? { target_type: 'http', url: targetUrl.value, model: model.value || undefined,
            identity: probeIdentity.value || undefined,
            target_spec: targetSpec.value.trim() || undefined,
            agent_name: agent, baseline_report_id: baseline }
        : { target_type: 'mcp', mcp_cmd: mcpCmd.value.trim().split(/\s+/).filter(Boolean),
            allow_dangerous: allowDangerous.value,
            agent_name: agent, baseline_report_id: baseline });
      ElMessage.info(t('ev.job-submitted', { id: submit.job_id }));
      await pollJob(submit.job_id);
      return;
    }
    let report: ScanReport;
    if (runType.value === 'scan') {
      report = await createScan({
        target_path: targetPath.value, agent_name: agent, baseline_report_id: baseline
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

/** 探针任务轮询：1s 起、指数退避到 5s；终态后拉取报告。 */
async function pollJob(jobId: string) {
  let delay = 1000;
  while (true) {
    let job: JobView;
    try {
      job = await getJob(jobId);
    } catch (e: any) {
      ElMessage.error(t('ev.job-lost') + ' — ' + String(e?.message || e));
      return;
    }
    activeJob.value = job;
    if (job.status === 'succeeded') {
      if (job.report_id) {
        const report = await getReport(job.report_id);
        lastResult.value = report;
        ElMessage.success(`${report.report_id} · ${report.summary?.grade}`);
        await loadReports();
      }
      return;
    }
    if (job.status === 'failed') {
      ElMessage.error(job.error?.message || t('ev.job-failed'));
      return;
    }
    if (job.status === 'canceled') {
      ElMessage.info(t('ev.job-canceled'));
      return;
    }
    await new Promise(r => setTimeout(r, delay));
    delay = Math.min(5000, Math.round(delay * 1.5));
  }
}

async function cancelCurrent() {
  if (!activeJob.value) return;
  try {
    activeJob.value = await cancelJob(activeJob.value.job_id);
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  }
}

async function refreshStatus() {
  await evalStore.load(true);
  if (ready.value) await loadReports();
}

onMounted(async () => {
  await evalStore.load();
  if (ready.value) await loadReports();
  const pick = (v: unknown): string =>
    Array.isArray(v) ? String(v[0] || '') : (typeof v === 'string' ? v : '');
  const reportId = pick(route.query.report);
  const riskId = pick(route.query.risk);
  if (reportId) {
    try {
      await loadDetail(reportId, riskId || undefined);
    } catch (e: any) {
      ElMessage.error(String(e?.message || e));
    }
  }
});
</script>

<style scoped>
.ev-job { margin-top: 12px; display: flex; flex-direction: column; gap: 6px; }
:deep(.ev-suppressed-row) { opacity: .55; background: var(--el-fill-color-light); }
:deep(.ev-row-highlight) { background: var(--el-color-primary-light-9) !important; }
.ev-evidence {
  font-size: 12px;
  color: var(--el-text-color-regular);
  line-height: 1.6;
  word-break: break-all;
}
.rem-plan {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px;
  margin-bottom: 10px;
}
</style>
