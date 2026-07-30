<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('lists.title') }}</h2>
    <p class="v-hint" v-html="t('lists.desc')"></p>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('lists.tab-catalog')" name="catalog">
        <div class="v-toolbar">
          <el-input v-model="newListName" :placeholder="t('lists.placeholder-name')" style="width:150px" />
          <el-select v-model="newListDim" style="width:140px" @change="syncDim">
            <el-option value="keyword" label="keyword" />
            <el-option value="user_id" label="user_id" />
            <el-option value="device_id" label="device_id" />
            <el-option value="ip_cidr" label="ip_cidr" />
            <el-option value="var" :label="t('lists.dim-logical')" />
          </el-select>
          <template v-if="newListDim === 'var'">
            <el-select v-model="listVarLogical" style="width:160px">
              <el-option v-if="!logicals.length" value="" :label="t('lists.no-mapping')" />
              <el-option v-for="l in logicals" :key="l" :value="l" :label="l" />
            </el-select>
            <el-input v-model="listVarLogicalCustom" :placeholder="t('common.or-enter')" style="width:120px" />
          </template>
          <el-input v-model="newListRemark" :placeholder="t('lists.placeholder-remark-list')" style="width:180px" />
          <el-button type="primary" @click="createList">{{ t('lists.btn-create') }}</el-button>
        </div>

        <el-table :data="catalogRows" size="small" border stripe>
          <el-table-column :label="t('lists.header-name')" prop="name">
            <template #default="{ row }"><code>{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column :label="t('lists.header-dim')" prop="dimension" width="120">
            <template #default="{ row }">{{ formatListDimension(row.dimension) }}</template>
          </el-table-column>
          <el-table-column :label="t('lists.header-storage')" prop="storage" width="90">
            <template #default="{ row }">{{ formatListStorage(row.storage) }}</template>
          </el-table-column>
          <el-table-column :label="t('lists.header-entries')" prop="entryCount" width="80" align="right" />
          <el-table-column :label="t('lists.header-remark')" prop="remark" />
          <el-table-column :label="t('lists.header-actions')" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link @click="viewListEntries(row.name)">{{ t('lists.btn-view') }}</el-button>
              <el-button type="danger" size="small" link @click="deleteList(row.name)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('lists.tab-entries')" name="entries">
        <div class="v-row">
          <el-select v-model="entryListName" style="width:200px" @change="loadEntriesForList">
            <el-option value="" :label="t('lists.placeholder-select')" />
            <el-option v-for="n in listNames" :key="n" :value="n" :label="n" />
          </el-select>
          <el-input v-model="entryVal" :placeholder="t('lists.placeholder-value')" style="width:180px" />
          <el-input v-model="entryExpires" :placeholder="t('lists.placeholder-expires')" style="width:220px" />
          <el-input v-model="entryRemark" :placeholder="t('lists.placeholder-remark-entry')" style="width:150px" />
          <el-button @click="addEntry">{{ t('lists.btn-add-entry') }}</el-button>
        </div>

        <el-table :data="paginatedEntryRows" size="small" border stripe>
          <el-table-column :label="t('lists.header-value')" prop="value">
            <template #default="{ row }"><code>{{ row.value }}</code></template>
          </el-table-column>
          <el-table-column :label="t('lists.header-created')" prop="createdAt">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('lists.header-expires')" prop="expiresAt">
            <template #default="{ row }">{{ fmtTime(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('lists.header-remark')" prop="remark" />
          <el-table-column :label="t('common.none')" width="90">
            <template #default="{ row }">
              <el-button type="danger" size="small" link @click="deleteEntry(row)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="entryTotal > size" small background layout="prev, pager, next"
          v-model:current-page="entryPage" :page-size="size" :total="entryTotal"
          @current-change="scrollTop" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore } from '@/stores/feedback';
import { useRulesStore } from '@/stores/rules';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field, fmtTime, inferListStorage, formatListStorage, formatListDimension,
  isListEntryActive, countActiveListEntries, listEntryValue } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const rulesStore = useRulesStore();
const session = useSessionStore();

const MEMORY_LIST_MAX_ACTIVE = 1000;
const activeTab = ref('catalog');
const newListName = ref('');
const newListDim = ref('keyword');
const newListRemark = ref('');
const listVarLogical = ref('');
const listVarLogicalCustom = ref('');
const entryListName = ref('');
const entryVal = ref('');
const entryExpires = ref('');
const entryRemark = ref('');

const listMeta = ref<Record<string, any>>({});
const listNames = computed(() => Object.keys(listMeta.value).sort());
const logicals = computed(() => rulesStore.contextVars.map((v: any) => v.logical).filter(Boolean));
const catalogRows = ref<any[]>([]);
const entryRows = ref<any[]>([]);

function scrollTop() { document.querySelector('.v-scroll')?.scrollTo(0, 0); }
const entryPage = ref(1);
const size = ref(50);
const entryTotal = ref(0);
const paginatedEntryRows = computed(() => entryRows.value.slice((entryPage.value - 1) * size.value, entryPage.value * size.value));

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
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

function loadEntriesForList() {
  if (!rawData || !entryListName.value) { entryRows.value = []; entryTotal.value = 0; return; }
  entryRows.value = flattenEntries(entryListName.value, rawData.lists);
  entryTotal.value = entryRows.value.length; entryPage.value = 1;
}

function viewListEntries(name: string) {
  entryListName.value = name;
  loadEntriesForList();
  activeTab.value = 'entries';
}

async function createList() {
  if (!newListName.value.trim()) { feedback.log(t('lists.name-required'), 'warn'); return; }
  let dim: string;
  try { dim = buildListDimension(); } catch (e: any) { feedback.log(e.message, 'err'); return; }
  try {
    await admin('/lists/' + encodeURIComponent(newListName.value.trim()), {
      method: 'PUT', body: JSON.stringify({ dimension: dim, remark: newListRemark.value.trim() || null })
    });
    newListName.value = ''; newListRemark.value = '';
    await loadLists();
    feedback.log(t('lists.created'), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function syncHint(data: any): string {
  if (data && data.engine_reload) return t('lists.synced-engine');
  if (data && data.refreshed) return t('lists.synced-gateway');
  return '';
}

async function addEntry() {
  if (!entryListName.value || !entryVal.value.trim()) { feedback.log(t('lists.select-name-and-value'), 'warn'); return; }
  const meta = listMeta.value[entryListName.value];
  if (meta && meta.storage === 'memory') {
    const active = meta.activeEntryCount != null ? Number(meta.activeEntryCount) : countActiveListEntries(meta.entries);
    const newActive = isListEntryActive(entryExpires.value || null);
    const dup = (meta.entries || []).some((e: any) => listEntryValue(e) === entryVal.value.trim());
    if (newActive && !dup && active >= MEMORY_LIST_MAX_ACTIVE) {
      feedback.log(t('lists.memory-limit', [MEMORY_LIST_MAX_ACTIVE]), 'warn'); return;
    }
  }
  const entry: any = { value: entryVal.value.trim() };
  if (entryRemark.value.trim()) entry.remark = entryRemark.value.trim();
  if (entryExpires.value.trim()) entry.expires_at = entryExpires.value.trim();
  try {
    const resp = await admin('/lists/' + encodeURIComponent(entryListName.value) + '/entries', {
      method: 'POST', body: JSON.stringify({ entries: [entry] })
    });
    entryVal.value = ''; entryExpires.value = ''; entryRemark.value = '';
    await loadLists();
    feedback.log(t('lists.entry-added') + syncHint(resp), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function deleteEntry(row: any) {
  try {
    const resp = await admin(`/lists/${encodeURIComponent(row.listName)}/entries/${encodeURIComponent(row.value)}`, { method: 'DELETE' });
    await loadLists();
    feedback.log(t('lists.entry-deleted') + syncHint(resp), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function deleteList(name: string) {
  try {
    await admin('/lists/' + encodeURIComponent(name), { method: 'DELETE' });
    await loadLists();
    if (entryListName.value === name) { entryListName.value = ''; entryRows.value = []; entryTotal.value = 0; }
    feedback.log(t('lists.list-deleted'), 'ok');
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(loadLists);
watch(() => session.tenant, loadLists);
</script>
