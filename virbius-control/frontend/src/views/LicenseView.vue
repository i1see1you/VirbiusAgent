<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('license.title') }}</h2>
    <p class="v-hint">{{ t('license.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint">{{ t('license.desc') }}</p>
    </details>

    <div class="v-row">
      <el-radio-group v-model="statusFilter" size="small">
        <el-radio-button value="active">{{ t('license.filter-active') }}</el-radio-button>
        <el-radio-button value="all">{{ t('license.filter-all') }}</el-radio-button>
      </el-radio-group>
      <span v-if="statusFilter === 'active' && revokedCount > 0" class="v-hint" style="margin:0">
        {{ t('license.revoked-count', [revokedCount]) }}
      </span>
      <el-button type="primary" @click="openIssue">{{ t('license.btn-issue') }}</el-button>
      <el-button @click="loadPage">{{ t('common.refresh') }}</el-button>
    </div>
    <p class="v-empty-hint" style="margin:12px 0 8px">{{ t('license.click-row') }}</p>

    <el-table :data="visibleLicenses" size="small" border stripe highlight-current-row
      @row-click="onRowClick" :empty-text="emptyText">
      <el-table-column :label="t('license.header-agent')" min-width="140">
        <template #default="{ row }">{{ row.agent_name || t('common.none') }}</template>
      </el-table-column>
      <el-table-column :label="t('license.header-app')" min-width="120">
        <template #default="{ row }"><code>{{ row.app_id }}</code></template>
      </el-table-column>
      <el-table-column :label="t('license.header-tools')" min-width="180">
        <template #default="{ row }">
          <template v-if="toolList(row).length">
            <el-tag v-for="name in toolList(row).slice(0, 4)" :key="name" size="small" class="license-tool-tag">{{ name }}</el-tag>
            <span v-if="toolList(row).length > 4" class="v-hint" style="margin:0">+{{ toolList(row).length - 4 }}</span>
          </template>
          <span v-else class="v-hint" style="margin:0">{{ t('common.none') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('license.header-quota')" width="80" prop="risk_quota" />
      <el-table-column :label="t('license.header-expiry')" width="170">
        <template #default="{ row }">{{ expiryLabel(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('license.header-status')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'revoked' ? 'info' : 'success'" effect="plain">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('license.header-actions')" width="80">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'revoked'" size="small" link type="danger" @click.stop="revoke(row)">
            {{ t('license.btn-revoke') }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <details class="v-hint-more license-keys">
      <summary>{{ t('license.keys-summary') }}</summary>
      <p class="v-hint">{{ t('license.key-desc') }}</p>
      <el-input v-if="publicKey" v-model="publicKey" type="textarea" readonly :rows="5" class="mono" />
      <div class="v-row" style="margin-top:8px">
        <el-button :disabled="!publicKey" @click="copy(publicKey, t('license.key-copied'))">{{ t('license.copy-key') }}</el-button>
        <el-button type="danger" plain @click="rotateKey">{{ t('license.btn-rotate') }}</el-button>
      </div>
    </details>

    <el-dialog
      v-model="jwtVisible"
      class="license-jwt-dialog"
      :title="t('license.jwt-title')"
      width="640px"
      append-to-body
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
      :z-index="4200"
    >
      <p class="license-jwt-warn">{{ t('license.jwt-warning') }}</p>
      <pre class="license-jwt-code">{{ jwtText }}</pre>
      <template #footer>
        <el-button type="primary" @click="copyJwt">{{ t('license.copy-jwt') }}</el-button>
        <el-button @click="closeJwt">{{ t('license.jwt-close') }}</el-button>
      </template>
    </el-dialog>

    <Teleport to="body">
      <Transition name="license-slide">
        <div v-if="panelVisible" class="license-mask" @click.self="closePanel">
          <aside class="license-panel" @click.stop>
            <header class="license-panel-head">
              <h2>{{ panelTitle }}</h2>
              <button type="button" class="license-panel-close" @click="closePanel">{{ t('common.close') }}</button>
            </header>

            <div v-if="panelMode === 'issue'" class="license-panel-body">
              <div class="v-section" style="margin-top:0;padding-top:0;border-top:none">
                <h3>{{ t('license.section-identity') }}</h3>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-agent') }}</span>
                  <el-input v-model="form.agent_name" :placeholder="t('license.placeholder-agent')" />
                </div>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-app') }}</span>
                  <el-input v-model="form.app_id" :placeholder="t('license.placeholder-app')" />
                </div>
                <p class="v-hint">{{ t('license.app-hint') }}</p>
                <p v-if="activeForApp" class="license-warn">{{ t('license.app-taken', [activeForApp.license_id]) }}</p>
              </div>

              <div class="v-section">
                <h3>{{ t('license.section-perms') }}</h3>
                <el-select
                  v-model="form.allowed_tools"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  collapse-tags
                  collapse-tags-tooltip
                  popper-class="license-select-popper"
                  :placeholder="t('license.tools-placeholder')"
                  style="width:100%"
                >
                  <el-option v-for="name in toolNames" :key="name" :label="name" :value="name" />
                </el-select>
                <p class="v-hint">
                  {{ t('license.tools-hint') }}
                  <router-link to="/tools">{{ t('license.tools-link') }}</router-link>
                </p>
              </div>

              <div class="v-section">
                <h3>{{ t('license.section-limits') }}</h3>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-quota') }}</span>
                  <el-input-number v-model="form.risk_quota" :min="0" :max="100" style="width:140px" />
                </div>
                <p class="v-hint">{{ t('license.quota-hint') }}</p>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-rate') }}</span>
                  <el-input-number v-model="form.tool_rate_limit" :min="1" style="width:140px" />
                </div>
                <p class="v-hint">{{ t('license.rate-hint') }}</p>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-expiry') }}</span>
                  <div class="license-field-ctrl">
                    <el-select v-model="form.expiry_preset" popper-class="license-select-popper" style="width:180px">
                      <el-option value="30d" :label="t('license.expiry-30d')" />
                      <el-option value="90d" :label="t('license.expiry-90d')" />
                      <el-option value="1y" :label="t('license.expiry-1y')" />
                      <el-option value="2y" :label="t('license.expiry-2y')" />
                      <el-option value="custom" :label="t('license.expiry-custom')" />
                    </el-select>
                    <el-input-number v-if="form.expiry_preset === 'custom'" v-model="form.expiry_days" :min="1" :max="3650" style="width:130px" />
                    <span v-if="form.expiry_preset === 'custom'" class="v-hint" style="margin:0">{{ t('license.expiry-days') }}</span>
                  </div>
                </div>
                <div class="license-field">
                  <span class="license-field-label">{{ t('license.label-desc') }}</span>
                  <el-input v-model="form.description" :placeholder="t('license.placeholder-desc')" />
                </div>
              </div>
            </div>

            <div v-else class="license-panel-body">
              <el-tag size="small" :type="detailLicense?.status === 'revoked' ? 'info' : 'success'" effect="plain">
                {{ statusLabel(detailLicense?.status) }}
              </el-tag>
              <dl class="license-dl">
                <div class="license-dl-row"><dt>{{ t('license.label-agent') }}</dt><dd>{{ detailLicense?.agent_name || t('common.none') }}</dd></div>
                <div class="license-dl-row"><dt>{{ t('license.label-app') }}</dt><dd><code>{{ detailLicense?.app_id }}</code></dd></div>
                <div class="license-dl-row"><dt>{{ t('license.detail-id') }}</dt><dd><code>{{ detailLicense?.license_id }}</code></dd></div>
                <div class="license-dl-row"><dt>Agent AID</dt><dd><code>{{ detailLicense?.agent_aid || t('common.none') }}</code></dd></div>
                <div class="license-dl-row">
                  <dt>{{ t('license.section-perms') }}</dt>
                  <dd>
                    <template v-if="toolList(detailLicense).length">
                      <el-tag v-for="name in toolList(detailLicense)" :key="name" size="small" class="license-tool-tag">{{ name }}</el-tag>
                    </template>
                    <span v-else>{{ t('common.none') }}</span>
                  </dd>
                </div>
                <div class="license-dl-row"><dt>{{ t('license.label-quota') }}</dt><dd>{{ detailLicense?.risk_quota }}</dd></div>
                <div class="license-dl-row"><dt>{{ t('license.label-rate') }}</dt><dd>{{ detailLicense?.tool_rate_limit }} / {{ t('license.rate-unit') }}</dd></div>
                <div class="license-dl-row"><dt>{{ t('license.header-issued') }}</dt><dd>{{ humanTime(detailLicense?.issued_at) }}</dd></div>
                <div class="license-dl-row"><dt>{{ t('license.header-expiry') }}</dt><dd>{{ expiryLabel(detailLicense) }}</dd></div>
                <div class="license-dl-row"><dt>{{ t('license.label-desc') }}</dt><dd>{{ detailLicense?.description || t('common.none') }}</dd></div>
              </dl>
              <p class="v-hint">{{ t('license.detail-hint') }}</p>
              <div class="v-section">
                <h3>{{ t('license.sig-hash-label') }}</h3>
                <div class="mono license-hash">{{ detailSigHash }}</div>
                <p class="v-hint">{{ t('license.sig-hash-hint') }}</p>
              </div>
            </div>

            <footer v-if="panelMode === 'issue' || (panelMode === 'detail' && detailLicense?.status !== 'revoked')" class="license-panel-foot">
              <el-button v-if="panelMode === 'issue'" type="primary" :disabled="!!activeForApp" @click="issue">
                {{ t('license.btn-issue-confirm') }}
              </el-button>
              <el-button v-if="panelMode === 'detail'" type="danger" @click="revoke(detailLicense)">
                {{ t('license.btn-revoke') }}
              </el-button>
            </footer>
          </aside>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox, ElMessageBoxOptions } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field, parseUtc } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const licenses = ref<any[]>([]);
const catalogTools = ref<any[]>([]);
const publicKey = ref('');
const statusFilter = ref<'active' | 'all'>('active');
const panelVisible = ref(false);
const panelMode = ref<'issue' | 'detail'>('issue');
const jwtVisible = ref(false);
const jwtText = ref('');
const detailLicense = ref<any | null>(null);
const form = reactive<any>({
  app_id: '',
  agent_name: '',
  allowed_tools: [] as string[],
  risk_quota: 60,
  tool_rate_limit: 50,
  expiry_preset: '1y',
  expiry_days: 365,
  description: ''
});

const toolNames = computed(() =>
  catalogTools.value.map((x: any) => field(x, 'tool_name', 'toolName') || '').filter(Boolean)
);

const revokedCount = computed(() => licenses.value.filter((l: any) => l.status === 'revoked').length);

const visibleLicenses = computed(() =>
  statusFilter.value === 'active'
    ? licenses.value.filter((l: any) => l.status !== 'revoked')
    : licenses.value
);

const emptyText = computed(() => {
  if (statusFilter.value === 'active' && revokedCount.value > 0) {
    return t('license.empty-active-has-revoked', [revokedCount.value]);
  }
  return t('license.empty');
});

const activeForApp = computed(() => {
  const id = String(form.app_id || '').trim();
  if (!id || panelMode.value !== 'issue') return null;
  return licenses.value.find((l: any) => l.status === 'active' && l.app_id === id) || null;
});

const panelTitle = computed(() => {
  if (panelMode.value === 'issue') return t('license.issue-title');
  const name = detailLicense.value?.agent_name || detailLicense.value?.app_id || '';
  return name ? t('license.detail-title-named', [name]) : t('license.detail-title');
});

const detailSigHash = computed(() => {
  const full = detailLicense.value?.signature_hash || '';
  return full ? 'sha256:' + full.slice(0, 16) + '...' : t('common.none');
});

function toolList(row: any): string[] {
  const raw = row?.allowed_tools;
  if (Array.isArray(raw)) return raw.map(String).filter(Boolean);
  if (typeof raw === 'string' && raw.trim()) return raw.split(/[,，]/).map(s => s.trim()).filter(Boolean);
  return [];
}

function statusLabel(st: string) {
  if (st === 'revoked') return t('license.status-revoked');
  if (st === 'active') return t('license.status-active');
  return st || t('common.none');
}

function humanTime(s: any) {
  const d = parseUtc(s);
  if (!d) return s ? String(s).replace('T', ' ').slice(0, 16) : t('common.none');
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function expiryLabel(row: any) {
  if (!row?.expiry) return t('common.none');
  const d = parseUtc(row.expiry);
  const text = humanTime(row.expiry);
  if (d && d.getTime() < Date.now()) return text + ' · ' + t('license.expired');
  return text;
}

function expirySeconds(): number {
  const map: Record<string, number> = { '30d': 30, '90d': 90, '1y': 365, '2y': 730 };
  const days = form.expiry_preset === 'custom' ? Number(form.expiry_days) || 365 : (map[form.expiry_preset] || 365);
  return days * 86400;
}

function closePanel() {
  panelVisible.value = false;
}

function openIssue() {
  form.app_id = '';
  form.agent_name = '';
  form.allowed_tools = [];
  form.risk_quota = 60;
  form.tool_rate_limit = 50;
  form.expiry_preset = '1y';
  form.expiry_days = 365;
  form.description = '';
  detailLicense.value = null;
  panelMode.value = 'issue';
  panelVisible.value = true;
}

function showDetail(l: any) {
  if (!l) return;
  detailLicense.value = l;
  panelMode.value = 'detail';
  panelVisible.value = true;
}

function onRowClick(row: any) {
  showDetail(row);
}

function onKeydown(e: KeyboardEvent) {
  if (e.key !== 'Escape') return;
  if (jwtVisible.value) return;
  if (panelVisible.value) closePanel();
}

async function loadPage() {
  try {
    const [list, key, tools] = await Promise.all([
      admin<any[]>('/licenses/list'),
      admin<any>('/licenses/public-key').catch(() => null),
      admin<any[]>('/tools').catch(() => [])
    ]);
    licenses.value = (list || []).map((l: any) => ({
      license_id: field(l, 'license_id', 'licenseId'),
      app_id: field(l, 'app_id', 'appId'),
      agent_name: field(l, 'agent_name', 'agentName'),
      risk_quota: field(l, 'risk_quota', 'riskQuota'),
      tool_rate_limit: field(l, 'tool_rate_limit', 'toolRateLimit'),
      expiry: field(l, 'expiry'),
      issued_at: field(l, 'issued_at', 'issuedAt'),
      status: field(l, 'status'),
      description: field(l, 'description'),
      agent_aid: field(l, 'agent_aid', 'agentAid'),
      allowed_tools: field(l, 'allowed_tools') || [],
      signature_hash: field(l, 'signature_hash', 'signatureHash')
    }));
    publicKey.value = key?.public_key_pem || '';
    catalogTools.value = tools || [];
    if (detailLicense.value) {
      const next = licenses.value.find((x: any) => x.license_id === detailLicense.value.license_id);
      if (next) detailLicense.value = next;
    }
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function issue() {
  if (!form.app_id.trim()) { ElMessage.warning(t('license.app-id-required')); return; }
  if (activeForApp.value) { ElMessage.warning(t('license.app-taken', [activeForApp.value.license_id])); return; }
  const body = {
    app_id: form.app_id.trim(),
    agent_name: form.agent_name.trim(),
    allowed_tools: (form.allowed_tools || []).map((s: string) => String(s).trim()).filter(Boolean),
    risk_quota: form.risk_quota,
    tool_rate_limit: form.tool_rate_limit,
    expiry_seconds: expirySeconds(),
    description: form.description.trim() || null
  };
  try {
    const data = await admin<any>('/licenses/issue', { method: 'POST', body: JSON.stringify(body) });
    panelVisible.value = false;
    if (data.jwt) {
      jwtText.value = data.jwt;
      jwtVisible.value = true;
    }
    await loadPage();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function revoke(l: any) {
  if (!l) return;
  if (l.status === 'revoked') {
    ElMessage.warning(t('license.already-revoked'));
    return;
  }
  try {
    await ElMessageBox.confirm(t('license.confirm-revoke'), t('license.revoke-title'), {
      type: 'warning',
      zIndex: 5000
    });
  } catch { return; }
  try {
    await admin('/licenses/' + encodeURIComponent(l.license_id) + '/revoke', {
      method: 'POST',
      body: JSON.stringify({ reason: 'manual_revoke' })
    });
    ElMessage.success(t('license.revoked'));
    await loadPage();
    if (detailLicense.value?.license_id === l.license_id) {
      const next = licenses.value.find((x: any) => x.license_id === l.license_id);
      if (next) detailLicense.value = next;
    }
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function rotateKey() {
  try { await ElMessageBox.confirm(t('license.rotate-confirm'), t('license.btn-rotate'), { type: 'warning' } as ElMessageBoxOptions); }
  catch { return; }
  try {
    await admin('/licenses/rotate-key', { method: 'POST' });
    ElMessage.success(t('license.rotate-ok'));
    await loadPage();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function copy(text: string, successMsg: string) {
  try { await navigator.clipboard.writeText(text); ElMessage.success(successMsg); }
  catch { ElMessage.error(t('license.copy-failed')); }
}

async function copyJwt() {
  await copy(jwtText.value, t('license.jwt-copied'));
}

function closeJwt() {
  jwtVisible.value = false;
  jwtText.value = '';
}

onMounted(() => {
  loadPage();
  window.addEventListener('keydown', onKeydown);
});
onUnmounted(() => window.removeEventListener('keydown', onKeydown));
watch(() => session.tenant, loadPage);
</script>

<style scoped>
.license-keys { margin-top: 20px; }
.license-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 10px;
  font-size: 13px;
  color: #334155;
}
.license-field-label { line-height: 1.4; }
.license-field-ctrl {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.license-warn { color: #b45309; font-size: 12px; margin: 0 0 8px; }
.license-tool-tag { margin: 0 4px 4px 0; }
.license-dl { margin: 12px 0 0; }
.license-dl-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 8px 12px;
  padding: 8px 0;
  border-bottom: 1px solid var(--v-border);
  font-size: 13px;
}
.license-dl-row dt { color: #64748b; }
.license-dl-row dd { margin: 0; word-break: break-all; }
.license-hash {
  font-size: 12px;
  color: #64748b;
  padding: 8px;
  background: #f1f5f9;
  border-radius: 4px;
}
</style>
<style>
.license-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(15, 23, 42, 0.35);
}
.license-panel {
  position: absolute;
  top: 0;
  right: 0;
  width: 560px;
  height: 100%;
  background: #fff;
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.16);
  display: flex;
  flex-direction: column;
}
.license-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--v-border);
}
.license-panel-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.license-panel-close {
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 13px;
  padding: 4px 6px;
}
.license-panel-close:hover { color: #0f172a; }
.license-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px 20px;
}
.license-panel-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
  border-top: 1px solid var(--v-border);
  padding: 10px 16px;
}
.license-slide-enter-active,
.license-slide-leave-active { transition: opacity 0.2s ease; }
.license-slide-enter-active .license-panel,
.license-slide-leave-active .license-panel { transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1); }
.license-slide-enter-from,
.license-slide-leave-to { opacity: 0; }
.license-slide-enter-from .license-panel,
.license-slide-leave-to .license-panel { transform: translateX(100%); }
.license-select-popper { z-index: 4100 !important; }
.license-jwt-warn {
  margin: 0 0 12px;
  color: #b91c1c;
  font-size: 13px;
  font-weight: 600;
}
.license-jwt-code {
  margin: 0;
  padding: 12px 14px;
  max-height: 280px;
  overflow: auto;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
