<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('challenge.title') }}</h2>
    <p class="v-hint" v-html="t('challenge.desc')"></p>

    <div class="v-row">
      <el-button @click="load">{{ t('challenge.btn-refresh') }}</el-button>
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :name="'pending'">
        <template #label>{{ t('challenge.tab-pending') }} <el-badge :value="pending.length" :hidden="!pending.length" /></template>
        <el-table :data="paginatedPending" size="small" border stripe>
          <el-table-column :label="t('challenge.header-id')" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.challenge_id }}</code></template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-tool')" prop="tool_name" show-overflow-tooltip />
          <el-table-column :label="t('challenge.header-risk')" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="riskType(row.risk_score)">{{ riskLabel(row.risk_score) }}</el-tag>
              <span style="color:#94a3b8;margin-left:4px">({{ row.risk_score || 0 }})</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-rule')" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.rule_id || '-' }}</code></template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-session')" prop="session_id" show-overflow-tooltip>
            <template #default="{ row }">{{ row.session_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-created')">
            <template #default="{ row }">{{ fmtEpoch(row.created_at) }}</template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-expires')">
            <template #default="{ row }">
              <span :style="{ color: isExpired(row.expires_at) ? '#dc2626' : '' }">{{ fmtEpoch(row.expires_at) }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-actions')" width="180">
            <template #default="{ row }">
              <template v-if="isExpired(row.expires_at)"><span style="color:#94a3b8">{{ t('challenge.expired') }}</span></template>
              <template v-else>
                <el-button size="small" type="success" @click="approve(row.challenge_id)">{{ t('challenge.btn-approve') }}</el-button>
                <el-button size="small" type="danger" @click="reject(row.challenge_id)">{{ t('challenge.btn-reject') }}</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="totalPending > size" small background layout="prev, pager, next"
          v-model:current-page="pagePending" :page-size="size" :total="totalPending"
          @current-change="scrollTop" />
      </el-tab-pane>

      <el-tab-pane :name="'approved'">
        <template #label>{{ t('challenge.tab-approved') }} <el-badge :value="approved.length" :hidden="!approved.length" type="success" /></template>
        <el-table :data="paginatedApproved" size="small" border stripe>
          <el-table-column :label="t('challenge.header-id')" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.challenge_id }}</code></template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-tool')" prop="tool_name" show-overflow-tooltip />
          <el-table-column :label="t('challenge.header-risk')" width="120">
            <template #default="{ row }"><el-tag size="small" :type="riskType(row.risk_score)">{{ riskLabel(row.risk_score) }}</el-tag></template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-rule')" show-overflow-tooltip>
            <template #default="{ row }"><code>{{ row.rule_id || '-' }}</code></template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-session')" prop="session_id" show-overflow-tooltip>
            <template #default="{ row }">{{ row.session_id || '-' }}</template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-created')">
            <template #default="{ row }">{{ fmtEpoch(row.created_at) }}</template>
          </el-table-column>
          <el-table-column :label="t('challenge.header-approved-by')" prop="approved_by" />
          <el-table-column :label="t('challenge.header-approved-at')">
            <template #default="{ row }">{{ fmtEpoch(row.approved_at) }}</template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="totalApproved > size" small background layout="prev, pager, next"
          v-model:current-page="pageApproved" :page-size="size" :total="totalApproved"
          @current-change="scrollTop" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template><script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage } from 'element-plus';
import { useSessionStore } from '@/stores/session';
import { rawJson } from '@/api/client';

const { t } = useI18n();
const session = useSessionStore();

const size = ref(50);
const pagePending = ref(1);
const totalPending = ref(0);
const pageApproved = ref(1);
const totalApproved = ref(0);
const activeTab = ref('pending');
const pending = ref<any[]>([]);
const approved = ref<any[]>([]);

function scrollTop() { document.querySelector('.v-scroll')?.scrollTo(0, 0); }
const paginatedPending = computed(() => pending.value.slice((pagePending.value - 1) * size.value, pagePending.value * size.value));
const paginatedApproved = computed(() => approved.value.slice((pageApproved.value - 1) * size.value, pageApproved.value * size.value));
let timer: any = null;

function fmtEpoch(s: any): string {
  if (!s) return '-';
  return new Date(s * 1000).toLocaleString(undefined, { hour12: false });
}
function isExpired(exp: any): boolean { return exp && (Date.now() / 1000) > exp; }
function riskLabel(score: number) { return score >= 80 ? 'Critical' : score >= 50 ? 'High' : 'Medium'; }
function riskType(score: number): any { return score >= 80 ? 'danger' : score >= 50 ? 'warning' : 'info'; }

async function load() {
  try {
    const [p, a] = await Promise.all([
      rawJson<any[]>(`/api/v1/challenges?tenant_id=${encodeURIComponent(session.tenant)}&status=pending&max=50`),
      rawJson<any[]>(`/api/v1/challenges?tenant_id=${encodeURIComponent(session.tenant)}&status=approved&max=50`).catch(() => [])
    ]);
    pending.value = p || []; totalPending.value = pending.value.length; pagePending.value = 1;
    approved.value = a || []; totalApproved.value = approved.value.length; pageApproved.value = 1;
  } catch (e: any) { ElMessage.error(e.message); }
}

async function approve(id: string) {
  const approvedBy = window.prompt('Approver name:', 'operator');
  if (approvedBy === null) return;
  const comment = window.prompt('Approval comment (optional):', '') || '';
  try {
    const res = await rawJson<any>(`/api/v1/challenges/${id}/approve`, { method: 'POST', body: JSON.stringify({ approved_by: approvedBy, comment }) });
    if (res && res.token) {
      ElMessage.success(`Challenge approved! Token: ${res.token} (expires in 10 minutes)`);
      load();
    } else { ElMessage.error('Approve failed: ' + (res?.message || res?.status || 'unknown')); }
  } catch (e: any) { ElMessage.error('Approve error: ' + e.message); }
}

async function reject(id: string) {
  const rejectedBy = window.prompt('Rejector name:', 'operator');
  if (rejectedBy === null) return;
  const reason = window.prompt('Rejection reason:', '');
  if (!reason) { ElMessage.warning('Rejection reason is required'); return; }
  try {
    await rawJson(`/api/v1/challenges/${id}/reject`, { method: 'POST', body: JSON.stringify({ rejected_by: rejectedBy, reason }) });
    ElMessage.success('Challenge rejected');
    load();
  } catch (e: any) { ElMessage.error('Reject error: ' + e.message); }
}

function start() { load(); timer = setInterval(load, 5000); }
function stop() { if (timer) { clearInterval(timer); timer = null; } }

onMounted(start);
onUnmounted(stop);
watch(() => session.tenant, load);
</script>
