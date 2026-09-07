<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('imgbl.title') }}</h2>
    <p class="v-hint" v-html="t('imgbl.desc')"></p>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('imgbl.tab-catalog')" name="catalog">
        <div class="v-toolbar">
          <el-input v-model="newListName" :placeholder="t('imgbl.placeholder-name')" style="width:200px"
            @keyup.enter="createList" />
          <el-button type="primary" @click="createList">{{ t('imgbl.btn-create-list') }}</el-button>
        </div>

        <el-table :data="catalogRows" size="small" border stripe>
          <el-table-column :label="t('imgbl.header-name')" prop="name">
            <template #default="{ row }"><code>{{ row.name }}</code></template>
          </el-table-column>
          <el-table-column :label="t('imgbl.header-entries')" prop="entryCount" width="90" align="right" />
          <el-table-column :label="t('imgbl.header-remark')" prop="remark" />
          <el-table-column :label="t('imgbl.header-actions')" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link @click="viewListEntries(row.name)">{{ t('imgbl.btn-view') }}</el-button>
              <el-button type="danger" size="small" link @click="deleteList(row.name)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="t('imgbl.tab-entries')" name="entries">
        <div class="v-toolbar">
          <el-select v-model="selectedList" style="width:200px" :placeholder="t('imgbl.placeholder-select')"
            @change="loadEntries">
            <el-option v-for="l in imageLists" :key="l" :label="l" :value="l" />
          </el-select>
          <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/gif,image/bmp"
            @change="onFilePicked" />
          <el-input v-model="remark" :placeholder="t('imgbl.placeholder-remark')" style="width:200px" />
          <el-button type="primary" :disabled="!pickedFile || !selectedList" @click="upload">{{ t('imgbl.btn-add') }}</el-button>
          <el-button :disabled="!selectedList" @click="loadEntries">{{ t('common.refresh') }}</el-button>
        </div>

        <el-alert v-if="tooLarge" type="warning" :title="tooLarge" show-icon :closable="false"
          style="margin-bottom:12px" />

        <el-table v-if="selectedList" :data="paginatedEntryRows" size="small" border stripe>
          <el-table-column :label="t('imgbl.header-sha256')" width="150">
            <template #default="{ row }">
              <el-tooltip :content="row.sha256" placement="top">
                <code>{{ row.sha256 ? row.sha256.slice(0, 12) + '…' : '' }}</code>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column :label="t('imgbl.header-phash')" width="170">
            <template #default="{ row }"><code>{{ row.phashHex }}</code></template>
          </el-table-column>
          <el-table-column :label="t('imgbl.header-created')" width="180">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('imgbl.header-remark')" prop="remark" min-width="180">
            <template #default="{ row }">
              <template v-if="editingValue === row.value">
                <div class="v-toolbar" style="margin:0;gap:4px">
                  <el-input v-model="editRemark" size="small" style="flex:1" @keyup.enter="saveRemark(row)" />
                  <el-button type="primary" size="small" link @click="saveRemark(row)">{{ t('common.save') }}</el-button>
                  <el-button size="small" link @click="cancelEdit">{{ t('common.cancel') }}</el-button>
                </div>
              </template>
              <template v-else>
                <span>{{ row.remark || '—' }}</span>
                <el-button size="small" link style="margin-left:6px" @click="startEdit(row)">{{ t('common.edit') }}</el-button>
              </template>
            </template>
          </el-table-column>
          <el-table-column :label="t('common.none')" width="90" fixed="right">
            <template #default="{ row }">
              <el-button type="danger" size="small" link @click="remove(row)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="entryTotal > size" small background layout="prev, pager, next"
          v-model:current-page="entryPage" :page-size="size" :total="entryTotal"
          @current-change="scrollTop" />
        <p v-if="!selectedList" class="v-hint">{{ t('imgbl.no-list') }}</p>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin, adminUpload, jsonBody } from '@/api/client';
import { fmtTime } from '@/utils/format';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const activeTab = ref('catalog');
const imageLists = ref<string[]>([]);
const catalogRows = ref<any[]>([]);
const selectedList = ref('');
const newListName = ref('');
const rows = ref<any[]>([]);
const fileInput = ref<HTMLInputElement | null>(null);
const pickedFile = ref<File | null>(null);
const remark = ref('');
const tooLarge = ref('');

const entryPage = ref(1);
const size = ref(50);
const entryTotal = ref(0);
const paginatedEntryRows = computed(() => rows.value.slice((entryPage.value - 1) * size.value, entryPage.value * size.value));

function scrollTop() { document.querySelector('.v-scroll')?.scrollTo(0, 0); }

/** Keep in sync with server spring.servlet.multipart.max-file-size (20MB). */
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + 'MB';
  if (bytes >= 1024) return Math.round(bytes / 1024) + 'KB';
  return bytes + 'B';
}

function onFilePicked(e: Event) {
  const files = (e.target as HTMLInputElement).files;
  const f = files && files.length ? files[0] : null;
  if (f && f.size > MAX_UPLOAD_BYTES) {
    // friendly inline hint instead of a failed request; the upload button stays disabled
    tooLarge.value = t('imgbl.file-too-large', [f.name, formatSize(f.size)]);
    pickedFile.value = null;
    return;
  }
  tooLarge.value = '';
  pickedFile.value = f;
}

async function load() {
  try {
    const data = await admin<any>('/lists');
    const lists = (data.lists || []).filter((l: any) => (l.dimension || '').toLowerCase() === 'image');
    imageLists.value = lists.map((l: any) => l.list_name);
    catalogRows.value = lists.map((l: any) => ({
      name: l.list_name,
      entryCount: (l.entries || []).length,
      remark: l.remark || ''
    }));
    if (!imageLists.value.includes(selectedList.value)) {
      selectedList.value = '';
      rows.value = [];
      entryTotal.value = 0;
    }
    if (selectedList.value) {
      await loadEntries();
    }
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function loadEntries() {
  if (!selectedList.value) { rows.value = []; entryTotal.value = 0; return; }
  try {
    const data = await admin<any>('/lists/' + encodeURIComponent(selectedList.value));
    rows.value = (data.entries || []).map((e: any) => {
      // fingerprint value: <sha256hex>:<phashhex>
      const v = e.value || '';
      const sep = v.indexOf(':');
      return {
        value: v,
        sha256: sep > 0 ? v.slice(0, sep) : v,
        phashHex: sep > 0 ? v.slice(sep + 1) : '',
        remark: e.remark || '',
        createdAt: e.created_at || ''
      };
    });
    entryTotal.value = rows.value.length;
    entryPage.value = 1;
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

function viewListEntries(name: string) {
  selectedList.value = name;
  loadEntries();
  activeTab.value = 'entries';
}

async function createList() {
  const name = newListName.value.trim();
  if (!name) return;
  try {
    await admin('/lists/' + encodeURIComponent(name),
      { method: 'PUT', body: jsonBody({ dimension: 'image', remark: '' }) });
    newListName.value = '';
    await load();
    feedback.log(t('imgbl.list-created'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function deleteList(name: string) {
  try {
    await admin('/lists/' + encodeURIComponent(name), { method: 'DELETE' });
    if (selectedList.value === name) { selectedList.value = ''; rows.value = []; entryTotal.value = 0; }
    await load();
    feedback.log(t('imgbl.deleted-list'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function upload() {
  if (!pickedFile.value || !selectedList.value) return;
  const fd = new FormData();
  fd.append('file', pickedFile.value);
  if (remark.value.trim()) fd.append('remark', remark.value.trim());
  try {
    await adminUpload('/lists/' + encodeURIComponent(selectedList.value) + '/entries/image', fd);
    pickedFile.value = null;
    if (fileInput.value) fileInput.value.value = '';
    remark.value = '';
    await load();
    feedback.log(t('imgbl.added'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

async function remove(row: any) {
  try {
    await admin('/lists/' + encodeURIComponent(selectedList.value)
      + '/entries/' + encodeURIComponent(row.value), { method: 'DELETE' });
    await load();
    feedback.log(t('imgbl.deleted'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

const editingValue = ref('');
const editRemark = ref('');

function startEdit(row: any) {
  editingValue.value = row.value;
  editRemark.value = row.remark || '';
}

function cancelEdit() {
  editingValue.value = '';
  editRemark.value = '';
}

async function saveRemark(row: any) {
  try {
    await admin('/lists/' + encodeURIComponent(selectedList.value)
      + '/entries/' + encodeURIComponent(row.value) + '/remark',
      { method: 'PATCH', body: jsonBody({ remark: editRemark.value }) });
    row.remark = editRemark.value.trim();
    cancelEdit();
    feedback.log(t('imgbl.remark-saved'), 'ok');
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

onMounted(load);
watch(() => session.tenant, load);
</script>
