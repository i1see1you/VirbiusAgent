<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('cum.title') }}</h2>
    <p class="v-hint">{{ t('cum.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('cum.desc')"></p>
    </details>

    <div class="v-row">
      <el-button type="primary" @click="openNew">{{ t('cum.btn-new') }}</el-button>
      <el-button @click="load">{{ t('cum.btn-refresh') }}</el-button>
    </div>
    <p class="v-empty-hint" style="margin:0 0 8px">{{ t('cum.click-row') }}</p>

    <el-table ref="tableRef" :data="rows" size="small" border stripe highlight-current-row
      @row-click="onRowClick" :empty-text="t('cum.empty')">
      <el-table-column :label="t('cum.header-name')">
        <template #default="{ row }"><code>{{ cumName(row) }}</code></template>
      </el-table-column>
      <el-table-column :label="t('cum.header-dim')">
        <template #default="{ row }">{{ formatDim(row.dimension) }}</template>
      </el-table-column>
      <el-table-column :label="t('cum.header-window')">
        <template #default="{ row }">{{ formatWindow(row) }}</template>
      </el-table-column>
      <el-table-column :label="t('cum.header-status')" width="90">
        <template #default="{ row }">{{ formatStatus(row.status) }}</template>
      </el-table-column>
      <el-table-column :label="t('cum.header-actions')" width="80">
        <template #default="{ row }">
          <el-button type="danger" size="small" link @click.stop="remove(cumName(row))">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="editorVisible"
      class="cum-drawer"
      direction="rtl"
      size="560px"
      :title="drawerTitle"
      :close-on-click-modal="true"
      :close-on-press-escape="true"
      @opened="syncTableHighlight"
    >
      <div class="v-section" style="margin-top:0;padding-top:0;border-top:none">
        <h3>{{ t('cum.label-name') }}</h3>
        <div class="v-row">
          <el-input v-model="form.cumulative_name" :disabled="!isNew" style="width:200px" />
        </div>
      </div>
      <div class="v-section">
        <h3>{{ t('cum.label-desc') }}</h3>
        <div class="v-row">
          <el-input v-model="form.description" :placeholder="t('cum.placeholder-desc')" />
        </div>
      </div>

      <div class="v-section">
        <h3>{{ t('cum.section-who') }}</h3>
        <div class="v-row">
          <el-select v-model="form.dimension" style="width:180px" @change="onDimChange">
            <el-option value="user_id" :label="t('cum.dim-user')" />
            <el-option value="device_id" :label="t('cum.dim-device')" />
            <el-option value="ip" :label="t('cum.dim-ip')" />
            <el-option value="session_id" :label="t('cum.dim-session')" />
            <el-option value="keyword" :label="t('cum.dim-keyword')" />
            <el-option value="var" :label="t('cum.dim-var')" />
          </el-select>
        </div>
        <div v-if="form.dimension === 'var'" class="v-row">
          <el-select v-model="varLogical" style="width:160px">
            <el-option v-if="!logicals.length" value="" :label="t('cum.no-mapping')" />
            <el-option v-for="l in logicals" :key="l" :value="l" :label="l" />
          </el-select>
          <el-input v-model="varLogicalCustom" :placeholder="t('common.or-enter')" style="width:140px" />
        </div>
        <p v-if="form.dimension === 'var'" class="v-hint" v-html="t('cum.var-hint')"></p>
      </div>

      <div class="v-section">
        <h3>{{ t('cum.section-window') }}</h3>
        <div class="v-row">
          <el-select v-model="form.window_kind" style="width:160px" @change="onWindowKindChange">
            <el-option value="rolling" :label="t('cum.window-rolling')" />
            <el-option value="calendar_day" :label="t('cum.window-calendar')" />
          </el-select>
        </div>
        <div v-if="form.window_kind === 'rolling'" class="v-row">
          <el-radio-group v-model="winUnit">
            <el-radio value="minutes">{{ t('cum.rolling-min') }}</el-radio>
            <el-radio value="hours">{{ t('cum.rolling-hour') }}</el-radio>
          </el-radio-group>
          <label>{{ t('cum.label-duration') }}
            <el-input-number v-model="winLen" :min="1" style="width:110px" />
          </label>
        </div>
        <div v-else class="v-row">
          <label>{{ t('cum.label-timezone') }}
            <el-select v-model="form.timezone" style="width:200px">
              <el-option v-for="z in timezoneOptions" :key="z.value" :value="z.value" :label="z.label" />
            </el-select>
          </label>
        </div>
      </div>

      <div class="v-section">
        <h3>{{ t('cum.section-more') }}</h3>
        <div class="v-row">
          <label>{{ t('cum.label-status') }}
            <el-select v-model="form.status" style="width:120px">
              <el-option value="active" :label="t('cum.status-active')" />
              <el-option value="disabled" :label="t('cum.status-disabled')" />
            </el-select>
          </label>
          <label>{{ t('cum.label-priority') }}
            <el-input-number v-model="form.priority" style="width:100px" />
          </label>
        </div>
      </div>

      <details class="v-hint-more" :open="!!form.ingest_predicate">
        <summary>{{ t('cum.advanced') }}</summary>
        <label>{{ t('cum.label-ingest-predicate') }}</label>
        <el-input v-model="form.ingest_predicate" type="textarea" :rows="4"
          placeholder="return tonumber(var('order_amount') or '0') > 20" />
        <p class="v-hint" v-html="t('cum.ingest-predicate-hint')"></p>
      </details>

      <template #footer>
        <div class="cum-drawer-footer">
          <el-button type="primary" @click="save">{{ t('cum.btn-save') }}</el-button>
          <el-button v-if="!isNew" type="danger" @click="remove(form.cumulative_name)">{{ t('cum.btn-delete') }}</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue';
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

const tableRef = ref();
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
const drawerTitle = computed(() =>
  isNew.value ? t('cum.drawer-title-new') : t('cum.drawer-title', [form.cumulative_name || ''])
);
const TZ_VALUES = [
  { value: 'Asia/Shanghai', labelKey: 'cum.tz-shanghai' },
  { value: 'UTC', labelKey: 'cum.tz-utc' },
  { value: 'Asia/Hong_Kong', labelKey: 'cum.tz-hongkong' },
  { value: 'Asia/Tokyo', labelKey: 'cum.tz-tokyo' },
  { value: 'America/New_York', labelKey: 'cum.tz-newyork' },
  { value: 'America/Los_Angeles', labelKey: 'cum.tz-la' }
];
const timezoneOptions = computed(() => {
  const opts = TZ_VALUES.map(z => ({ value: z.value, label: t(z.labelKey) }));
  const cur = form.timezone;
  if (cur && !opts.some(o => o.value === cur)) opts.push({ value: cur, label: cur });
  return opts;
});

function cumName(r: any) { return field(r, 'cumulative_name', 'cumulativeName') || ''; }

function formatDim(dim: string) {
  if (!dim) return '-';
  if (String(dim).startsWith('var:')) return t('cum.dim-var') + ' ' + dim.slice(4);
  const map: Record<string, string> = {
    user_id: t('cum.dim-user'),
    device_id: t('cum.dim-device'),
    ip: t('cum.dim-ip'),
    session_id: t('cum.dim-session'),
    keyword: t('cum.dim-keyword')
  };
  return map[dim] || dim;
}

function formatWindow(r: any) {
  const kind = field(r, 'window_kind', 'windowKind') || 'rolling';
  if (kind === 'calendar_day') return t('cum.window-calendar-n', [tzLabel(field(r, 'timezone') || 'UTC')]);
  const m = field(r, 'window_minutes', 'windowMinutes');
  const h = field(r, 'window_hours', 'windowHours');
  if (h) return t('cum.window-rolling-n', [h, t('cum.hours')]);
  if (m) return t('cum.window-rolling-n', [m, t('cum.minutes')]);
  return t('cum.window-rolling');
}

function tzLabel(id: string) {
  const hit = TZ_VALUES.find(z => z.value === id);
  return hit ? t(hit.labelKey) : (id || 'UTC');
}

function formatStatus(st: string) {
  return st === 'disabled' ? t('cum.status-disabled') : t('cum.status-active');
}

function syncTableHighlight() {
  const name = !isNew.value ? form.cumulative_name : '';
  const row = name ? rows.value.find((r: any) => cumName(r) === name) : undefined;
  tableRef.value?.setCurrentRow(row);
}

async function load() {
  try {
    const data = await admin<any>('/cumulatives');
    rows.value = Array.isArray(data) ? data : (data?.rows || []);
    nextTick(syncTableHighlight);
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
      timezone: field(d, 'timezone') || 'Asia/Shanghai',
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
    editorVisible.value = true;
    nextTick(syncTableHighlight);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function onRowClick(row: any) { selectCum(cumName(row)); }
function onDimChange() { if (form.dimension === 'var' && logicals.value.length) varLogical.value = logicals.value[0]; }
function onWindowKindChange() {
  if (form.window_kind === 'calendar_day' && !form.timezone) form.timezone = 'Asia/Shanghai';
}

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
    body.timezone = form.timezone || 'Asia/Shanghai';
  }
  try {
    await admin('/cumulatives/' + encodeURIComponent(name), { method: 'PUT', body: JSON.stringify(body) });
    feedback.log(t('cum.saved'), 'ok');
    await load();
    await selectCum(name);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function remove(name: string) {
  if (!name) return;
  try { await ElMessageBox.confirm(t('cum.confirm-delete', [name]), { type: 'warning' }); }
  catch { return; }
  try {
    await admin('/cumulatives/' + encodeURIComponent(name), { method: 'DELETE' });
    if (form.cumulative_name === name) editorVisible.value = false;
    await load();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(load);
watch(() => session.tenant, () => { editorVisible.value = false; load(); });
watch([() => form.cumulative_name, editorVisible], () => { nextTick(syncTableHighlight); });
</script>

<style scoped>
.cum-drawer-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}
</style>
