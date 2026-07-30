<template>
  <div class="simulate-panel">
    <div class="simulate-header" @click="collapsed = !collapsed">
      <span class="simulate-title">{{ t('rules.simulate-title') }}</span>
      <span class="simulate-toggle">{{ collapsed ? '▶' : '▼' }}</span>
    </div>
    <div v-show="!collapsed" class="simulate-body">
      <div class="simulate-fixture-section">
        <div class="simulate-fixture-header">
          <span>{{ t('rules.simulate-fixture') }}</span>
          <el-select v-model="fixturePreset" size="small" style="width:140px" @change="applyFixturePreset">
            <el-option v-for="p in fixturePresets" :key="p.key" :value="p.key" :label="p.label" />
          </el-select>
        </div>
        <el-input v-model="fixtureText" type="textarea" :rows="8"
          style="font-family:ui-monospace,monospace;font-size:12px" />
        <div v-if="fixtureError" class="simulate-fixture-error">{{ fixtureError }}</div>
      </div>

      <div class="simulate-actions">
        <el-button type="primary" size="small" :loading="simulating" @click="runSimulate">
          {{ t('rules.simulate-run') }}
        </el-button>
        <el-button size="small" @click="resetFixture">{{ t('rules.simulate-reset') }}</el-button>
      </div>

      <div v-if="simResult" class="simulate-result">
        <div class="simulate-summary" :class="simResult.ok ? 'ok' : 'fail'">
          {{ simResult.summary || (simResult.ok ? 'OK' : 'FAIL') }}
        </div>
        <div v-if="simResult.steps?.length" class="simulate-steps">
          <div v-for="(step, i) in simResult.steps" :key="i"
            class="simulate-step" :class="step.ok ? 'ok' : 'fail'">
            <span class="step-icon">{{ step.ok ? '✓' : '✗' }}</span>
            <strong>{{ step.id }}</strong>
            <span class="step-detail">{{ JSON.stringify(step.detail) }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from 'vue-i18n';
import { admin } from '@/api/client';

const props = defineProps<{
  ruleId: string;
  layer: string;
  runtime: string;
  body: any;
  scope: any;
  intent: string;
  risk: number;
  reason: string;
  editorMode: string;
  condition: any;
  bundleId: string;
}>();

const { t } = useI18n();
const collapsed = ref(false);
const simulating = ref(false);
const fixtureText = ref('');
const fixturePreset = ref('clinical_chat');
const fixtureError = ref('');
const simResult = ref<any>(null);

const fixturePresets = [
  { key: 'clinical_chat', label: 'Clinical Chat' },
  { key: 'beta_chat', label: 'Beta Chat' },
  { key: 'l3_prior', label: 'L3 Prior' },
  { key: 'cum_over_limit', label: 'Cum Over Limit' },
  { key: 'force_list_hit', label: 'Force List Hit' }
];

const SIM_FIXTURE_PRESETS: Record<string, any> = {
  clinical_chat: {
    route_uri: '/v1/chat/completions',
    headers: { 'X-App-Id': 'medical-prod' },
    query: { mode: 'clinical' },
    content: 'test prompt'
  },
  beta_chat: {
    route_uri: '/v1/chat/completions',
    headers: { 'X-App-Id': 'beta' },
    query: {},
    content: 'hello'
  },
  l3_prior: {
    route_uri: '/v1/chat/completions',
    headers: { 'X-App-Id': 'medical-prod' },
    query: { mode: 'clinical' },
    content: 'test prompt',
    prior_signals: [{
      rule_id: 'gateway_deny_1',
      intent_action: 'deny',
      risk_score: 100,
      reason_code: 'DENY_KEYWORD'
    }]
  },
  cum_over_limit: {
    route_uri: '/v1/chat/completions',
    headers: { 'X-App-Id': 'medical-prod' },
    query: {},
    content: 'hello',
    overrides: { cumulative: { user_req_1h: 150 } }
  },
  force_list_hit: {
    route_uri: '/v1/chat/completions',
    headers: { 'X-App-Id': 'beta' },
    query: {},
    content: 'hello',
    overrides: { force_list_hit: true }
  }
};

function applyFixturePreset(key: string) {
  const preset = SIM_FIXTURE_PRESETS[key];
  if (preset) {
    fixtureText.value = JSON.stringify(preset, null, 2);
    fixtureError.value = '';
  }
}

function resetFixture() {
  fixturePreset.value = 'clinical_chat';
  applyFixturePreset('clinical_chat');
}

function validateFixture(): any | null {
  const raw = fixtureText.value.trim();
  if (!raw) {
    fixtureError.value = t('rules.fixture-empty');
    return null;
  }
  try {
    const obj = JSON.parse(raw);
    if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
      fixtureError.value = t('rules.fixture-root-obj');
      return null;
    }
    fixtureError.value = '';
    return obj;
  } catch (e: any) {
    fixtureError.value = t('rules.fixture-invalid', [e.message]);
    return null;
  }
}

async function runSimulate() {
  const fixture = validateFixture();
  if (!fixture) return;
  simulating.value = true;
  simResult.value = null;
  try {
    const payload = {
      editor_mode: props.editorMode,
      condition: props.editorMode === 'simple' ? props.condition : null,
      rule: {
        rule_id: props.ruleId || 'draft-preview',
        bundle_id: props.bundleId,
        layer: props.layer,
        runtime: props.runtime,
        scope: props.scope,
        body: props.body,
        intent_action: props.intent,
        risk_score: Number(props.risk),
        reason_code: props.reason,
        rollout_state: 'dry_run'
      },
      fixture,
      options: { cumulative_source: 'mock' }
    };
    const data = await admin<any>('/rules/simulate', { method: 'POST', body: JSON.stringify(payload) });
    simResult.value = {
      ok: !data.steps?.some((s: any) => !s.ok),
      summary: data.summary?.message || '',
      steps: data.steps || []
    };
  } catch (e: any) {
    simResult.value = { ok: false, summary: e.message, steps: [] };
  } finally {
    simulating.value = false;
  }
}

applyFixturePreset('clinical_chat');
</script>

<style scoped>
.simulate-panel {
  margin-top: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}
.simulate-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: #f1f5f9;
  cursor: pointer;
  user-select: none;
  font-size: 13px;
  font-weight: 500;
  color: #334155;
}
.simulate-header:hover {
  background: #e2e8f0;
}
.simulate-toggle {
  font-size: 10px;
  color: #94a3b8;
}
.simulate-body {
  padding: 12px;
}
.simulate-fixture-section {
  margin-bottom: 8px;
}
.simulate-fixture-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
  font-size: 12px;
  color: #475569;
}
.simulate-fixture-error {
  margin-top: 4px;
  font-size: 12px;
  color: #991b1b;
}
.simulate-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.simulate-result {
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  overflow: hidden;
}
.simulate-summary {
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 500;
}
.simulate-summary.ok {
  background: #dcfce7;
  color: #166534;
}
.simulate-summary.fail {
  background: #fee2e2;
  color: #991b1b;
}
.simulate-steps {
  padding: 8px 12px;
  background: #f8fafc;
}
.simulate-step {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 0;
  font-size: 12px;
  line-height: 1.5;
}
.step-icon {
  font-weight: bold;
  flex-shrink: 0;
}
.simulate-step.ok .step-icon {
  color: #22c55e;
}
.simulate-step.fail .step-icon {
  color: #ef4444;
}
.step-detail {
  color: #64748b;
  margin-left: 4px;
}
</style>
