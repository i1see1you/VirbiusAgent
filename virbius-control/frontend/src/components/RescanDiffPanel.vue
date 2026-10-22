<template>
  <div class="rescan-panel">
    <div class="v-row">
      <el-select v-model="fingerprint" filterable clearable style="width:320px"
                 :placeholder="t('ev.fingerprint')" @change="loadSeries">
        <el-option v-for="fp in fingerprints" :key="fp" :label="fp" :value="fp" />
      </el-select>
      <el-button size="small" @click="reload">{{ t('ev.refresh') }}</el-button>
      <span class="v-hint">{{ t('ev.fingerprint-hint') }}</span>
    </div>

    <div v-if="series.length" class="ev-trend">
      <div v-for="r in series" :key="r.report_id" class="ev-trend-item"
           :title="r.report_id + ' · ' + r.scanned_at">
        <div class="ev-trend-bar"
             :class="'ev-grade-' + (r.summary?.grade || 'pass')"
             :style="{ height: barHeight(r.summary?.risk_score) }"></div>
        <span class="ev-trend-score">{{ r.summary?.risk_score ?? 0 }}</span>
      </div>
    </div>

    <div class="v-row" style="margin-top:12px">
      <el-select v-model="candidateId" filterable style="width:320px"
                 :placeholder="t('ev.pick-report')" @change="loadCandidate">
        <el-option v-for="r in series" :key="r.report_id"
                   :label="r.report_id + ' · ' + (r.summary?.risk_score ?? 0) + ' · ' + (r.summary?.grade || '')"
                   :value="r.report_id" />
      </el-select>
      <span class="v-hint">{{ t('ev.pick-report-hint') }}</span>
    </div>

    <el-alert v-if="candidate && !candidate.rescan" type="info" :closable="false" show-icon
              :title="t('ev.no-rescan')" style="margin-top:12px" />

    <template v-if="candidate?.rescan">
      <div class="v-row" style="margin-top:12px">
        <span class="v-hint">
          {{ t('ev.baseline') }}: {{ candidate.rescan.baseline_report_id }}
        </span>
        <el-tag v-if="candidate.rescan.baseline_version_drift" type="warning" size="small">
          {{ t('ev.version-drift') }}
        </el-tag>
      </div>

      <el-row :gutter="12" style="margin-top:8px">
        <el-col :span="6">
          <div class="ev-bucket ev-bucket-fixed">
            <div class="ev-bucket-title">{{ t('ev.fixed') }} · {{ candidate.rescan.fixed.length }}</div>
            <div v-for="id in candidate.rescan.fixed" :key="id" class="ev-bucket-item">
              {{ nameOf(id, baselineFindings) }}
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="ev-bucket ev-bucket-regressed">
            <div class="ev-bucket-title">{{ t('ev.regressed') }} · {{ candidate.rescan.regressed.length }}</div>
            <div v-for="id in candidate.rescan.regressed" :key="id" class="ev-bucket-item">
              {{ nameOf(id, candidateFindings) }}
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="ev-bucket ev-bucket-new">
            <div class="ev-bucket-title">{{ t('ev.new') }} · {{ candidate.rescan.new_findings.length }}</div>
            <div v-for="id in candidate.rescan.new_findings" :key="id" class="ev-bucket-item">
              {{ nameOf(id, candidateFindings) }}
            </div>
          </div>
        </el-col>
        <el-col :span="6">
          <div class="ev-bucket">
            <div class="ev-bucket-title">{{ t('ev.external-drift') }} · {{ candidate.rescan.external_drift.length }}</div>
            <div v-for="id in candidate.rescan.external_drift" :key="id" class="ev-bucket-item">
              {{ nameOf(id, candidateFindings) }}
            </div>
          </div>
        </el-col>
      </el-row>

      <el-table v-if="regressedRows.length" :data="regressedRows" size="small" border stripe
                style="margin-top:12px">
        <el-table-column :label="t('ev.col-risk')" min-width="260">
          <template #default="{ row }">{{ row.risk_name }}</template>
        </el-table-column>
        <el-table-column :label="t('ev.col-severity')" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="sevType(row.severity)">{{ row.severity }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('ev.col-check')" width="200" prop="check_id" />
        <el-table-column :label="t('ev.col-owasp')" width="160">
          <template #default="{ row }">{{ (row.owasp || []).join(', ') }}</template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { getReport, listReports, type Finding, type ReportMeta, type ScanReport } from '@/api/evaluation';

const { t } = useI18n();

const allReports = ref<ReportMeta[]>([]);
const fingerprint = ref('');
const series = ref<ReportMeta[]>([]);
const candidateId = ref('');
const candidate = ref<ScanReport | null>(null);
const baselineFindings = ref<Record<string, Finding>>({});

const fingerprints = computed(() =>
  Array.from(new Set(allReports.value.map(r => r.target_fingerprint).filter((x): x is string => !!x)))
);

const candidateFindings = computed<Record<string, Finding>>(() => {
  const map: Record<string, Finding> = {};
  for (const f of candidate.value?.findings || []) map[f.risk_id] = f;
  return map;
});

const regressedRows = computed(() =>
  (candidate.value?.rescan?.regressed || [])
    .map(id => candidateFindings.value[id])
    .filter((f): f is Finding => !!f)
);

function barHeight(score?: number): string {
  const s = Math.max(0, Math.min(100, score ?? 0));
  return Math.max(4, Math.round((s / 100) * 64)) + 'px';
}

function nameOf(riskId: string, map: Record<string, Finding>): string {
  return map[riskId]?.risk_name || riskId;
}

function sevType(sev?: string): string {
  switch (sev) {
    case 'critical': return 'danger';
    case 'high': return 'warning';
    case 'medium': return '';
    default: return 'info';
  }
}

async function reload() {
  allReports.value = await listReports();
  if (!fingerprint.value && fingerprints.value.length) {
    fingerprint.value = fingerprints.value[0];
  }
  await loadSeries();
}

async function loadSeries() {
  candidate.value = null;
  baselineFindings.value = {};
  candidateId.value = '';
  series.value = fingerprint.value
    ? await listReports(fingerprint.value)
    : allReports.value;
  // 时间正序展示趋势
  series.value = [...series.value].sort((a, b) => a.scanned_at.localeCompare(b.scanned_at));
}

async function loadCandidate() {
  if (!candidateId.value) return;
  candidate.value = await getReport(candidateId.value);
  baselineFindings.value = {};
  const baseId = candidate.value.rescan?.baseline_report_id;
  if (baseId) {
    try {
      const base = await getReport(baseId);
      const map: Record<string, Finding> = {};
      for (const f of base.findings) map[f.risk_id] = f;
      baselineFindings.value = map;
    } catch {
      // 基线报告可能已清理：fixed 项回退显示 risk_id
      baselineFindings.value = {};
    }
  }
}

onMounted(reload);
</script>

<style scoped>
.ev-trend {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  height: 84px;
  margin-top: 12px;
  padding: 8px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}
.ev-trend-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  min-width: 28px;
}
.ev-trend-bar {
  width: 18px;
  border-radius: 3px 3px 0 0;
  background: var(--el-color-success);
}
.ev-trend-bar.ev-grade-caution { background: var(--el-color-warning); }
.ev-trend-bar.ev-grade-review { background: var(--el-color-danger-light-3); }
.ev-trend-bar.ev-grade-blocked { background: var(--el-color-danger); }
.ev-trend-score {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.ev-bucket {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 8px;
  min-height: 72px;
}
.ev-bucket-fixed { border-color: var(--el-color-success-light-5); }
.ev-bucket-regressed { border-color: var(--el-color-danger-light-5); }
.ev-bucket-new { border-color: var(--el-color-warning-light-5); }
.ev-bucket-title { font-weight: 600; font-size: 12px; margin-bottom: 6px; }
.ev-bucket-item { font-size: 12px; line-height: 1.6; color: var(--el-text-color-regular); }
</style>
