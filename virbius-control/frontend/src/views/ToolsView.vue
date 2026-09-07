<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('tools.title') }}</h2>
    <p class="v-hint">{{ t('tools.desc-short') }}</p>
    <details class="v-hint-more">
      <summary>{{ t('common.learn-more') }}</summary>
      <p class="v-hint" v-html="t('tools.desc')"></p>
    </details>

    <div class="v-row">
      <el-input v-model="filterQ" :placeholder="t('tools.filter-q')" clearable style="width:220px" />
      <el-button type="primary" @click="openNew">{{ t('tools.btn-new') }}</el-button>
      <el-button @click="load">{{ t('tools.btn-refresh') }}</el-button>
    </div>
    <p class="v-empty-hint" style="margin:0 0 8px">{{ t('tools.click-row') }}</p>

    <el-table ref="tableRef" :data="filteredTools" size="small" border stripe highlight-current-row
      @row-click="onRowClick" :empty-text="t('tools.empty')">
      <el-table-column :label="t('tools.header-name')">
        <template #default="{ row }"><code>{{ row.tool_name }}</code></template>
      </el-table-column>
      <el-table-column :label="t('tools.header-risk')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="riskTagType(row.risk_class)" effect="dark">{{ riskLabel(row.risk_class) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('tools.header-sandbox')" width="140">
        <template #default="{ row }">{{ sandboxLabel(row.sandbox_type) }}</template>
      </el-table-column>
      <el-table-column :label="t('tools.header-approval')" width="140">
        <template #default="{ row }">{{ approvalLabel(row.approval_mode) }}</template>
      </el-table-column>
      <el-table-column :label="t('tools.header-fastpath')" width="90">
        <template #default="{ row }">{{ row.fast_path ? t('common.yes') : t('common.no') }}</template>
      </el-table-column>
      <el-table-column :label="t('tools.header-actions')" width="80">
        <template #default="{ row }">
          <el-button type="danger" size="small" link @click.stop="deleteTool(row.tool_name)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <Teleport to="body">
      <Transition name="tools-slide">
        <div v-if="editorVisible" class="tools-mask" @click.self="closeDrawer">
          <aside class="tools-panel" @click.stop>
            <header class="tools-panel-head">
              <h2>{{ drawerTitle }}</h2>
              <button type="button" class="tools-panel-close" @click="closeDrawer">{{ t('common.close') }}</button>
            </header>
            <div class="tools-panel-body">
              <div class="v-section" style="margin-top:0;padding-top:0;border-top:none">
                <h3>{{ t('tools.label-name') }}</h3>
                <div class="v-row">
                  <el-input v-model="form.tool_name" :disabled="!!editingName"
                    :placeholder="t('tools.placeholder-name')" style="width:220px" />
                </div>
                <p class="v-hint">{{ t('tools.name-hint') }}</p>
              </div>

              <div class="v-section">
                <h3>{{ t('tools.label-desc') }}</h3>
                <div class="v-row">
                  <el-input v-model="form.description" :placeholder="t('tools.placeholder-desc')" />
                </div>
              </div>

              <div class="v-section">
                <h3>{{ t('tools.section-risk') }}</h3>
                <div class="v-row">
                  <el-select v-model="form.risk_class" popper-class="tools-select-popper" style="width:220px">
                    <el-option value="low" :label="t('tools.risk-low')" />
                    <el-option value="medium" :label="t('tools.risk-medium')" />
                    <el-option value="high" :label="t('tools.risk-high')" />
                    <el-option value="network" :label="t('tools.risk-network')" />
                  </el-select>
                </div>
                <p class="v-hint">{{ t('tools.risk-hint') }}</p>
              </div>

              <div class="v-section">
                <h3>{{ t('tools.section-exec') }}</h3>
                <div class="v-row">
                  <span>{{ t('tools.label-sandbox') }}
                    <el-select v-model="form.sandbox_type" popper-class="tools-select-popper" style="width:200px">
                      <el-option value="none" :label="t('tools.sandbox-none')" />
                      <el-option value="landlock" :label="t('tools.sandbox-landlock')" />
                      <el-option value="gvisor" :label="t('tools.sandbox-gvisor')" />
                    </el-select>
                  </span>
                  <span>{{ t('tools.label-timeout') }}
                    <el-input-number v-model="form.timeout_sec" :min="1" :max="300" style="width:120px" />
                  </span>
                </div>
                <p class="v-hint">{{ t('tools.sandbox-hint') }}</p>
                <div class="v-row">
                  <el-checkbox v-model="form.fast_path">{{ t('tools.fastpath-label') }}</el-checkbox>
                </div>
                <p class="v-hint">{{ t('tools.fastpath-hint') }}</p>
              </div>

              <div class="v-section">
                <h3>{{ t('tools.section-approval') }}</h3>
                <div class="v-row">
                  <el-select v-model="form.approval_mode" popper-class="tools-select-popper" style="width:280px">
                    <el-option value="strict" :label="t('tools.approval-strict')" />
                    <el-option value="lax" :label="t('tools.approval-lax')" />
                  </el-select>
                </div>
                <p class="v-hint">{{ t('tools.approval-hint') }}</p>
              </div>

              <details class="v-hint-more" :open="schemaOpen" @toggle="onSchemaToggle">
                <summary>{{ t('tools.advanced') }}</summary>
                <div v-if="schemaOpen">
                  <label>{{ t('tools.label-schema') }}</label>
                  <ScriptEditor
                    ref="schemaEditorRef"
                    v-model="form.allowed_args_schema"
                    language="json"
                    min-height="140px"
                    max-height="280px"
                    :lint-fn="jsonLint"
                  />
                  <p class="v-hint">{{ t('tools.schema-hint') }}</p>
                </div>
              </details>
            </div>
            <footer class="tools-panel-foot">
              <el-button type="primary" @click="save">{{ t('tools.btn-save') }}</el-button>
              <el-button v-if="editingName" type="danger" @click="deleteTool(form.tool_name)">{{ t('common.delete') }}</el-button>
            </footer>
          </aside>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick, shallowRef } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import ScriptEditor from '@/components/ScriptEditor.vue';
import type { Diagnostic } from '@codemirror/lint';
import type { EditorView } from '@codemirror/view';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const tableRef = ref();
const schemaEditorRef = shallowRef<InstanceType<typeof ScriptEditor> | null>(null);
const tools = ref<any[]>([]);
const filterQ = ref('');
const editorVisible = ref(false);
const editingName = ref<string | null>(null);
const schemaOpen = ref(false);
const form = reactive<any>({
  tool_name: '', risk_class: 'low', sandbox_type: 'none', timeout_sec: 30,
  fast_path: false, approval_mode: 'strict', allowed_args_schema: '', description: ''
});

const drawerTitle = computed(() =>
  editingName.value ? t('tools.drawer-title', [editingName.value]) : t('tools.drawer-title-new')
);

const filteredTools = computed(() => {
  const q = filterQ.value.trim().toLowerCase();
  if (!q) return tools.value;
  return tools.value.filter((r: any) => {
    const name = String(r.tool_name || '').toLowerCase();
    const desc = String(r.description || '').toLowerCase();
    return name.includes(q) || desc.includes(q);
  });
});

function riskLabel(rc: string) {
  return ({ low: t('tools.risk-low-short'), medium: t('tools.risk-medium-short'), high: t('tools.risk-high-short'), network: t('tools.risk-network-short') } as any)[rc] || rc || t('tools.risk-low-short');
}
function riskTagType(rc: string): any {
  return ({ low: 'success', medium: 'warning', high: 'danger', network: 'primary' } as any)[rc] || 'info';
}
function sandboxLabel(st: string) {
  return ({ none: t('tools.sandbox-none'), landlock: t('tools.sandbox-landlock'), gvisor: t('tools.sandbox-gvisor') } as any)[st] || st || t('tools.sandbox-none');
}
function approvalLabel(am: string) {
  return am === 'lax' ? t('tools.approval-lax-short') : t('tools.approval-strict-short');
}

function jsonLint(view: EditorView): Diagnostic[] {
  const text = view.state.doc.toString().trim();
  if (!text) return [];
  try { JSON.parse(text); return []; }
  catch (e: any) {
    return [{ from: 0, to: view.state.doc.length, severity: 'error', message: e.message }];
  }
}

function refreshSchemaEditor() {
  nextTick(() => {
    window.setTimeout(() => schemaEditorRef.value?.refresh(), 280);
  });
}

function onSchemaToggle(e: Event) {
  const open = (e.target as HTMLDetailsElement).open;
  schemaOpen.value = open;
  if (open) refreshSchemaEditor();
}

function schemaText(raw: any): string {
  if (!raw) return '';
  if (typeof raw === 'string') {
    try { return JSON.stringify(JSON.parse(raw), null, 2); } catch { return raw; }
  }
  try { return JSON.stringify(raw, null, 2); } catch { return String(raw); }
}

function syncTableHighlight() {
  const name = editingName.value;
  const row = name ? tools.value.find((r: any) => r.tool_name === name) : undefined;
  tableRef.value?.setCurrentRow(row);
}

async function load() {
  try {
    tools.value = await admin<any[]>('/tools') || [];
    nextTick(syncTableHighlight);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function fillForm(tool: any) {
  Object.assign(form, {
    tool_name: tool?.tool_name || '',
    risk_class: tool?.risk_class || 'low',
    sandbox_type: tool?.sandbox_type || 'none',
    timeout_sec: Math.max(1, Math.round((tool?.timeout_ms || 30000) / 1000)),
    fast_path: !!tool?.fast_path,
    approval_mode: tool?.approval_mode || 'strict',
    allowed_args_schema: schemaText(tool?.allowed_args_schema),
    description: tool?.description || ''
  });
  schemaOpen.value = !!form.allowed_args_schema;
}

function closeDrawer() { editorVisible.value = false; }

function openNew() {
  editingName.value = null;
  fillForm(null);
  editorVisible.value = true;
  nextTick(syncTableHighlight);
}

function showEditor(tool: any) {
  editingName.value = tool?.tool_name || null;
  fillForm(tool);
  editorVisible.value = true;
  nextTick(syncTableHighlight);
  if (schemaOpen.value) refreshSchemaEditor();
}

function onRowClick(row: any) { showEditor(row); }

async function save() {
  const name = form.tool_name.trim();
  if (!name) { feedback.log(t('tools.name-required'), 'warn'); return; }
  if (!/^[a-z][a-z0-9_-]*$/.test(name)) { feedback.log(t('tools.name-invalid'), 'err'); return; }
  let schema = String(form.allowed_args_schema || '').trim();
  if (schema) {
    try { JSON.parse(schema); } catch (e: any) { feedback.log(t('tools.schema-invalid', [e.message]), 'err'); return; }
  }
  const body = {
    tool_name: name,
    risk_class: form.risk_class,
    sandbox_type: form.sandbox_type,
    timeout_ms: (parseInt(form.timeout_sec, 10) || 30) * 1000,
    fast_path: form.fast_path,
    approval_mode: form.approval_mode,
    allowed_args_schema: schema || null,
    description: form.description.trim() || null
  };
  try {
    await admin('/tools', { method: 'POST', body: JSON.stringify(body) });
    feedback.log(t('tools.saved'), 'ok');
    editingName.value = name;
    await load();
    const row = tools.value.find((r: any) => r.tool_name === name);
    if (row) fillForm(row);
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function deleteTool(name: string) {
  if (!name) return;
  try { await ElMessageBox.confirm(t('tools.confirm-delete', [name]), { type: 'warning' }); }
  catch { return; }
  try {
    await admin('/tools/' + encodeURIComponent(name), { method: 'DELETE' });
    feedback.log(t('tools.deleted'), 'ok');
    if (editingName.value === name || form.tool_name === name) editorVisible.value = false;
    await load();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function onEsc(e: KeyboardEvent) {
  if (e.key === 'Escape') closeDrawer();
}

onMounted(load);
onUnmounted(() => window.removeEventListener('keydown', onEsc));
watch(() => session.tenant, () => { editorVisible.value = false; load(); });
watch(editorVisible, (open) => {
  if (open) {
    window.addEventListener('keydown', onEsc);
    if (schemaOpen.value) refreshSchemaEditor();
  } else {
    window.removeEventListener('keydown', onEsc);
  }
});
</script>

<style scoped>
.tools-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(15, 23, 42, 0.35);
}
.tools-panel {
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
.tools-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px 12px;
  border-bottom: 1px solid var(--v-border);
}
.tools-panel-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #0f172a;
}
.tools-panel-close {
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  font-size: 13px;
  padding: 4px 6px;
}
.tools-panel-close:hover { color: #0f172a; }
.tools-panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px 20px;
}
.tools-panel-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
  border-top: 1px solid var(--v-border);
  padding: 10px 16px;
}
.tools-slide-enter-active,
.tools-slide-leave-active { transition: opacity 0.2s ease; }
.tools-slide-enter-active .tools-panel,
.tools-slide-leave-active .tools-panel { transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1); }
.tools-slide-enter-from,
.tools-slide-leave-to { opacity: 0; }
.tools-slide-enter-from .tools-panel,
.tools-slide-leave-to .tools-panel { transform: translateX(100%); }
</style>
<style>
.tools-select-popper {
  z-index: 4100 !important;
}
</style>
