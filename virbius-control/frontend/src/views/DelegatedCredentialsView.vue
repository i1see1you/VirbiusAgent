<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('dcred.title') }}</h2>
    <p class="v-empty-hint">{{ t('dcred.hint') }}</p>

    <div class="v-row">
      <el-button type="primary" @click="openCreate">{{ t('dcred.btn-create') }}</el-button>
      <el-button :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      <el-button type="danger" plain @click="revealKek">{{ t('dcred.btn-reveal-kek') }}</el-button>
    </div>

    <el-table :data="rows" size="small" border stripe v-loading="loading" :empty-text="t('common.no-data')">
      <el-table-column :label="t('dcred.col-handle')" prop="handle" width="130" />
      <el-table-column :label="t('dcred.col-host')" prop="host" min-width="170" />
      <el-table-column :label="t('dcred.col-header')" prop="header_name" width="120" />
      <el-table-column :label="t('dcred.col-prefix')" prop="header_prefix" width="90" />
      <el-table-column :label="t('dcred.col-status')" width="90">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.status === 'revoked' ? 'info' : 'success'">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('dcred.col-updated')" width="170">
        <template #default="{ row }">{{ fmtTime(row.updated_at) }}</template>
      </el-table-column>
      <el-table-column :label="t('dcred.col-actions')" width="210">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openRotate(row)">{{ t('dcred.btn-rotate') }}</el-button>
          <el-button v-if="row.status === 'active'" size="small" link type="warning" @click="revoke(row)">
            {{ t('dcred.btn-revoke') }}
          </el-button>
          <el-button size="small" link type="danger" @click="remove(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlgOpen" :title="dlgMode === 'create' ? t('dcred.form-create-title') : t('dcred.form-rotate-title')" width="480px">
      <el-form label-width="100px">
        <el-form-item :label="t('dcred.form-handle')">
          <el-input v-model="form.handle" :disabled="dlgMode === 'rotate'" :placeholder="t('dcred.ph-handle')" />
        </el-form-item>
        <el-form-item :label="t('dcred.form-host')">
          <el-input v-model="form.host" :placeholder="t('dcred.ph-host')" />
        </el-form-item>
        <el-form-item :label="t('dcred.form-header')">
          <el-input v-model="form.headerName" :placeholder="t('dcred.ph-header')" />
        </el-form-item>
        <el-form-item :label="t('dcred.form-prefix')">
          <el-input v-model="form.headerPrefix" :placeholder="t('dcred.ph-prefix')" />
        </el-form-item>
        <el-form-item :label="t('dcred.form-value')">
          <el-input v-model="form.secret" type="password" show-password :placeholder="t('dcred.ph-value')" />
          <div class="v-hint" style="margin:0">{{ t('dcred.value-rule') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlgOpen = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="kekOpen" :title="t('dcred.kek-title')" width="560px">
      <p class="v-empty-hint" style="margin-top:0">{{ t('dcred.kek-note') }}</p>
      <pre class="dcred-kek">{{ kek }}</pre>
      <el-button @click="copyKek">{{ t('dcred.kek-copy') }}</el-button>
      <template #footer>
        <el-button type="primary" @click="closeKek">{{ t('common.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox, ElMessageBoxOptions } from 'element-plus';
import { admin, jsonBody } from '@/api/client';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';

interface Row {
  handle: string;
  host: string;
  header_name: string;
  header_prefix: string;
  status: string;
  updated_at: string;
}

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const rows = ref<Row[]>([]);
const loading = ref(false);
const saving = ref(false);
const dlgOpen = ref(false);
const dlgMode = ref<'create' | 'rotate'>('create');
const kekOpen = ref(false);
const kek = ref('');

const form = reactive({ handle: '', host: '', headerName: 'Authorization', headerPrefix: 'Bearer ', secret: '' });

async function load() {
  loading.value = true;
  try {
    rows.value = await admin<Row[]>('/delegated-credentials');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  dlgMode.value = 'create';
  Object.assign(form, { handle: '', host: '', headerName: 'Authorization', headerPrefix: 'Bearer ', secret: '' });
  dlgOpen.value = true;
}

function openRotate(row: Row) {
  dlgMode.value = 'rotate';
  Object.assign(form, { handle: row.handle, host: row.host, headerName: row.header_name, headerPrefix: row.header_prefix, secret: '' });
  dlgOpen.value = true;
}

async function submit() {
  if (!form.handle.trim() || !form.host.trim() || !form.secret) {
    ElMessage.warning(t('dcred.form-required'));
    return;
  }
  if (new TextEncoder().encode(form.secret).length < 16) {
    ElMessage.warning(t('dcred.value-short'));
    return;
  }
  saving.value = true;
  try {
    const body = {
      handle: form.handle.trim(),
      host: form.host.trim(),
      header_name: form.headerName.trim(),
      header_prefix: form.headerPrefix,
      value: form.secret
    };
    if (dlgMode.value === 'create') {
      await admin('/delegated-credentials', { method: 'POST', body: jsonBody(body) });
    } else {
      await admin('/delegated-credentials/' + encodeURIComponent(form.handle), { method: 'PUT', body: jsonBody(body) });
    }
    ElMessage.success(t('dcred.saved'));
    form.secret = '';
    dlgOpen.value = false;
    await load();
  } catch (e: any) {
    feedback.log(e.message, 'err');
  } finally {
    saving.value = false;
  }
}

async function revoke(row: Row) {
  try {
    await ElMessageBox.confirm(t('dcred.confirm-revoke', [row.handle]), t('dcred.btn-revoke'), { type: 'warning' } as ElMessageBoxOptions);
  } catch {
    return;
  }
  try {
    await admin('/delegated-credentials/' + encodeURIComponent(row.handle) + '/revoke', { method: 'POST' });
    ElMessage.success(t('dcred.revoked'));
    await load();
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function remove(row: Row) {
  try {
    await ElMessageBox.confirm(t('dcred.confirm-delete', [row.handle]), t('common.delete'), { type: 'warning' } as ElMessageBoxOptions);
  } catch {
    return;
  }
  try {
    await admin('/delegated-credentials/' + encodeURIComponent(row.handle), { method: 'DELETE' });
    ElMessage.success(t('dcred.deleted'));
    await load();
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function revealKek() {
  try {
    await ElMessageBox.confirm(t('dcred.confirm-kek'), t('dcred.btn-reveal-kek'), { type: 'warning' } as ElMessageBoxOptions);
  } catch {
    return;
  }
  try {
    const data = await admin<{ kek_base64: string }>('/delegated-credentials/kek/reveal', { method: 'POST' });
    kek.value = data.kek_base64;
    kekOpen.value = true;
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function copyKek() {
  try {
    await navigator.clipboard.writeText(kek.value);
    ElMessage.success(t('dcred.copied'));
  } catch {
    ElMessage.warning(t('dcred.copy-failed'));
  }
}

function closeKek() {
  kek.value = '';
  kekOpen.value = false;
}

function statusLabel(s: string) {
  return s === 'revoked' ? t('dcred.status-revoked') : t('dcred.status-active');
}

function fmtTime(iso: string) {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleString();
}

onMounted(load);
watch(() => session.tenant, load);
</script>

<style scoped>
.dcred-kek {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  padding: 12px;
  margin: 8px 0 12px;
  word-break: break-all;
  user-select: all;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}
</style>
