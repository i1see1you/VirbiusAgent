<template>
  <el-drawer :model-value="modelValue" :title="t('ev.set-title')" size="520px"
             @update:model-value="$emit('update:modelValue', $event)">
    <div v-loading="loading">
      <el-form label-position="top" size="small">
        <el-form-item :label="t('ev.set-enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item :label="t('ev.set-base-url')">
          <el-input v-model="form.baseUrl" placeholder="http://virbius-evaluation:8084" />
        </el-form-item>
        <el-form-item :label="t('ev.set-token')">
          <el-input v-model="tokenInput" type="password" show-password
                    :placeholder="apiTokenSet ? t('ev.set-token-keep') : t('ev.set-token-empty')" />
          <el-checkbox v-if="apiTokenSet" v-model="clearToken" style="margin-top:6px">
            {{ t('ev.set-token-clear') }}
          </el-checkbox>
        </el-form-item>
        <el-form-item :label="t('ev.set-promo')">
          <el-switch v-model="form.promoEnabled" />
        </el-form-item>
        <el-form-item :label="t('ev.set-promo-url')">
          <el-input v-model="form.promoUrl" placeholder="https://..." />
        </el-form-item>
        <el-form-item :label="t('ev.set-probe-tenants')">
          <el-input v-model="form.probeTenants"
                    :placeholder="t('ev.set-probe-tenants-hint')" />
        </el-form-item>
      </el-form>

      <div class="v-row" style="gap:8px;margin:8px 0 16px">
        <el-button size="small" :loading="saving" type="primary" @click="save">
          {{ t('ev.set-save') }}
        </el-button>
        <el-button size="small" :loading="testing" @click="test">
          {{ t('ev.set-test') }}
        </el-button>
      </div>

      <div v-if="diag" class="ev-diag">
        <div v-for="s in diag.steps" :key="s.step" class="ev-diag-row">
          <el-tag size="small" :type="s.ok ? 'success' : 'danger'">{{ s.ok ? '✓' : '✗' }}</el-tag>
          <span class="ev-diag-step">{{ s.step }}</span>
          <span class="ev-diag-detail">{{ s.detail }}</span>
          <span class="ev-diag-ms">{{ s.ms }}ms</span>
        </div>
      </div>

      <el-divider />

      <div class="v-row" style="gap:8px;align-items:center">
        <el-upload :auto-upload="false" :show-file-list="false" accept=".jwt,.txt"
                   :on-change="onLicenseFile">
          <el-button size="small" :loading="uploading">{{ t('ev.set-upload') }}</el-button>
        </el-upload>
        <span class="v-hint">{{ t('ev.set-upload-hint') }}</span>
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessage, type UploadFile } from 'element-plus';
import {
  getSettings, testSettings, updateSettings, uploadLicense,
  type DiagResult, type EvaluationSettingsView
} from '@/api/evaluation';

const props = defineProps<{ modelValue: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>();
const { t } = useI18n();

const loading = ref(false);
const saving = ref(false);
const testing = ref(false);
const uploading = ref(false);
const diag = ref<DiagResult | null>(null);
const apiTokenSet = ref(false);
const tokenInput = ref('');
const clearToken = ref(false);

const form = reactive({
  enabled: true,
  baseUrl: '',
  promoEnabled: true,
  promoUrl: '',
  probeTenants: ''
});

function apply(v: EvaluationSettingsView) {
  form.enabled = v.enabled;
  form.baseUrl = v.baseUrl || '';
  form.promoEnabled = v.promoEnabled;
  form.promoUrl = v.promoUrl || '';
  form.probeTenants = v.probeTenants || '';
  apiTokenSet.value = v.apiTokenSet;
  tokenInput.value = '';
  clearToken.value = false;
}

async function load() {
  loading.value = true;
  try {
    apply(await getSettings());
  } catch (e: any) {
    ElMessage.error(t('ev.set-forbidden') + ' — ' + String(e?.message || e));
  } finally {
    loading.value = false;
  }
}

watch(() => props.modelValue, (open) => { if (open) void load(); });

async function save() {
  saving.value = true;
  try {
    const apiToken = clearToken.value ? '' : (tokenInput.value ? tokenInput.value : null);
    apply(await updateSettings({ ...form, apiToken }));
    ElMessage.success(t('ev.set-saved'));
    emit('saved');
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  } finally {
    saving.value = false;
  }
}

async function test() {
  testing.value = true;
  try {
    diag.value = await testSettings();
    if (diag.value.ok) ElMessage.success(t('ev.set-diag-ok'));
    else ElMessage.warning(t('ev.set-diag-fail'));
  } catch (e: any) {
    ElMessage.error(String(e?.message || e));
  } finally {
    testing.value = false;
  }
}

async function onLicenseFile(file: UploadFile) {
  if (!file.raw) return;
  uploading.value = true;
  try {
    const jwt = (await file.raw.text()).trim();
    await uploadLicense(jwt);
    ElMessage.success(t('ev.set-upload-ok'));
    emit('saved');
    await load();
  } catch (e: any) {
    ElMessage.error(t('ev.set-upload-fail') + ' — ' + String(e?.message || e));
  } finally {
    uploading.value = false;
  }
}
</script>

<style scoped>
.ev-diag { border: 1px solid var(--el-border-color-lighter); border-radius: 6px; padding: 8px 10px; }
.ev-diag-row { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 3px 0; }
.ev-diag-step { width: 64px; font-family: var(--el-font-family-monospace, monospace); }
.ev-diag-detail { flex: 1; color: var(--el-text-color-regular); word-break: break-all; }
.ev-diag-ms { color: var(--el-text-color-secondary); }
</style>
