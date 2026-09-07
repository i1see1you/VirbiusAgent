<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('tenants.title') }}</h2>
    <p class="v-hint">{{ t('tenants.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('tenants.desc')"></p>
    </details>

    <div class="v-row">
      <el-input v-model="newTenantId" :placeholder="t('tenants.placeholder-id')" style="width:160px" />
      <el-input v-model="newTenantName" :placeholder="t('tenants.placeholder-name')" style="width:200px" />
      <el-button type="primary" @click="createTenant">{{ t('tenants.btn-create') }}</el-button>
      <el-button @click="loadPage">{{ t('common.refresh') }}</el-button>
    </div>

    <p class="v-empty-hint" style="margin:0 0 8px">{{ t('tenants.click-creds') }}</p>

    <el-table ref="tableRef" :data="tenantRows" size="small" border stripe highlight-current-row
      :empty-text="t('tenants.empty')">
      <el-table-column :label="t('tenants.header-id')">
        <template #default="{ row }"><code>{{ row.id }}</code></template>
      </el-table-column>
      <el-table-column :label="t('tenants.header-name')" prop="name" />
      <el-table-column :label="t('tenants.header-rules')" prop="ruleCount" width="90" />
      <el-table-column :label="t('tenants.header-created')" prop="created" width="180" />
      <el-table-column :label="t('tenants.header-actions')" width="180">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="switchTenant(row.id)">{{ t('common.switch') }}</el-button>
          <el-button size="small" link @click="openCreds(row.id)">{{ t('common.credential') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="credDrawerVisible"
      class="tenants-drawer"
      direction="rtl"
      size="560px"
      :title="t('tenants.cred-title', [credTenant])"
      :close-on-click-modal="true"
      :close-on-press-escape="true"
      @opened="syncTableHighlight"
    >
      <div class="v-row">
        <el-select v-model="issueRole" style="width:170px">
          <el-option value="tenant_viewer" label="tenant_viewer" />
          <el-option value="tenant_admin" label="tenant_admin" />
        </el-select>
        <el-input v-model="issueLabel" :placeholder="t('tenants.remark-label')" style="width:220px" />
      </div>
      <div class="v-row">
        <el-button type="primary" @click="issueCred">{{ t('tenants.btn-issue-key') }}</el-button>
        <el-button @click="issuePlatformCred">{{ t('tenants.btn-issue-platform') }}</el-button>
      </div>

      <el-table :data="credRows" size="small" border stripe :empty-text="t('tenants.empty-creds')">
        <el-table-column :label="t('tenants.header-prefix')">
          <template #default="{ row }"><code>{{ row.prefix }}****</code></template>
        </el-table-column>
        <el-table-column :label="t('tenants.header-role')" prop="role" width="140" />
        <el-table-column :label="t('tenants.header-remark')" prop="label" />
        <el-table-column :label="t('tenants.header-status')" prop="status" width="80" />
        <el-table-column :label="t('tenants.header-last-used')" prop="lastUsed" width="150" />
        <el-table-column :label="t('tenants.header-actions')" width="80">
          <template #default="{ row }">
            <el-button v-if="row.status === 'active'" size="small" link type="danger" @click="revokeCred(row)">{{ t('tenants.revoke') }}</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="keyDialogVisible" :title="t('tenants.key-dialog-title')" width="560px">
      <p class="v-hint" style="color:#dc2626">{{ t('tenants.key-warning') }}</p>
      <el-input v-model="keyDialogText" type="textarea" readonly :rows="5" />
      <template #footer>
        <el-button @click="copyKey">{{ t('tenants.copy-key') }}</el-button>
        <el-button type="primary" @click="keyDialogVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, nextTick } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { adminRoot, adminFetch } from '@/api/client';
import { field } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const tableRef = ref();
const newTenantId = ref('');
const newTenantName = ref('');
const tenantRows = ref<any[]>([]);
const credDrawerVisible = ref(false);
const credTenant = ref('');
const credRows = ref<any[]>([]);
const issueRole = ref('tenant_viewer');
const issueLabel = ref('');
const keyDialogVisible = ref(false);
const keyDialogText = ref('');

function credsUrl(tid: string) {
  return '/api/v1/admin/tenants/' + encodeURIComponent(tid) + '/api-credentials';
}

function syncTableHighlight() {
  const row = credTenant.value
    ? tenantRows.value.find((r: any) => r.id === credTenant.value)
    : undefined;
  tableRef.value?.setCurrentRow(row);
}

async function loadPage() {
  try {
    const data = await adminRoot<any[]>('/tenants');
    tenantRows.value = (data || []).map((x: any) => ({
      id: field(x, 'tenant_id', 'tenantId'),
      name: field(x, 'name') || '',
      ruleCount: field(x, 'rule_count', 'ruleCount') ?? 0,
      created: (field(x, 'created_at', 'createdAt') || '').slice(0, 19)
    }));
    nextTick(syncTableHighlight);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function loadCreds(tid: string) {
  credTenant.value = tid;
  try {
    const creds = await adminFetch<any[]>(credsUrl(tid));
    const platform = await adminFetch<any[]>('/api/v1/admin/platform/api-credentials').catch(() => []);
    const all = [...(creds || []), ...(platform || [])];
    credRows.value = all.map((c: any) => ({
      tid: field(c, 'tenant_id', 'tenantId'),
      cid: field(c, 'credential_id', 'credentialId'),
      prefix: field(c, 'key_prefix', 'keyPrefix') || '',
      role: field(c, 'role') || '',
      label: field(c, 'label') || '',
      status: field(c, 'status') || '',
      lastUsed: (field(c, 'last_used_at', 'lastUsedAt') || '-').slice(0, 19)
    }));
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function openCreds(tid: string) {
  credDrawerVisible.value = true;
  await loadCreds(tid);
  nextTick(syncTableHighlight);
}

async function createTenant() {
  if (!newTenantId.value.trim() || !newTenantName.value.trim()) {
    feedback.log(t('tenants.id-name-required'), 'warn'); return;
  }
  try {
    await adminRoot('/tenants', { method: 'POST', body: JSON.stringify({ tenant_id: newTenantId.value.trim(), name: newTenantName.value.trim() }) });
    newTenantId.value = ''; newTenantName.value = '';
    await loadPage();
    session.setTenant(tenantRows.value[tenantRows.value.length - 1]?.id || session.tenant);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function switchTenant(tid: string) {
  session.setTenant(tid);
  feedback.log({ switched: tid }, 'ok');
}

async function issueCred() {
  if (!credTenant.value) return;
  try {
    const data = await adminFetch<any>(credsUrl(credTenant.value), {
      method: 'POST',
      body: JSON.stringify({ role: issueRole.value, label: issueLabel.value.trim() || null })
    });
    if (data.api_key) {
      session.setApiKey(data.api_key);
      keyDialogText.value = data.api_key;
      keyDialogVisible.value = true;
    }
    await loadCreds(credTenant.value);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function issuePlatformCred() {
  try { await ElMessageBox.confirm(t('tenants.confirm-platform'), { type: 'warning' }); }
  catch { return; }
  try {
    const data = await adminFetch<any>('/api/v1/admin/platform/api-credentials', { method: 'POST', body: JSON.stringify({ role: 'platform_admin', label: issueLabel.value.trim() || null }) });
    if (data.api_key) {
      session.setApiKey(data.api_key);
      keyDialogText.value = data.api_key;
      keyDialogVisible.value = true;
    }
    if (credTenant.value) await loadCreds(credTenant.value);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function revokeCred(row: any) {
  try { await ElMessageBox.confirm(t('tenants.confirm-revoke'), { type: 'warning' }); }
  catch { return; }
  const path = row.tid === '*'
    ? '/api/v1/admin/platform/api-credentials/' + encodeURIComponent(row.cid) + '/revoke'
    : '/api/v1/admin/tenants/' + encodeURIComponent(row.tid) + '/api-credentials/' + encodeURIComponent(row.cid) + '/revoke';
  try {
    await adminFetch(path, { method: 'POST' });
    feedback.log({ revoked: row.cid }, 'ok');
    if (credTenant.value) await loadCreds(credTenant.value);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function copyKey() {
  try { await navigator.clipboard.writeText(keyDialogText.value); ElMessage.success(t('tenants.key-copied')); }
  catch { ElMessage.error('copy failed'); }
}

onMounted(loadPage);
watch([credTenant, credDrawerVisible], () => { nextTick(syncTableHighlight); });
</script>
