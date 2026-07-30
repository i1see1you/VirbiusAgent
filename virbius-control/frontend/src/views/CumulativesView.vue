<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('cum.title') }}</h2>
    <p class="v-hint" v-html="t('cum.desc')"></p>

    <div class="v-row">
      <el-button type="primary" @click="openNew">{{ t('cum.btn-new') }}</el-button>
      <el-button @click="load">{{ t('cum.btn-refresh') }}</el-button>
    </div>

    <el-table :data="rows" size="small" border stripe @row-click="onRowClick">
      <el-table-column :label="t('cum.header-name')">
        <template #default="{ row }"><code>{{ cumName(row) }}</code></template>
      </el-table-column>
      <el-table-column :label="t('cum.header-dim')">
        <template #default="{ row }">{{ formatDim(row.dimension) }}</template>
      </el-table-column>
      <el-table-column :label="t('cum.header-window')">
        <template #default="{ row }">{{ formatWindow(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('cum.header-status')" prop="status" width="100" />
      <el-table-column :label="t('common.none')" width="120">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click.stop="selectCum(cumName(row))">{{ t('common.edit') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="editorVisible" class="v-card" style="margin-top:16px;background:#f8fafc">
      <h3 style="font-size:15px;margin:0 0 8px">
        {{ isNew ? t('cum.edit-title-new') : t('cum.edit-title-edit') }}
        <code v-if="!isNew" style="margin-left:6px">{{ form.cumulative_name }}</code>
      </h3>
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>cumulative_name
          <el-input v-model="form.cumulative_name" :disabled="!isNew" style="width:180px" />
        </label>
        <label>{{ t('cum.label-desc') }}
          <el-input v-model="form.description" style="width:220px" />
        </label>
      </div>
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>{{ t('cum.label-dimension') }}
          <el-select v-model="form.dimension" style="width:150px" @change="onDimChange">
            <el-option value="user_id" label="user_id" />
            <el-option value="device_id" label="device_id" />
            <el-option value="ip" label="ip" />
            <el-option value="session_id" label="session_id" />
            <el-option value="keyword" label="keyword" />
            <el-option value="var" :label="t('cum.label-logical')" />
          </el-select>
        </label>
        <label v-if="form.dimension === 'var'">{{ t('cum.label-logical') }}
          <el-select v-model="varLogical" style="width:140px">
            <el-option v-if="!logicals.length" value="" :label="t('cum.no-mapping')" />
            <el-option v-for="l in logicals" :key="l" :value="l" :label="l" />
          </el-select>
          <el-input v-model="varLogicalCustom" :placeholder="t('common.or-enter')" style="width:110px" />
        </label>
        <label>{{ t('cum.label-status') }}
          <el-select v-model="form.status" style="width:110px">
            <el-option value="active" label="active" />
            <el-option value="disabled" label="disabled" />
          </el-select>
        </label>
        <label>{{ t('cum.label-priority') }}
          <el-input-number v-model="form.priority" style="width:100px" />
        </label>
      </div>
      <p v-if="form.dimension === 'var'" class="v-hint" v-html="t('cum.var-hint')"></p>

      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <label>{{ t('cum.label-window-kind') }}
          <el-select v-model="form.window_kind" style="width:150px" @change="syncWindow">
            <el-option value="rolling" label="rolling" />
            <el-option value="calendar_day" label="calendar_day" />
          </el-select>
        </label>
        <template v-if="form.window_kind === 'rolling'">
          <el-radio-group v-model="winUnit">
            <el-radio value="minutes">{{ t('cum.rolling-min') }}</el-radio>
            <el-radio value="hours">{{ t('cum.rolling-hour') }}</el-radio>
          </el-radio-group>
          <label>{{ t('cum.label-duration') }}
            <el-input-number v-model="winLen" :min="1" style="width:110px" />
          </label>
        </template>
        <label v-else>{{ t('cum.label-timezone') }}
          <el-input v-model="form.timezone" placeholder="Asia/Shanghai" style="width:160px" />
        </label>
      </div>

      <div style="margin-top:8px">
        <label>{{ t('cum.label-ingest-predicate') }}</label>
        <el-input v-model="form.ingest_predicate" type="textarea" :rows="4"
          placeholder="return tonumber(var('order_amount') or '0') > 20" />
        <p class="v-hint" v-html="t('cum.ingest-predicate-hint')"></p>
      </div>

      <div class="v-row" style="margin-top:8px">
        <el-button type="primary" @click="save">{{ t('cum.btn-save') }}</el-button>
        <el-button v-if="!isNew" type="danger" @click="remove">{{ t('cum.btn-delete') }}</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useRulesStore } from '@/stores/rules';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const rulesStore = useRulesStore();
const session = useSessionStore();

const rows = ref<any[]>([]);
const editorVisible = ref(false);
const isNew = ref(false);
const varLogical = ref('');
const varLogicalCustom = ref('');
const winUnit = ref<'minutes' | 'hours'>('minutes');
const winLen = ref(60);
const form = reactive<any>({
  cumulative_name: '', description: '', dimension: 'user_id',
  status: 'active', priority: 10, window_kind: 'rolling',
  timezone: '', ingest_predicate: ''
});
const logicals = computed(() => rulesStore.contextVars.map((v: any) => v.logical).filter(Boolean));

function cumName(r: any) { return field(r, 'cumulative_name', 'cumulativeName') || ''; }
function formatDim(dim: string) {
  if (!dim) return '-';
  if (String(dim).startsWith('var:')) return 'var(' + dim.slice(4) + ')';
  return dim;
}
function formatWindow(r: any) {
  const kind = field(r, 'window_kind', 'windowKind') || 'rolling';
  if (kind === 'calendar_day') return 'calendar_day (' + (field(r, 'timezone') || 'UTC') + ')';
  const m = field(r, 'window_minutes', 'windowMinutes');
  const h = field(r, 'window_hours', 'windowHours');
  if (m) return m + 'm';
  if (h) return h + 'h';
  return 'rolling';
}

async function load() {
  try {
    const data = await admin<any>('/cumulatives');
    rows.value = Array.isArray(data) ? data : (data?.rows || []);
    feedback.log(t('cum.list-refreshed'));
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function openNew() {
  isNew.value = true;
  Object.assign(form, { cumulative_name: '', description: '', dimension: 'user_id', status: 'active', priority: 10, window_kind: 'rolling', timezone: '', ingest_predicate: '' });
  varLogical.value = ''; varLogicalCustom.value = ''; winUnit.value = 'minutes'; winLen.value = 60;
  editorVisible.value = true;
}

async function selectCum(name: string) {
  try {
    const def = await admin<any>('/cumulatives/' + encodeURIComponent(name));
    const d = def?.definition || def || {};
    isNew.value = false;
    Object.assign(form, {
      cumulative_name: field(d, 'cumulative_name', 'cumulativeName') || name,
      description: field(d, 'description') || '',
      dimension: field(d, 'dimension') || 'user_id',
      status: field(d, 'status') || 'active',
      priority: field(d, 'priority') ?? 10,
      window_kind: field(d, 'window_kind', 'windowKind') || 'rolling',
      timezone: field(d, 'timezone') || '',
      ingest_predicate: field(d, 'ingest_predicate') || ''
    });
    const m = field(d, 'window_minutes', 'windowMinutes');
    const h = field(d, 'window_hours', 'windowHours');
    if (h) { winUnit.value = 'hours'; winLen.value = h; } else { winUnit.value = 'minutes'; winLen.value = m || 60; }
    if (String(form.dimension).startsWith('var:')) {
      varLogical.value = form.dimension.slice(4);
      varLogicalCustom.value = varLogical.value;
      form.dimension = 'var';
    }
    syncWindow();
    editorVisible.value = true;
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function onRowClick(row: any) { selectCum(cumName(row)); }
function onDimChange() { if (form.dimension === 'var' && logicals.value.length) varLogical.value = logicals.value[0]; }
function syncWindow() {}

function buildDimension(): string {
  if (form.dimension !== 'var') return form.dimension;
  const logical = (varLogicalCustom.value.trim() || varLogical.value.trim());
  if (!logical) throw new Error(t('cum.var-dim-required'));
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(logical)) throw new Error(t('cum.var-name-invalid'));
  return 'var:' + logical;
}

async function save() {
  const name = form.cumulative_name.trim();
  if (!name) { feedback.log(t('cum.name-required'), 'warn'); return; }
  let dim: string;
  try { dim = buildDimension(); } catch (e: any) { feedback.log(e.message, 'err'); return; }
  const body: any = {
    description: form.description || null,
    dimension: dim,
    window_kind: form.window_kind,
    priority: form.priority,
    status: form.status,
    ingest_predicate_runtime: form.ingest_predicate ? 'lua' : null,
    ingest_predicate: form.ingest_predicate || null
  };
  if (form.window_kind === 'rolling') {
    if (winUnit.value === 'hours') { body.window_hours = winLen.value; body.window_minutes = null; }
    else { body.window_minutes = winLen.value; body.window_hours = null; }
    body.timezone = null;
  } else {
    body.window_minutes = null; body.window_hours = null;
    body.timezone = form.timezone || 'UTC';
  }
  try {
    await admin('/cumulatives/' + encodeURIComponent(name), { method: 'PUT', body: JSON.stringify(body) });
    feedback.log(t('cum.btn-save'), 'ok');
    await load();
    await selectCum(name);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function remove() {
  try { await ElMessageBox.confirm(t('cum.confirm-delete', [form.cumulative_name]), { type: 'warning' }); }
  catch { return; }
  try {
    await admin('/cumulatives/' + encodeURIComponent(form.cumulative_name), { method: 'DELETE' });
    editorVisible.value = false;
    await load();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(load);
watch(() => session.tenant, load);
</script>
