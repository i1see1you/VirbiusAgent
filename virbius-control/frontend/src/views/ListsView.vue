<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('lists.title') }}</h2>
    <p class="v-hint">{{ t('lists.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('lists.desc')"></p>
    </details>

    <div class="v-row">
      <el-input v-model="filterQ" :placeholder="t('lists.filter-q')" clearable style="width:220px" />
      <el-button type="primary" @click="openCreate">{{ t('lists.btn-create') }}</el-button>
    </div>
    <p class="v-empty-hint" style="margin:0 0 8px">{{ t('lists.click-row') }}</p>

    <el-table ref="tableRef" :data="filteredCatalog" size="small" border stripe highlight-current-row
      :row-class-name="catalogRowClass" @row-click="onCatalogRowClick" :empty-text="t('lists.empty')">
      <el-table-column :label="t('lists.header-name')" prop="name">
        <template #default="{ row }"><code>{{ row.name }}</code></template>
      </el-table-column>
      <el-table-column :label="t('lists.header-dim')" width="140">
        <template #default="{ row }">{{ dimLabel(row.dimension) }}</template>
      </el-table-column>
      <el-table-column :label="t('lists.header-entries')" prop="entryCount" width="80" align="right" />
      <el-table-column :label="t('lists.header-remark')" prop="remark" />
      <el-table-column :label="t('lists.header-actions')" width="90" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" size="small" link @click.stop="deleteList(row.name)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer
      v-model="entryDrawerVisible"
      class="lists-drawer"
      direction="rtl"
      size="640px"
      :title="drawerTitle"
      :close-on-click-modal="true"
      :close-on-press-escape="true"
      @opened="syncTableHighlight"
    >
      <p class="v-hint">{{ t('lists.drawer-hint', [dimLabel(currentDim)]) }}</p>
      <p class="v-hint">{{ t('lists.drawer-hint-expiry') }}</p>

      <el-input v-model="batchText" type="textarea" :rows="5" :placeholder="t('lists.placeholder-batch')" />
      <div class="v-row" style="margin-top:8px">
        <el-select v-model="expireMode" style="width:130px">
          <el-option value="never" :label="t('lists.expire-never')" />
          <el-option value="1d" :label="t('lists.expire-1d')" />
          <el-option value="7d" :label="t('lists.expire-7d')" />
          <el-option value="custom" :label="t('lists.expire-custom')" />
        </el-select>
        <el-date-picker v-if="expireMode === 'custom'" v-model="expireCustom" type="datetime"
          :placeholder="t('lists.expire-custom')" style="width:220px" />
        <el-button type="primary" :loading="adding" @click="addBatch">{{ t('lists.btn-add-batch') }}</el-button>
      </div>

      <el-table :data="paginatedEntryRows" size="small" border stripe :row-class-name="entryRowClass"
        :empty-text="t('lists.empty-entries')" style="margin-top:12px">
        <el-table-column :label="t('lists.header-value')" prop="value">
          <template #default="{ row }"><code>{{ row.value }}</code></template>
        </el-table-column>
        <el-table-column :label="t('lists.header-created')" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('lists.header-expires')" width="160">
          <template #default="{ row }">
            <span v-if="isExpired(row.expiresAt)" class="is-expired">{{ t('lists.expired') }}</span>
            <span v-else>{{ fmtTime(row.expiresAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('lists.header-remark')" prop="remark" />
        <el-table-column :label="t('lists.header-actions')" width="80">
          <template #default="{ row }">
            <el-button type="danger" size="small" link @click="deleteEntry(row)">{{ t('common.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-if="entryTotal > size" small background layout="prev, pager, next"
        v-model:current-page="entryPage" :page-size="size" :total="entryTotal" />
    </el-drawer>

    <el-dialog v-model="createVisible" :title="t('lists.create-title')" width="480px">
      <div class="v-row">
        <label>{{ t('lists.header-name') }}
          <el-input v-model="newListName" :placeholder="t('lists.placeholder-name')" style="width:200px" />
        </label>
      </div>
      <div class="v-row">
        <label>{{ t('lists.match') }}
          <el-select v-model="newListDim" style="width:180px" @change="syncDim">
            <el-option value="keyword" :label="t('lists.dim-keyword')" />
            <el-option value="user_id" :label="t('lists.dim-user')" />
            <el-option value="device_id" :label="t('lists.dim-device')" />
            <el-option value="ip_cidr" :label="t('lists.dim-ip')" />
            <el-option value="var" :label="t('lists.dim-logical')" />
          </el-select>
        </label>
      </div>
      <div v-if="newListDim === 'var'" class="v-row">
        <el-select v-model="listVarLogical" style="width:180px">
          <el-option v-if="!logicals.length" value="" :label="t('lists.no-mapping')" />
          <el-option v-for="l in logicals" :key="l" :value="l" :label="l" />
        </el-select>
        <el-input v-model="listVarLogicalCustom" :placeholder="t('common.or-enter')" style="width:140px" />
      </div>
      <div class="v-row">
        <el-input v-model="newListRemark" :placeholder="t('lists.placeholder-remark-list')" />
      </div>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="createList">{{ t('lists.btn-create') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useRulesStore } from '@/stores/rules';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field, fmtTime, inferListStorage,
  isListEntryActive, countActiveListEntries, listEntryValue } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const rulesStore = useRulesStore();
const session = useSessionStore();

const MEMORY_LIST_MAX_ACTIVE = 1000;
const tableRef = ref();
const filterQ = ref('');
const createVisible = ref(false);
const entryDrawerVisible = ref(false);
const newListName = ref('');
const newListDim = ref('keyword');
const newListRemark = ref('');
const listVarLogical = ref('');
const listVarLogicalCustom = ref('');
const entryListName = ref('');
const batchText = ref('');
const expireMode = ref('never');
const expireCustom = ref<Date | null>(null);
const adding = ref(false);

const listMeta = ref<Record<string, any>>({});
const logicals = computed(() => rulesStore.contextVars.map((v: any) => v.logical).filter(Boolean));
const catalogRows = ref<any[]>([]);
const entryRows = ref<any[]>([]);
const currentDim = computed(() => listMeta.value[entryListName.value]?.dimension || '');
const drawerTitle = computed(() => t('lists.drawer-title', [entryListName.value || '']));

const filteredCatalog = computed(() => {
  const q = filterQ.value.trim().toLowerCase();
  if (!q) return catalogRows.value;
  return catalogRows.value.filter((r: any) => {
    const name = String(r.name || '').toLowerCase();
    const remark = String(r.remark || '').toLowerCase();
    const dim = dimLabel(r.dimension).toLowerCase();
    return name.includes(q) || remark.includes(q) || dim.includes(q);
  });
});

const entryPage = ref(1);
const size = ref(50);
const entryTotal = ref(0);
const paginatedEntryRows = computed(() => entryRows.value.slice((entryPage.value - 1) * size.value, entryPage.value * size.value));

function dimLabel(dim: string): string {
  if (!dim) return '-';
  if (String(dim).startsWith('var:')) return t('lists.dim-logical') + ' ' + dim.slice(4);
  const map: Record<string, string> = {
    keyword: t('lists.dim-keyword'),
    user_id: t('lists.dim-user'),
    device_id: t('lists.dim-device'),
    ip_cidr: t('lists.dim-ip')
  };
  return map[dim] || dim;
}

function catalogRowClass({ row }: { row: any }) {
  return row.name === entryListName.value && entryDrawerVisible.value ? 'current-row' : '';
}
function entryRowClass({ row }: { row: any }) {
  return isExpired(row.expiresAt) ? 'is-expired' : '';
}
function isExpired(expiresAt: any) {
  return !!expiresAt && !isListEntryActive(expiresAt);
}

function syncTableHighlight() {
  const row = entryListName.value
    ? catalogRows.value.find((r: any) => r.name === entryListName.value)
    : undefined;
  tableRef.value?.setCurrentRow(row);
}

function syncDim() {
  if (newListDim.value === 'var' && logicals.value.length) {
    if (!listVarLogical.value) listVarLogical.value = logicals.value[0];
  }
}

function buildListDimension(): string {
  if (newListDim.value !== 'var') return newListDim.value;
  const logical = (listVarLogicalCustom.value.trim() || listVarLogical.value.trim());
  if (!logical) throw new Error(t('lists.var-dim-required'));
  if (!/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(logical)) throw new Error(t('lists.var-name-invalid'));
  return 'var:' + logical;
}

function flattenToCatalog(lists: any[]): any[] {
  const out: any[] = [];
  if (!Array.isArray(lists)) return out;
  lists.forEach(item => {
    const name = field(item, 'list_name', 'listName') || '';
    if (!name) return;
    const dim = field(item, 'dimension') || '';
    const entries = field(item, 'entries') || [];
    out.push({
      name,
      dimension: dim,
      storage: inferListStorage(dim, field(item, 'storage')),
      entryCount: entries.length,
      remark: field(item, 'remark') || ''
    });
  });
  return out;
}

function flattenEntries(listName: string, lists: any[]): any[] {
  const item = lists.find((x: any) => (field(x, 'list_name', 'listName') || '') === listName);
  if (!item) return [];
  const dim = field(item, 'dimension') || '';
  const storage = inferListStorage(dim, field(item, 'storage'));
  const entries = field(item, 'entries') || [];
  return entries.map((e: any) => ({
    listName,
    dim, storage,
    value: listEntryValue(e),
    createdAt: field(e, 'created_at', 'createdAt') || '',
    expiresAt: field(e, 'expires_at', 'expiresAt') || '',
    remark: field(e, 'remark') || ''
  }));
}

let rawData: any = null;

async function loadLists() {
  try {
    rawData = await admin<any>('/lists');
    const meta: Record<string, any> = {};
    (rawData.lists || []).forEach((item: any) => {
      const name = field(item, 'list_name', 'listName');
      if (!name) return;
      const dim = field(item, 'dimension') || '';
      meta[name] = {
        dimension: dim,
        storage: inferListStorage(dim, field(item, 'storage')),
        entries: field(item, 'entries') || [],
        activeEntryCount: field(item, 'active_entry_count', 'activeEntryCount')
      };
    });
    listMeta.value = meta;
    catalogRows.value = flattenToCatalog(rawData.lists);
    if (entryListName.value) loadEntriesForList();
    nextTick(syncTableHighlight);
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

function loadEntriesForList() {
  if (!rawData || !entryListName.value) { entryRows.value = []; entryTotal.value = 0; return; }
  entryRows.value = flattenEntries(entryListName.value, rawData.lists);
  entryTotal.value = entryRows.value.length; entryPage.value = 1;
}

function onCatalogRowClick(row: any) {
  openEntries(row.name);
}

function openEntries(name: string) {
  entryListName.value = name;
  loadEntriesForList();
  entryDrawerVisible.value = true;
  nextTick(syncTableHighlight);
}

function openCreate() {
  newListName.value = '';
  newListDim.value = 'keyword';
  newListRemark.value = '';
  listVarLogical.value = '';
  listVarLogicalCustom.value = '';
  createVisible.value = true;
}

async function createList() {
  if (!newListName.value.trim()) { feedback.log(t('lists.name-required'), 'warn'); return; }
  let dim: string;
  try { dim = buildListDimension(); } catch (e: any) { feedback.log(e.message, 'err'); return; }
  const name = newListName.value.trim();
  try {
    await admin('/lists/' + encodeURIComponent(name), {
      method: 'PUT', body: JSON.stringify({ dimension: dim, remark: newListRemark.value.trim() || null })
    });
    createVisible.value = false;
    await loadLists();
    feedback.log(t('lists.created'), 'ok');
    openEntries(name);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function syncHint(data: any): string {
  if (data && data.engine_reload) return t('lists.synced-engine');
  if (data && data.refreshed) return t('lists.synced-gateway');
  return '';
}

function parseBatchValues(): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const line of batchText.value.split(/\r?\n/)) {
    const v = line.trim();
    if (!v || seen.has(v)) continue;
    seen.add(v);
    out.push(v);
  }
  return out;
}

function batchExpiresAt(): string | null {
  if (expireMode.value === '1d') return new Date(Date.now() + 86400000).toISOString();
  if (expireMode.value === '7d') return new Date(Date.now() + 7 * 86400000).toISOString();
  if (expireMode.value === 'custom' && expireCustom.value) return new Date(expireCustom.value).toISOString();
  return null;
}

async function addBatch() {
  if (!entryListName.value) return;
  const values = parseBatchValues();
  if (!values.length) { feedback.log(t('lists.select-values'), 'warn'); return; }
  const meta = listMeta.value[entryListName.value];
  const expiresAt = batchExpiresAt();
  if (meta && meta.storage === 'memory') {
    const active = meta.activeEntryCount != null ? Number(meta.activeEntryCount) : countActiveListEntries(meta.entries);
    const existing = new Set((meta.entries || []).map((e: any) => listEntryValue(e)));
    const newActive = values.filter(v => !existing.has(v) && isListEntryActive(expiresAt)).length;
    if (active + newActive > MEMORY_LIST_MAX_ACTIVE) {
      feedback.log(t('lists.memory-limit', [MEMORY_LIST_MAX_ACTIVE]), 'warn'); return;
    }
  }
  adding.value = true;
  let lastResp: any = null;
  let added = 0;
  try {
    for (const value of values) {
      const entry: any = { value };
      if (expiresAt) entry.expires_at = expiresAt;
      lastResp = await admin('/lists/' + encodeURIComponent(entryListName.value) + '/entries', {
        method: 'POST', body: JSON.stringify({ entries: [entry] })
      });
      added += 1;
    }
    batchText.value = '';
    await loadLists();
    feedback.log(t('lists.entry-added-n', [added]) + syncHint(lastResp), 'ok');
  } catch (e: any) {
    await loadLists();
    feedback.log(added ? t('lists.entry-added-n', [added]) + ' · ' + e.message : e.message, 'err');
  } finally {
    adding.value = false;
  }
}

async function deleteEntry(row: any) {
  try { await ElMessageBox.confirm(t('lists.confirm-delete-entry'), { type: 'warning' }); }
  catch { return; }
  try {
    const resp = await admin(`/lists/${encodeURIComponent(row.listName)}/entries/${encodeURIComponent(row.value)}`, { method: 'DELETE' });
    await loadLists();
    feedback.log(t('lists.entry-deleted') + syncHint(resp), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function deleteList(name: string) {
  try { await ElMessageBox.confirm(t('lists.confirm-delete', [name]), { type: 'warning' }); }
  catch { return; }
  try {
    await admin('/lists/' + encodeURIComponent(name), { method: 'DELETE' });
    if (entryListName.value === name) {
      entryDrawerVisible.value = false;
      entryListName.value = '';
      entryRows.value = [];
      entryTotal.value = 0;
    }
    await loadLists();
    feedback.log(t('lists.list-deleted'), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(loadLists);
watch(() => session.tenant, () => {
  entryDrawerVisible.value = false;
  entryListName.value = '';
  loadLists();
});
watch([entryListName, entryDrawerVisible], () => { nextTick(syncTableHighlight); });
</script>
