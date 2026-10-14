<template>
  <div class="v-card">
    <div class="al-title-row">
      <h2 class="v-card-title">{{ t('al.title') }}</h2>
      <el-tooltip placement="top" :show-after="200" :z-index="4200" popper-class="al-help-popper">
        <template #content>
          <p>{{ t('al.desc-short') }}</p>
          <p v-html="t('al.desc')"></p>
        </template>
        <button type="button" class="al-help" :aria-label="t('common.learn-more')">?</button>
      </el-tooltip>
    </div>

    <div class="v-row">
      <label>{{ t('al.filter-status') }}
        <el-select v-model="status" style="width:150px" @change="load">
          <el-option :label="t('al.status-open')" value="open" />
          <el-option :label="t('al.status-acked')" value="acked" />
          <el-option :label="t('al.status-all')" value="" />
        </el-select>
      </label>
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
    </div>

    <p class="v-hint">{{ summary }}</p>

    <el-table :data="alerts" size="small" border stripe :empty-text="t('al.empty')">
      <el-table-column :label="t('al.header-time')" width="170">
        <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column :label="t('al.header-alert-id')" show-overflow-tooltip>
        <template #default="{ row }"><code>{{ row.alert_id }}</code></template>
      </el-table-column>
      <el-table-column :label="t('al.header-scene')" prop="scene" width="100" />
      <el-table-column :label="t('al.header-rule')" prop="rule_id" show-overflow-tooltip />
      <el-table-column :label="t('al.header-reason')" prop="reason_code" width="150" />
      <el-table-column :label="t('al.header-risk')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="riskType(row.risk_score)">{{ row.risk_score || 0 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('al.header-action')" prop="effective_action" width="90" />
      <el-table-column :label="t('al.header-status')" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'acked' ? 'info' : 'warning'">
            {{ t('al.status-' + (row.status || 'open')) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('al.header-trace')" show-overflow-tooltip>
        <template #default="{ row }">{{ row.trace_id || '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('al.header-acked-by')" width="130">
        <template #default="{ row }">{{ row.acked_by || '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('al.header-actions')" width="190">
        <template #default="{ row }">
          <el-button size="small" :disabled="row.status === 'acked'" @click="ack(row)">{{ t('al.btn-ack') }}</el-button>
          <el-button size="small" @click="openBom(row)">{{ t('al.btn-bom') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>

  <el-dialog v-model="bomVisible" :title="t('al.bom-title')" width="780px">
    <p class="v-hint"><code>{{ bomTrace }}</code></p>
    <el-table v-if="bomEvents.length" :data="bomEvents" size="small" border stripe>
      <el-table-column :label="t('ac.header-time')" width="170">
        <template #default="{ row }">{{ fmtTime(row.intercepted_at) }}</template>
      </el-table-column>
      <el-table-column :label="t('ac.header-action')" prop="effective_action" width="90" />
      <el-table-column :label="t('ac.header-rule')" prop="rule_id" show-overflow-tooltip />
      <el-table-column :label="t('ac.header-reason')" prop="reason_code" width="150" />
      <el-table-column :label="t('ac.header-risk')" prop="max_risk_score" width="70" />
    </el-table>
    <p v-else class="v-hint">{{ t('common.no-data') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('ai.raw') }}</summary>
      <pre class="al-json">{{ bomRaw }}</pre>
    </details>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { admin } from '@/api/client';
import { useSessionStore } from '@/stores/session';
import { fmtTime } from '@/utils/format';

const { t } = useI18n();
const session = useSessionStore();

const status = ref('open');
const alerts = ref<any[]>([]);
const summary = ref('');
const bomVisible = ref(false);
const bomTrace = ref('');
const bomEvents = ref<any[]>([]);
const bomRaw = ref('');

function riskType(score: number): 'danger' | 'warning' | 'info' {
  return score >= 80 ? 'danger' : score >= 50 ? 'warning' : 'info';
}

async function load() {
  summary.value = t('common.loading');
  try {
    const q = status.value ? `?status=${encodeURIComponent(status.value)}&limit=200` : '?limit=200';
    const data = await admin<any[]>(`/alerts${q}`);
    alerts.value = data || [];
    summary.value = t('al.count', [alerts.value.length]);
  } catch (e: any) {
    summary.value = e.message;
    alerts.value = [];
  }
}

async function ack(row: any) {
  try {
    const r = await ElMessageBox.prompt(t('al.ack-prompt'), t('al.btn-ack'), {
      inputValue: 'operator',
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel')
    }) as { value?: string };
    const op = String(r.value || 'operator').trim() || 'operator';
    await admin(`/alerts/${encodeURIComponent(row.alert_id)}/ack?operator=${encodeURIComponent(op)}`, { method: 'POST' });
    ElMessage.success(t('al.acked-ok'));
    load();
  } catch (e: any) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message);
  }
}

async function openBom(row: any) {
  try {
    const bom = await admin<any>(`/alerts/${encodeURIComponent(row.alert_id)}/bom`);
    bomTrace.value = row.trace_id || '';
    bomEvents.value = bom?.audit_events || [];
    bomRaw.value = JSON.stringify(bom, null, 2);
    bomVisible.value = true;
  } catch (e: any) {
    ElMessage.error(e.message);
  }
}

onMounted(load);
watch(() => session.tenant, load);
</script>
<style scoped>
.al-title-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.al-title-row .v-card-title { margin: 0; }
.al-help {
  width: 22px; height: 22px; flex-shrink: 0; border-radius: 50%;
  border: 1px solid var(--v-border); background: #f8fafc; color: var(--v-muted);
  cursor: pointer; font-size: 12px; line-height: 1; padding: 0;
}
.al-help:hover, .al-help:focus-visible { color: var(--v-primary); border-color: var(--v-primary); outline: none; }
.al-json { max-height: 360px; overflow: auto; background: #f8fafc; border: 1px solid var(--v-border); border-radius: 6px; padding: 10px; font-size: 12px; }
</style>
<style>
.al-help-popper { max-width: 420px; line-height: 1.55; }
.al-help-popper p { margin: 0; }
.al-help-popper p + p { margin-top: 8px; }
</style>
