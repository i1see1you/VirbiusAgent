<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('tools.title') }}</h2>
    <p class="v-hint" v-html="t('tools.desc')"></p>

    <div class="v-row">
      <el-button type="primary" @click="showEditor(null)">{{ t('tools.btn-new') }}</el-button>
    </div>

    <el-table :data="tools" size="small" border stripe style="margin-bottom:16px">
      <el-table-column :label="t('tools.header-name')" prop="tool_name" />
      <el-table-column :label="t('tools.header-risk')" width="120">
        <template #default="{ row }">
          <el-tag size="small" :type="riskTagType(row.risk_class)" effect="dark">{{ riskLabel(row.risk_class) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('tools.header-sandbox')" prop="sandbox_type" width="110" />
      <el-table-column :label="t('tools.header-timeout')" prop="timeout_ms" width="100" />
      <el-table-column :label="t('tools.header-fastpath')" width="90">
        <template #default="{ row }">{{ row.fast_path ? '⚡' : '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('tools.header-approval')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.approval_mode === 'lax' ? 'warning' : 'info'">{{ row.approval_mode || 'strict' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('tools.header-schema')" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.allowed_args_schema" size="small">schema</el-tag>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('tools.header-desc')" prop="description" />
      <el-table-column :label="t('common.none')" width="130">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="showEditor(row)">{{ t('common.edit') }}</el-button>
          <el-button size="small" link type="danger" @click="deleteTool(row.tool_name)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editorVisible" :title="t('tools.editor-title-new')" width="640px">
      <div class="v-row" style="flex-wrap:wrap;gap:12px">
        <span>tool_name <el-input v-model="form.tool_name" :disabled="!!editingName" style="width:160px" /></span>
        <span>risk_class
          <el-select v-model="form.risk_class" style="width:140px">
            <el-option value="low" label="🟢 low (1)" />
            <el-option value="medium" label="🟡 medium (3)" />
            <el-option value="high" label="🔴 high (5)" />
            <el-option value="network" label="🔵 network (4)" />
          </el-select>
        </span>
        <span>sandbox_type
          <el-select v-model="form.sandbox_type" style="width:120px">
            <el-option value="none" label="none" />
            <el-option value="landlock" label="landlock" />
            <el-option value="gvisor" label="gvisor" />
          </el-select>
        </span>
        <span>timeout_ms <el-input-number v-model="form.timeout_ms" :min="1000" :max="300000" style="width:120px" /></span>
        <el-checkbox v-model="form.fast_path">fast_path ({{ t('tools.fastpath-hint') }})</el-checkbox>
        <span>approval_mode
          <el-select v-model="form.approval_mode" style="width:180px">
            <el-option value="strict" :label="t('tools.approval-strict')" />
            <el-option value="lax" :label="t('tools.approval-lax')" />
          </el-select>
        </span>
      </div>
      <p class="v-hint">{{ t('tools.approval-hint') }}</p>
      <div style="margin-top:8px">
        <label>allowed_args_schema (JSON Schema)</label>
        <el-input v-model="form.allowed_args_schema" type="textarea" :rows="3" placeholder='{"required":["path"],"properties":{"path":{"type":"string"}}}' />
      </div>
      <div style="margin-top:8px">
        <label>description</label>
        <el-input v-model="form.description" :placeholder="t('tools.placeholder-desc')" />
      </div>
      <template #footer>
        <el-button @click="editorVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="save">{{ t('tools.btn-save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';

const { t } = useI18n();
const feedback = useFeedbackStore();
const session = useSessionStore();

const tools = ref<any[]>([]);
const editorVisible = ref(false);
const editingName = ref<string | null>(null);
const form = reactive<any>({
  tool_name: '', risk_class: 'low', sandbox_type: 'none', timeout_ms: 30000,
  fast_path: false, approval_mode: 'strict', allowed_args_schema: '', description: ''
});

function riskLabel(rc: string) { return rc || 'low'; }
function riskTagType(rc: string): any {
  return ({ low: 'success', medium: 'warning', high: 'danger', network: 'primary' } as any)[rc] || 'info';
}

async function load() {
  try { tools.value = await admin<any[]>('/tools') || []; }
  catch (e: any) { feedback.log(e.message, 'err'); }
}

function showEditor(tool: any) {
  editingName.value = tool?.tool_name || null;
  Object.assign(form, {
    tool_name: tool?.tool_name || '',
    risk_class: tool?.risk_class || 'low',
    sandbox_type: tool?.sandbox_type || 'none',
    timeout_ms: tool?.timeout_ms || 30000,
    fast_path: tool?.fast_path || false,
    approval_mode: tool?.approval_mode || 'strict',
    allowed_args_schema: tool?.allowed_args_schema || '',
    description: tool?.description || ''
  });
  editorVisible.value = true;
}

async function save() {
  const name = form.tool_name.trim();
  if (!name) { feedback.log('tool_name ' + t('common.none'), 'err'); return; }
  if (!/^[a-z][a-z0-9_-]*$/.test(name)) { feedback.log('tool_name 格式无效', 'err'); return; }
  let schema = form.allowed_args_schema.trim();
  if (schema) {
    try { JSON.parse(schema); } catch (e: any) { feedback.log('allowed_args_schema JSON 无效: ' + e.message, 'err'); return; }
  }
  const body = {
    tool_name: name,
    risk_class: form.risk_class,
    sandbox_type: form.sandbox_type,
    timeout_ms: parseInt(form.timeout_ms, 10) || 30000,
    fast_path: form.fast_path,
    approval_mode: form.approval_mode,
    allowed_args_schema: schema || null,
    description: form.description.trim() || null
  };
  try {
    await admin('/tools', { method: 'POST', body: JSON.stringify(body) });
    feedback.log('工具已保存: ' + name, 'ok');
    editorVisible.value = false;
    await load();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function deleteTool(name: string) {
  try { await ElMessageBox.confirm('确认删除工具 "' + name + '"？', { type: 'warning' }); }
  catch { return; }
  try {
    await admin('/tools/' + encodeURIComponent(name), { method: 'DELETE' });
    feedback.log('工具已删除: ' + name, 'ok');
    await load();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(load);
watch(() => session.tenant, load);
</script>
