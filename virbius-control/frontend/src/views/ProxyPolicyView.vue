<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('proxy.title') }}</h2>
    <p class="v-empty-hint">{{ t('proxy.hint') }}</p>

    <div class="v-section">
      <h3>{{ t('proxy.upstreams') }}</h3>
      <el-table :data="form.upstreams" size="small" border>
        <el-table-column :label="t('proxy.col-name')" width="160">
          <template #default="{ row }"><el-input v-model="row.name" /></template>
        </el-table-column>
        <el-table-column :label="t('proxy.col-url')">
          <template #default="{ row }"><el-input v-model="row.url" /></template>
        </el-table-column>
        <el-table-column :label="t('proxy.col-sse')" width="140">
          <template #default="{ row }"><el-input v-model="row.sse_path" /></template>
        </el-table-column>
        <el-table-column width="80">
          <template #default="{ $index }">
            <el-button type="danger" link @click="form.upstreams.splice($index, 1)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top:8px" @click="addUpstream">{{ t('proxy.add-upstream') }}</el-button>
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.ttl') }}</h3>
      <el-input-number v-model="form.session_ttl_secs" :min="30" :max="604800" />
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.fallback') }}</h3>
      <el-select v-model="form.fallback_policy" style="width:240px">
        <el-option value="minimum_privilege" :label="t('proxy.fallback-min')" />
        <el-option value="default_deny" :label="t('proxy.fallback-deny')" />
        <el-option value="audit_only" :label="t('proxy.fallback-audit')" />
      </el-select>
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.fast') }}</h3>
      <div class="v-row">
        <el-checkbox v-model="form.fast_path.enabled">{{ t('proxy.fast-on') }}</el-checkbox>
        <span>{{ t('proxy.warmup') }}</span>
        <el-input-number v-model="form.fast_path.warmup_calls" :min="0" :max="10000" />
        <span>{{ t('proxy.risk') }}</span>
        <el-input-number v-model="form.fast_path.risk_threshold" :min="0" :max="100" />
      </div>
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.failover') }}</h3>
      <div class="v-row">
        <el-checkbox v-model="form.failover.high_risk_fail_closed">{{ t('proxy.fail-closed') }}</el-checkbox>
        <el-checkbox v-model="form.failover.low_risk_fail_open">{{ t('proxy.fail-open') }}</el-checkbox>
        <span>{{ t('proxy.timeout') }}</span>
        <el-input-number v-model="form.failover.engine_timeout_ms" :min="100" :max="60000" :step="100" />
      </div>
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.review') }}</h3>
      <div class="v-row">
        <el-checkbox v-model="form.output_review.enabled">{{ t('proxy.review-on') }}</el-checkbox>
        <span>{{ t('proxy.min-len') }}</span>
        <el-input-number v-model="form.output_review.min_text_length" :min="0" :max="1000000" />
        <span>{{ t('proxy.min-risk') }}</span>
        <el-input-number v-model="form.output_review.min_risk_score" :min="0" :max="100" />
        <el-checkbox v-model="form.output_review.fail_open">{{ t('proxy.review-open') }}</el-checkbox>
      </div>
    </div>

    <div class="v-section">
      <h3>{{ t('proxy.memory') }}</h3>
      <div class="v-row">
        <el-checkbox v-model="form.memory.enabled">{{ t('proxy.memory-on') }}</el-checkbox>
        <span>{{ t('proxy.max-entry') }}</span>
        <el-input-number v-model="form.memory.max_entry_size" :min="1" :max="1000000" />
      </div>
      <el-input v-model="patternText" :placeholder="t('proxy.patterns')" style="margin-top:8px;max-width:480px" />
    </div>

    <el-button type="primary" :loading="saving" @click="save">{{ t('proxy.save') }}</el-button>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { admin, jsonBody } from '@/api/client';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();
const saving = ref(false);
const patternText = ref('');

const form = reactive({
  upstreams: [] as { name: string; url: string; sse_path: string }[],
  session_ttl_secs: 1800,
  fallback_policy: 'minimum_privilege',
  fast_path: { enabled: true, warmup_calls: 5, risk_threshold: 30 },
  failover: { high_risk_fail_closed: true, low_risk_fail_open: true, engine_timeout_ms: 3000 },
  output_review: { enabled: true, min_text_length: 512, min_risk_score: 50, fail_open: true },
  memory: { enabled: false, max_entry_size: 4096 }
});

function addUpstream() {
  form.upstreams.push({ name: '', url: '', sse_path: '/sse' });
}

function apply(data: any) {
  form.upstreams = (data.upstreams || []).map((u: any) => ({
    name: u.name || '',
    url: u.url || '',
    sse_path: u.sse_path || '/sse'
  }));
  form.session_ttl_secs = data.session_ttl_secs ?? 1800;
  form.fallback_policy = data.fallback_policy || 'minimum_privilege';
  Object.assign(form.fast_path, data.fast_path || {});
  Object.assign(form.failover, data.failover || {});
  Object.assign(form.output_review, data.output_review || {});
  Object.assign(form.memory, data.memory || {});
  patternText.value = (data.memory?.tool_patterns || []).join(', ');
}

async function load() {
  try {
    apply(await admin<any>('/proxy-policy'));
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function save() {
  if (form.upstreams.some(u => !u.name.trim() || !u.url.trim())) {
    feedback.log(t('proxy.upstream-required'), 'warn');
    return;
  }
  saving.value = true;
  try {
    const body = {
      ...form,
      memory: {
        ...form.memory,
        tool_patterns: patternText.value.split(',').map(s => s.trim()).filter(Boolean)
      }
    };
    apply(await admin<any>('/proxy-policy', { method: 'PUT', body: jsonBody(body) }));
    feedback.log(t('proxy.saved'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
watch(() => session.tenant, load);
</script>
