<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('license.title') }}</h2>
    <p class="v-hint" v-html="t('license.desc')"></p>

    <div class="v-row">
      <el-button type="primary" @click="issueFormVisible = !issueFormVisible">{{ t('license.btn-issue') }}</el-button>
      <el-button @click="loadPage">{{ t('common.refresh') }}</el-button>
      <el-button @click="rotateKey">{{ t('license.btn-rotate') }}</el-button>
    </div>

    <div v-if="issueFormVisible" class="v-card" style="background:#f8fafc">
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>{{ t('license.app-id') }} <el-input v-model="form.app_id" style="width:180px" /></label>
        <label>{{ t('license.agent-name') }} <el-input v-model="form.agent_name" style="width:180px" /></label>
      </div>
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>{{ t('license.allowed-tools') }} <el-input v-model="form.allowed_tools" style="width:300px" /></label>
      </div>
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>{{ t('license.risk-quota') }} <el-input-number v-model="form.risk_quota" :min="0" :max="100" style="width:110px" /></label>
        <label>{{ t('license.tool-rate-limit') }} <el-input-number v-model="form.tool_rate_limit" :min="1" style="width:110px" /></label>
        <label>{{ t('license.expiry-seconds') }} <el-input-number v-model="form.expiry_seconds" :min="60" style="width:130px" /></label>
      </div>
      <div class="v-row">
        <label>{{ t('license.description') }} <el-input v-model="form.description" style="width:300px" /></label>
      </div>
      <div class="v-row">
        <el-button type="primary" @click="issue">{{ t('license.btn-issue-confirm') }}</el-button>
        <el-button @click="issueFormVisible = false">{{ t('common.close') }}</el-button>
      </div>
    </div>

    <el-table :data="licenses" size="small" border stripe style="margin-top:12px">
      <el-table-column :label="t('license.header-id')">
        <template #default="{ row }"><code>{{ row.license_id }}</code></template>
      </el-table-column>
      <el-table-column :label="t('license.header-app')" prop="app_id" />
      <el-table-column :label="t('license.header-agent')" prop="agent_name" />
      <el-table-column :label="t('license.header-quota')" prop="risk_quota" width="90" />
      <el-table-column :label="t('license.header-rate')" prop="tool_rate_limit" width="90" />
      <el-table-column :label="t('license.header-expiry')" prop="expiry" width="170" />
      <el-table-column :label="t('license.header-status')" prop="status" width="90" />
      <el-table-column :label="t('license.header-actions')" width="140">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="showDetail(row)">{{ t('license.btn-detail') }}</el-button>
          <el-button size="small" link type="danger" @click="revoke(row)">{{ t('license.btn-revoke') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="publicKey" class="v-section">
      <h3>{{ t('license.key-section') }}</h3>
      <p class="v-hint" v-html="t('license.key-desc')"></p>
      <el-input v-model="publicKey" type="textarea" readonly :rows="6" />
      <div class="v-row" style="margin-top:8px">
        <el-button @click="copy(publicKey, t('license.key-copied'))">{{ t('license.copy-key') }}</el-button>
      </div>
    </div>

    <el-dialog v-model="jwtVisible" :title="t('license.jwt-label')" width="600px">
      <p class="v-hint" style="color:#dc2626">{{ t('license.jwt-warning') }}</p>
      <el-input v-model="jwtText" type="textarea" readonly :rows="10" />
      <template #footer>
        <el-button @click="copy(jwtText, t('license.jwt-copied'))">{{ t('license.copy-jwt') }}</el-button>
        <el-button type="primary" @click="jwtVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" :title="t('license.detail-title')" width="540px">
      <table class="el-table__body" style="width:100%;font-size:13px">
        <tr v-for="r in detailRows" :key="r.k"><td style="width:130px;padding:6px">{{ r.k }}</td><td style="padding:6px" v-html="r.v"></td></tr>
      </table>
      <div style="margin-top:8px">
        <label style="font-weight:600">{{ t('license.sig-hash-label') }}</label>
        <div class="mono" style="font-size:12px;color:#64748b;padding:8px;background:#f1f5f9;border-radius:4px">{{ detailSigHash }}</div>
        <p class="v-hint">{{ t('license.sig-hash-hint') }}</p>
      </div>
      <template #footer>
        <el-button type="danger" @click="revoke(detailLicense!)">{{ t('license.btn-revoke') }}</el-button>
        <el-button @click="detailVisible = false">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox, ElMessageBoxOptions } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const licenses = ref<any[]>([]);
const publicKey = ref('');
const issueFormVisible = ref(false);
const jwtVisible = ref(false);
const jwtText = ref('');
const detailVisible = ref(false);
const detailLicense = ref<any | null>(null);
const detailSigHash = ref('');
const form = reactive<any>({ app_id: '', agent_name: '', allowed_tools: '', risk_quota: 60, tool_rate_limit: 50, expiry_seconds: 31536000, description: '' });

async function loadPage() {
  try {
    const [list, key] = await Promise.all([
      admin<any[]>('/licenses/list'),
      admin<any>('/licenses/public-key').catch(() => null)
    ]);
    licenses.value = (list || []).map((l: any) => ({
      license_id: field(l, 'license_id', 'licenseId'),
      app_id: field(l, 'app_id', 'appId'),
      agent_name: field(l, 'agent_name', 'agentName'),
      risk_quota: field(l, 'risk_quota', 'riskQuota'),
      tool_rate_limit: field(l, 'tool_rate_limit', 'toolRateLimit'),
      expiry: (field(l, 'expiry') || '').slice(0, 19),
      issued_at: (field(l, 'issued_at', 'issuedAt') || '').slice(0, 19),
      status: field(l, 'status'),
      description: field(l, 'description'),
      agent_aid: field(l, 'agent_aid', 'agentAid'),
      allowed_tools: field(l, 'allowed_tools') || [],
      signature_hash: field(l, 'signature_hash', 'signatureHash')
    }));
    publicKey.value = key?.public_key_pem || '';
    feedback.log(t('license.refreshed'));
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function issue() {
  if (!form.app_id.trim()) { feedback.log(t('license.app-id-required'), 'warn'); return; }
  const body = {
    app_id: form.app_id.trim(),
    agent_name: form.agent_name.trim(),
    allowed_tools: String(form.allowed_tools || '').split(',').map(s => s.trim()).filter(Boolean),
    risk_quota: form.risk_quota,
    tool_rate_limit: form.tool_rate_limit,
    expiry_seconds: form.expiry_seconds,
    description: form.description.trim() || null
  };
  try {
    const data = await admin<any>('/licenses/issue', { method: 'POST', body: JSON.stringify(body) });
    issueFormVisible.value = false;
    if (data.jwt) {
      jwtText.value = data.jwt;
      jwtVisible.value = true;
    }
    await loadPage();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function showDetail(l: any) {
  detailLicense.value = l;
  const full = l.signature_hash || '';
  detailSigHash.value = full ? 'sha256:' + full.slice(0, 16) + '...' : '-';
}
const detailRows = computed(() => {
  const l = detailLicense.value;
  if (!l) return [];
  return [
    { k: t('license.header-id'), v: '<code>' + l.license_id + '</code>' },
    { k: t('license.header-app'), v: '<code>' + l.app_id + '</code>' },
    { k: t('license.agent-name'), v: l.agent_name },
    { k: 'Agent AID', v: '<code>' + (l.agent_aid || '') + '</code>' },
    { k: t('license.allowed-tools'), v: (l.allowed_tools || []).join(', ') },
    { k: t('license.risk-quota'), v: l.risk_quota },
    { k: t('license.tool-rate-limit'), v: l.tool_rate_limit },
    { k: t('license.header-issued'), v: l.issued_at },
    { k: t('license.header-expiry'), v: l.expiry },
    { k: t('license.header-status'), v: l.status },
    { k: t('license.description'), v: l.description || '' }
  ];
});

async function revoke(l: any) {
  const reason = window.prompt(t('license.revoke-reason-prompt'), 'manual_revoke');
  if (reason === null) return;
  try {
    await admin('/licenses/' + encodeURIComponent(l.license_id) + '/revoke', { method: 'POST', body: JSON.stringify({ reason: reason || 'manual_revoke' }) });
    detailVisible.value = false;
    await loadPage();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function rotateKey() {
  try { await ElMessageBox.confirm(t('license.rotate-confirm'), { type: 'warning' } as ElMessageBoxOptions); }
  catch { return; }
  try {
    await admin('/licenses/rotate-key', { method: 'POST' });
    await loadPage();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function copy(text: string, successMsg: string) {
  try { await navigator.clipboard.writeText(text); ElMessage.success(successMsg); }
  catch { ElMessage.error('copy failed'); }
}

onMounted(loadPage);
watch(() => session.tenant, loadPage);
</script>
