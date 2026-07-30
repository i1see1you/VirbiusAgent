<template>
  <div class="v-card">
    <h2 class="v-card-title">{{ t('rules.title') }}</h2>
    <p class="v-hint" v-html="t('hint.rules')"></p>

    <div class="v-row">
      <span class="v-hint" style="margin:0" v-html="t('rules.current-layer', [rules.currentLayer])"></span>
      <el-button type="primary" @click="openNew">{{ t('rules.btn-new') }}</el-button>
    </div>

    <el-table :data="paginatedRules" size="small" border stripe @row-click="onRowClick">
      <el-table-column :label="t('rules.header-id')" prop="rule_id" />
      <el-table-column :label="t('rules.header-runtime')" prop="runtime" width="90" />
      <el-table-column :label="t('rules.header-bind')" width="140">
        <template #default="{ row }"><code>{{ formatBindScope(row.scope) }}</code></template>
      </el-table-column>
      <el-table-column :label="t('rules.header-rollout')" width="90">
        <template #default="{ row }"><span class="v-tag" :class="statusCls(row.rollout_state)">{{ row.rollout_state || 'draft' }}</span></template>
      </el-table-column>
      <el-table-column :label="t('rules.header-rev')" prop="current_revision" width="60" />
      <el-table-column :label="t('rules.header-risk')" prop="risk_score" width="60" />
      <el-table-column :label="t('rules.header-intent')" width="80">
        <template #default="{ row }">{{ row.intent_action || 'deny' }}</template>
      </el-table-column>
      <el-table-column :label="t('rules.header-enforce')" width="100">
        <template #default="{ row }">{{ row.enforce_mode }}{{ row.canary_percent ? '@' + row.canary_percent + '%' : '' }}</template>
      </el-table-column>
      <el-table-column :label="t('rules.header-async')" width="70">
        <template #default="{ row }">{{ row.is_async ? 'async' : '-' }}</template>
      </el-table-column>
      <el-table-column :label="t('rules.header-reason')" prop="reason_code" />
    </el-table>

    <el-pagination v-if="total > size" small background layout="prev, pager, next"
      v-model:current-page="page" :page-size="size" :total="total"
      @current-change="scrollTop" />

    <div v-if="editorVisible" class="v-card" style="margin-top:16px;background:#f8fafc">
      <h3 style="font-size:15px;margin:0 0 8px">
        {{ isNew ? t('rules.edit-title-new') : t('rules.edit-title-edit') }}
        <span v-if="!isNew" style="margin-left:6px">{{ selectedRuleId }}</span>
      </h3>

      <div v-if="isNew" class="v-row">
        <label>rule_id <el-input v-model="form.rule_id" style="width:200px" /></label>
        <label>runtime
          <el-select v-model="form.runtime" style="width:140px" @change="onRuntimeChange">
            <el-option v-for="rt in layerRuntimes" :key="rt" :value="rt" :label="rt" />
          </el-select>
        </label>
      </div>

      <div class="v-row">
        <label>{{ t('rules.label-reason') }} <el-input v-model="form.reason" :disabled="isReadOnly" style="width:200px" /></label>
        <label>{{ t('rules.label-risk') }}
          <el-input-number v-model="form.risk" :min="0" :max="100" :disabled="isReadOnly || isDlp" style="width:100px" />
        </label>
        <label>{{ t('rules.label-intent') }}
          <el-select v-model="form.intent" :disabled="isReadOnly || isAsync || isDlp" style="width:120px">
            <el-option value="deny" label="deny" />
            <el-option value="allow" label="allow" />
            <el-option value="challenge" label="challenge" />
            <el-option value="review" label="review" />
          </el-select>
        </label>
        <el-checkbox v-if="showAsync" v-model="form.is_async" :disabled="isReadOnly" @change="onAsyncChange">{{ t('rules.label-async') }}</el-checkbox>
      </div>

      <div v-if="form.is_async" class="v-card" style="padding:12px;background:#fff;border:1px dashed #cbd5e1;margin:8px 0">
        <div class="v-row">
          <label>{{ t('rules.action-type') }}
            <el-select v-model="asyncCfg.type" style="width:150px">
              <el-option value="redis_stream" label="Redis Stream" />
              <el-option value="webhook" label="Webhook" />
            </el-select>
          </label>
          <label v-if="asyncCfg.type === 'redis_stream'">{{ t('rules.stream-key') }}
            <el-input v-model="asyncCfg.stream_key" style="width:240px" /></label>
          <label v-else>{{ t('rules.webhook-url') }}
            <el-input v-model="asyncCfg.url" style="width:240px" /></label>
        </div>
        <label>{{ t('rules.msg-content') }}</label>
        <el-input v-model="asyncCfg.message" type="textarea" :rows="5" />
        <p class="v-hint">{{ t('rules.async-hint') }}</p>
        <AsyncPreview :message="asyncCfg.message" :type="asyncCfg.type"
          :stream-key="asyncCfg.stream_key" :webhook-url="asyncCfg.url" />
      </div>

      <div v-if="showBindScope" class="v-row">
        <label>bind_scope
          <el-select v-model="form.bind_scope" :disabled="isReadOnly" style="width:200px" @change="onBindScopeChange">
            <el-option v-for="o in bindScopeOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </label>
        <label v-if="showToolNames">tool_names <el-input v-model="form.bind_tools" :disabled="isReadOnly" style="width:200px" /></label>
      </div>
      <div v-if="showBindScope" class="v-row">
        <label v-if="showToolNames">mcp_servers <el-input v-model="form.bind_mcp_servers" :disabled="isReadOnly" style="width:200px" /></label>
        <label v-if="showAppIds">app_ids <el-input v-model="form.bind_app_ids" :disabled="isReadOnly" style="width:240px" /></label>
      </div>
      <p v-if="showBindScope" class="v-hint" v-html="t('gw.scope-hint')"></p>

      <div v-if="isScript" class="v-row">
        <label>{{ t('rules.editor-mode') }}
          <el-select v-model="form.editor_mode" :disabled="isReadOnly" style="width:160px" @change="onEditorModeChange">
            <el-option value="simple" :label="t('rules.editor-simple')" />
            <el-option value="advanced" :label="t('rules.editor-advanced')" />
          </el-select>
        </label>
      </div>

      <p v-if="form.runtime === 'groovy'" class="v-hint" v-html="t('hint.groovy')"></p>
      <p v-if="form.runtime === 'lua'" class="v-hint" v-html="t('hint.lua')"></p>
      <p v-if="isPrompt" class="v-hint" v-html="t('hint.prompt')"></p>
      <p v-if="isEdgeDsl" class="v-hint" v-html="t('hint.edge-dsl')"></p>
      <p v-if="isDlp" class="v-hint" v-html="t('hint.dlp-dsl')"></p>

      <div v-if="isScript && form.editor_mode === 'simple'">
        <p class="v-hint">{{ t('rules.condition-hint') }}</p>
        <div v-for="(leaf, i) in conditionLeaves" :key="i" class="v-row" style="background:#fff;padding:6px;border-radius:4px">
          <template v-if="leaf.type === 'list_match'">
            <span>{{ t('rules.cond-list') }}</span>
            <el-select v-model="leaf.list_name" size="small" style="width:160px">
              <el-option v-for="n in listCatalog" :key="n" :value="n" :label="n" />
            </el-select>
            <span>{{ t('rules.cond-match') }}</span>
            <el-select v-model="leaf.value_source" size="small" style="width:140px">
              <el-option value="content" label="content" />
              <el-option v-for="v in varOptions" :key="v" :value="v" :label="v" />
            </el-select>
          </template>
          <template v-else>
            <span>{{ t('rules.cond-cum') }}</span>
            <el-select v-model="leaf.cumulative_name" size="small" style="width:160px">
              <el-option v-for="n in cumNames" :key="n" :value="n" :label="n" />
            </el-select>
            <el-select v-model="leaf.compare" size="small" style="width:80px">
              <el-option value="gte" label="≥" /><el-option value="gt" label=">" />
              <el-option value="lte" label="≤" /><el-option value="lt" label="<" /><el-option value="eq" label="=" />
            </el-select>
            <el-input-number v-model="leaf.threshold" :min="1" size="small" style="width:110px" />
          </template>
          <el-button size="small" link type="danger" @click="conditionLeaves.splice(i,1)">{{ t('common.delete') }}</el-button>
        </div>
        <div class="v-row">
          <el-button size="small" @click="addListCond">{{ t('rules.btn-add-list-cond') }}</el-button>
          <el-button size="small" @click="addCumCond">{{ t('rules.btn-add-cum-cond') }}</el-button>
        </div>
      </div>

      <div v-if="isEdgeDsl && form.editor_mode === 'simple'" style="margin-bottom:8px">
        <div class="v-row">
          <label>{{ t('edge.list-type') }}
            <el-select v-model="edgeBody.list_type" style="width:180px">
              <el-option value="deny" :label="t('edge.list-type.deny')" />
              <el-option value="allow" :label="t('edge.list-type.allow')" />
            </el-select>
          </label>
        </div>
        <label>{{ t('edge.keywords-label') }}</label>
        <el-input v-model="edgeBody.keywordsText" type="textarea" :rows="5" />
      </div>

      <div v-if="isDlp && form.editor_mode === 'simple'" style="margin-bottom:8px">
        <div class="v-row">
          <label>{{ t('dlp.entity-type') }}
            <el-select v-model="dlpBody.entity_type" style="width:180px">
              <el-option value="phone_cn" :label="t('dlp.phone-cn')" />
              <el-option value="idcard_cn" :label="t('dlp.idcard-cn')" />
              <el-option value="email" :label="t('dlp.email')" />
              <el-option value="bank_card_cn" :label="t('dlp.bank-card-cn')" />
              <el-option value="custom_regex" :label="t('dlp.custom-regex')" />
            </el-select>
          </label>
          <label>{{ t('dlp.priority') }} <el-input-number v-model="dlpBody.priority" style="width:100px" /></label>
        </div>
        <label v-if="dlpBody.entity_type === 'custom_regex'">{{ t('dlp.pattern-label') }}</label>
        <el-input v-if="dlpBody.entity_type === 'custom_regex'" v-model="dlpBody.pattern" />
        <label>{{ t('dlp.mask-template') }}</label>
        <el-input v-model="dlpBody.mask_template" :placeholder="t('dlp.placeholder-mask')" />
        <DlpRegexTest v-if="dlpBody.entity_type === 'custom_regex'" :initial-pattern="dlpBody.pattern" />
      </div>

      <div v-if="showBodyTextarea" style="margin-bottom:4px">
        <ScriptEditor
          ref="scriptEditorRef"
          v-model="form.body"
          :language="editorLanguage"
          :completion-sources="completionSources"
          :lint-fn="lintFn"
          :read-only="isReadOnly"
          @save="onSaveShortcut"
        />
        <div class="v-row" style="margin-top:4px">
          <span class="v-hint" style="margin:0;font-size:11px;color:#94a3b8">{{ t('editor.save-hint') }}</span>
        </div>
      </div>

      <div v-if="showValidate" class="v-row" style="margin-top:8px">
        <el-button :disabled="isReadOnly" @click="validateScript">{{ t('rules.btn-validate') }}</el-button>
        <span class="v-hint" style="margin:0" :style="validateStyle">{{ validateMsg }}</span>
      </div>

      <div v-if="showSimulate" class="v-row" style="margin-top:8px">
        <el-checkbox v-model="enableSimulate" :disabled="isReadOnly">{{ t('rules.enable-simulate') }}</el-checkbox>
      </div>
      <RuleSimulate v-if="enableSimulate && showSimulate"
        :rule-id="selectedRuleId || form.rule_id"
        :layer="effectiveLayer()"
        :runtime="form.runtime"
        :body="form.body"
        :scope="buildScope()"
        :intent="form.intent"
        :risk="form.risk"
        :reason="form.reason"
        :editor-mode="form.editor_mode"
        :condition="readConditionPayload()"
        :bundle-id="session.bundleId"
      />

      <div class="v-row" style="margin-top:8px">
        <el-button v-if="(!editMeta && isNew) || (editMeta && editMeta.rollout_state !== 'disabled' && !inExecutionPlane(editMeta.rollout_state))" type="primary" :loading="saving" @click="saveWithDiff">{{ isNew ? t('rules.btn-create') : t('rules.btn-save') }}</el-button>
        <el-button v-if="!isNew && editMeta?.rollout_state === 'draft'" @click="activate">{{ t('rules.btn-activate') }}</el-button>
        <el-button v-if="!isNew && editMeta && editMeta.rollout_state !== 'disabled'" type="danger" @click="disable">{{ t('rules.btn-disable') }}</el-button>
        <el-button v-if="!isNew && editMeta?.rollout_state === 'disabled'" @click="recover">{{ t('rules.btn-enable') }}</el-button>
      </div>
    </div>

    <DiffConfirm ref="diffConfirmRef" :old-text="previousBody" :new-text="form.body"
      @confirm="doSave" @cancel="cancelSave" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch, shallowRef } from 'vue';
import { useI18n } from 'vue-i18n';
import { ElMessageBox } from 'element-plus';
import { useFeedbackStore } from '@/stores/feedback';
import { useRulesStore, LAYER_RUNTIMES } from '@/stores/rules';
import { useSessionStore } from '@/stores/session';
import { admin } from '@/api/client';
import { field, formatBindScope, ruleStatusTagClass, inExecutionPlane } from '@/utils/format';
import ScriptEditor from '@/components/ScriptEditor.vue';
import AsyncPreview from '@/components/AsyncPreview.vue';
import DiffConfirm from '@/components/DiffConfirm.vue';
import RuleSimulate from '@/components/RuleSimulate.vue';
import DlpRegexTest from '@/components/DlpRegexTest.vue';
import { buildCompletionSources, runLint, type ScriptCompletionContext, type LintContext } from '@/utils/scriptAssist';
import type { Diagnostic } from '@codemirror/lint';
import type { EditorView } from '@codemirror/view';

const { t } = useI18n();
const feedback = useFeedbackStore();
const rules = useRulesStore();
const session = useSessionStore();

const page = ref(1);
const size = ref(50);
const total = ref(0);
const ruleList = ref<any[]>([]);
const paginatedRules = computed(() => ruleList.value.slice((page.value - 1) * size.value, page.value * size.value));
const editorVisible = ref(false);
const isNew = ref(false);
const selectedRuleId = ref<string | null>(null);
const editMeta = ref<any>(null);
const saving = ref(false);
const listCatalog = ref<string[]>([]);
const cumNames = ref<string[]>([]);
const conditionLeaves = ref<any[]>([]);
const validateMsg = ref('');
const validateOk = ref<boolean | null>(null);
const scriptEditorRef = shallowRef<InstanceType<typeof ScriptEditor> | null>(null);
const diffConfirmRef = shallowRef<InstanceType<typeof DiffConfirm> | null>(null);
const previousBody = ref('');
const enableSimulate = ref(false);

const form = reactive<any>({
  rule_id: '', runtime: 'groovy', reason: 'CUSTOM_RULE', risk: 100, intent: 'deny',
  is_async: false, bind_scope: 'global', bind_tools: '', bind_mcp_servers: '', bind_app_ids: '',
  editor_mode: 'simple', body: ''
});
const asyncCfg = reactive<any>({ type: 'redis_stream', stream_key: '', url: '', message: '' });
const edgeBody = reactive<any>({ list_type: 'deny', keywordsText: '' });
const dlpBody = reactive<any>({ entity_type: 'phone_cn', priority: 0, pattern: '', mask_template: '' });

const layerRuntimes = computed(() => LAYER_RUNTIMES[rules.currentLayer] || []);
const isPrompt = computed(() => form.runtime === 'prompt');
const isScript = computed(() => form.runtime === 'groovy' || form.runtime === 'lua');
const isEdgeDsl = computed(() => form.runtime === 'lua-dsl');
const isDlp = computed(() => form.runtime === 'dlp-dsl');
const isKernel = computed(() => ['falco', 'landlock', 'gvisor'].includes(form.runtime));
const isFalco = computed(() => form.runtime === 'falco');
const isEdgeForm = computed(() => isEdgeDsl.value || isDlp.value);
const isAsync = computed(() => !!form.is_async);
const showAsync = computed(() => rules.currentLayer === 'cloud' && (form.runtime === 'groovy' || form.runtime === 'prompt'));
const showBindScope = computed(() => ['lua', 'groovy', 'prompt'].includes(form.runtime) || isEdgeForm.value || isFalco.value);
const showValidate = computed(() => isScript.value || isEdgeDsl.value || isDlp.value);
const showBodyTextarea = computed(() => !((isScript.value || isEdgeDsl.value || isDlp.value) && form.editor_mode === 'simple'));
const showSimulate = computed(() => (rules.currentLayer === 'cloud' || isNew.value) && (isScript.value || isPrompt.value));
const varOptions = computed(() => rules.contextVars.map((v: any) => 'var:' + v.logical).filter(Boolean));
const validateStyle = computed(() => ({ color: validateOk.value === false ? '#991b1b' : (validateOk.value ? '#166534' : '#64748b') }));
const bindScopeOptions = computed(() => {
  if (isEdgeForm.value) return [{ value: 'global', label: t('gw.scope.edge-global') }, { value: 'service', label: t('gw.scope.edge-service') }];
  if (isFalco.value) return [{ value: 'global', label: t('gw.scope.global') }, { value: 'service', label: t('gw.scope.service') }];
  return [{ value: 'global', label: t('gw.scope.global') }, { value: 'tool', label: t('gw.scope.tool') }, { value: 'service', label: t('gw.scope.service') }];
});
const isReadOnly = computed(() => !isNew.value && editMeta.value?.rollout_state === 'disabled');
const showToolNames = computed(() => !isEdgeForm.value && form.bind_scope === 'tool');
const showAppIds = computed(() => form.bind_scope === 'service' || (!isEdgeForm.value && form.bind_scope === 'tool'));

const editorLanguage = computed(() => {
  if (form.runtime === 'groovy') return 'groovy' as const;
  if (form.runtime === 'lua') return 'lua' as const;
  if (['lua-dsl', 'dlp-dsl', 'falco', 'landlock', 'gvisor', 'prompt'].includes(form.runtime)) return 'json' as const;
  return 'groovy' as const;
});

const scriptCompletionCtx = computed<ScriptCompletionContext>(() => ({
  runtime: editorLanguage.value,
  listNames: listCatalog.value,
  cumNames: cumNames.value,
  contextVars: rules.contextVars.map((v: any) => v.logical).filter(Boolean)
}));

const completionSources = computed(() => buildCompletionSources(scriptCompletionCtx.value));

const lintCtx = computed<LintContext>(() => ({
  runtime: editorLanguage.value,
  layer: effectiveLayer(),
  listNames: listCatalog.value,
  cumNames: cumNames.value,
  contextVars: rules.contextVars.map((v: any) => v.logical).filter(Boolean)
}));

const lintFn = computed<((view: EditorView) => Diagnostic[]) | null>(() => {
  if (!showValidate.value || form.editor_mode === 'simple') return null;
  return (view: EditorView) => {
    runLint(view, lintCtx.value).then(diags => {
      // trigger re-render
    });
    return [];
  };
});

function scrollTop() { document.querySelector('.v-scroll')?.scrollTo(0, 0); }
function statusCls(st: string) { return ruleStatusTagClass(st); }
function effectiveLayer(): string {
  if (rules.currentLayer === 'kernel') return form.runtime === 'falco' ? 'falco' : 'sandbox';
  return rules.currentLayer;
}
function defaultBody(layer: string, runtime: string): string {
  if (runtime === 'groovy') return "def decide(ctx) {\n  return ctx.listMatch('deny_keyword')\n}";
  if (runtime === 'lua') return "function decide(ctx)\n  return listMatch('deny_keyword', ctx.content)\nend";
  if (runtime === 'prompt') return t('rules.prompt-default');
  if (runtime === 'lua-dsl') return JSON.stringify({ list_type: 'deny', keywords: [] }, null, 2);
  if (runtime === 'dlp-dsl') return JSON.stringify({ entity_type: 'phone_cn', priority: 0, dry_run: false }, null, 2);
  if (runtime === 'falco') return JSON.stringify({ condition: 'evt.num > 0', output: 'Falco rule triggered', priority: 'WARNING', tags: [] }, null, 2);
  if (runtime === 'landlock') return JSON.stringify({ tool_name: 'read_file', read_paths: ['/tmp/data/*'], write_paths: [], exec_paths: ['/usr/bin/cat'] }, null, 2);
  if (runtime === 'gvisor') return JSON.stringify({ runsc_path: '/usr/local/bin/runsc', rootfs_path: '/opt/virbius/rootfs', min_warm: 2 }, null, 2);
  return '';
}

async function loadListsAndCums() {
  try {
    const data = await admin<any>('/lists');
    listCatalog.value = (data.lists || []).map((x: any) => field(x, 'list_name', 'listName')).filter(Boolean).sort();
  } catch { /* ignore */ }
  try {
    const data = await admin<any>('/cumulatives');
    const rows = Array.isArray(data) ? data : (data?.rows || []);
    cumNames.value = rows.map((c: any) => field(c, 'cumulative_name', 'cumulativeName')).filter(Boolean);
  } catch { /* ignore */ }
}

async function loadRules() {
  try {
    let r: any[];
    if (rules.currentLayer === 'kernel') {
      const [f, s] = await Promise.all([admin('/rules?layer=falco'), admin('/rules?layer=sandbox')]);
      r = [...f, ...s];
    } else {
      r = await admin('/rules?layer=' + encodeURIComponent(rules.currentLayer));
    }
    ruleList.value = r; total.value = r.length; page.value = 1;
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

async function onRowClick(row: any) {
  if (rules.ruleFormDirty) {
    try { await ElMessageBox.confirm(t('rules.confirm-unsaved'), { type: 'warning' }); }
    catch { return; }
  }
  await selectRule(row.rule_id);
}

function resetEditor() {
  Object.assign(form, { rule_id: '', runtime: layerRuntimes.value[0], reason: 'CUSTOM_RULE', risk: 100, intent: 'deny', is_async: false, bind_scope: 'global', bind_tools: '', bind_mcp_servers: '', bind_app_ids: '', editor_mode: 'simple', body: '' });
  Object.assign(asyncCfg, { type: 'redis_stream', stream_key: '', url: '', message: '' });
  Object.assign(edgeBody, { list_type: 'deny', keywordsText: '' });
  Object.assign(dlpBody, { entity_type: 'phone_cn', priority: 0, pattern: '', mask_template: '' });
  conditionLeaves.value = [];
  validateMsg.value = ''; validateOk.value = null;
  previousBody.value = '';
  enableSimulate.value = false;
}

function openNew() {
  if (rules.ruleFormDirty) {
    ElMessageBox.confirm(t('rules.confirm-unsaved'), { type: 'warning' }).then(doOpenNew).catch(() => {});
  } else doOpenNew();
}
function doOpenNew() {
  isNew.value = true; selectedRuleId.value = null; editMeta.value = { layer: rules.currentLayer, rollout_state: 'draft' };
  resetEditor();
  form.runtime = layerRuntimes.value[0];
  form.body = defaultBody(rules.currentLayer, form.runtime);
  if (isScript.value) {
    form.editor_mode = 'simple';
    conditionLeaves.value = [{ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' }];
  } else {
    form.editor_mode = 'advanced';
  }
  editorVisible.value = true;
  rules.resetDirty();
}

function loadBindUiFromScope(scope: any) {
  const s = scope || {};
  let bs = s.bind_scope || 'global';
  if (isEdgeForm.value && bs === 'tool') bs = 'global';
  if (isFalco.value && bs === 'tool') bs = 'global';
  form.bind_scope = bs;
  const ref = s.bind_ref || {};
  form.bind_tools = Array.isArray(ref.tool_names) ? ref.tool_names.join(', ') : '';
  form.bind_mcp_servers = Array.isArray(ref.mcp_servers) ? ref.mcp_servers.join(', ') : '';
  form.bind_app_ids = Array.isArray(ref.app_ids) ? ref.app_ids.join(', ') : '';
}

async function selectRule(ruleId: string) {
  try {
    const r = await admin<any>('/rules/' + encodeURIComponent(ruleId));
    isNew.value = false; selectedRuleId.value = ruleId; editMeta.value = r;
    form.reason = r.reason_code || ''; form.risk = r.risk_score ?? 100; form.intent = r.intent_action || 'deny';
    form.is_async = !!r.is_async;
    loadAsyncCfg(r.async_action_config);
    form.runtime = r.runtime;
    const body = r.body;
    form.body = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
    previousBody.value = form.body;
    form.editor_mode = 'advanced';
    conditionLeaves.value = [];
    if (isEdgeDsl.value) {
      const obj = (typeof body === 'object' && body) ? body : (() => { try { return JSON.parse(form.body); } catch { return null; } })();
      if (obj && (obj.list_type === 'deny' || obj.list_type === 'allow')) {
        form.editor_mode = 'simple'; edgeBody.list_type = obj.list_type;
        edgeBody.keywordsText = Array.isArray(obj.keywords) ? obj.keywords.join('\n') : '';
      }
    } else if (isDlp.value) {
      const obj = (typeof body === 'object' && body) ? body : (() => { try { return JSON.parse(form.body); } catch { return null; } })();
      if (obj && obj.entity_type) {
        form.editor_mode = 'simple';
        dlpBody.entity_type = obj.entity_type; dlpBody.priority = obj.priority ?? 0;
        dlpBody.pattern = obj.pattern || ''; dlpBody.mask_template = obj.mask_template || '';
      }
    } else if (isScript.value) {
      try {
        const parsed = await admin<any>('/rules/parse-condition', { method: 'POST', body: JSON.stringify({ layer: r.layer, runtime: r.runtime, script: form.body }) });
        if (parsed.parseable && parsed.condition) {
          form.editor_mode = 'simple';
          const c = parsed.condition;
          conditionLeaves.value = c.op === 'and' && Array.isArray(c.children) ? c.children.map((x: any) => ({ ...x })) : [c];
        }
      } catch { /* ignore */ }
    }
    loadBindUiFromScope(r.scope || {});
    editorVisible.value = true;
    rules.resetDirty();
  } catch (e: any) { feedback.log(e.message, 'err'); }
}

function loadAsyncCfg(jsonStr: string) {
  Object.assign(asyncCfg, { type: 'redis_stream', stream_key: '', url: '', message: '' });
  if (!jsonStr) return;
  try {
    const cfg = JSON.parse(jsonStr);
    asyncCfg.type = cfg.type || 'redis_stream';
    if (cfg.type === 'webhook') { asyncCfg.url = cfg.url || ''; asyncCfg.message = cfg.body ? JSON.stringify(cfg.body, null, 2) : ''; }
    else { asyncCfg.stream_key = cfg.stream_key || ''; asyncCfg.message = cfg.message ? JSON.stringify(cfg.message, null, 2) : ''; }
  } catch { /* ignore */ }
}

function onRuntimeChange() {
  form.body = defaultBody(rules.currentLayer, form.runtime);
  if (isScript.value) { form.editor_mode = 'simple'; conditionLeaves.value = [{ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' }]; }
  else if (isEdgeDsl.value) { form.editor_mode = 'simple'; edgeBody.list_type = 'deny'; edgeBody.keywordsText = ''; }
  else if (isDlp.value) { form.editor_mode = 'simple'; }
  else form.editor_mode = 'advanced';
  rules.markDirty();
}
function onAsyncChange() { if (isAsync.value) form.intent = 'allow'; rules.markDirty(); }
function onBindScopeChange() { rules.markDirty(); }
function onEditorModeChange() { rules.markDirty(); }
function addListCond() { conditionLeaves.value.push({ type: 'list_match', list_name: listCatalog.value[0] || 'deny_keyword', value_source: 'content' }); rules.markDirty(); }
function addCumCond() { conditionLeaves.value.push({ type: 'cumulative', cumulative_name: cumNames.value[0] || 'user_req_1h', compare: 'gte', threshold: 120 }); rules.markDirty(); }

function edgeBodyFromForm(): any {
  const kws = edgeBody.keywordsText.split(/[\n,，]/).map((s: string) => s.trim()).filter(Boolean);
  return { list_type: edgeBody.list_type, keywords: kws };
}
function dlpBodyFromForm(): any {
  const out: any = { entity_type: dlpBody.entity_type, priority: dlpBody.priority ?? 0, dry_run: false };
  if (dlpBody.entity_type === 'custom_regex' && dlpBody.pattern) out.pattern = dlpBody.pattern;
  if (dlpBody.mask_template) out.mask_template = dlpBody.mask_template;
  return out;
}
function buildAsyncCfg(): string | null {
  if (!form.is_async) return null;
  let message: any = asyncCfg.message.trim();
  try { message = JSON.parse(message); } catch { /* keep string */ }
  const cfg: any = { type: asyncCfg.type };
  if (asyncCfg.type === 'redis_stream') { if (asyncCfg.stream_key) cfg.stream_key = asyncCfg.stream_key; cfg.message = message || {}; }
  else { if (asyncCfg.url) cfg.url = asyncCfg.url; cfg.body = message || {}; }
  return JSON.stringify(cfg);
}
function buildScope(): any {
  if (isEdgeForm.value && form.bind_scope === 'tool') return { bind_scope: 'global' };
  if (isFalco.value && form.bind_scope === 'tool') return { bind_scope: 'global' };
  const scope: any = { bind_scope: form.bind_scope };
  const ref: any = {};
  if (form.bind_scope === 'tool') {
    const tools = String(form.bind_tools || '').split(',').map(s => s.trim()).filter(Boolean);
    if (tools.length) ref.tool_names = tools;
    const upstreams = String(form.bind_mcp_servers || '').split(',').map(s => s.trim()).filter(Boolean);
    if (upstreams.length) ref.mcp_servers = upstreams;
    const ids = String(form.bind_app_ids || '').split(',').map(s => s.trim()).filter(Boolean);
    if (ids.length) ref.app_ids = ids;
  } else if (form.bind_scope === 'service') {
    const ids = String(form.bind_app_ids || '').split(',').map(s => s.trim()).filter(Boolean);
    if (ids.length) ref.app_ids = ids;
  }
  if (Object.keys(ref).length) scope.bind_ref = ref;
  return scope;
}
function readConditionPayload(): any {
  if (!conditionLeaves.value.length) return null;
  if (conditionLeaves.value.length === 1) return { ...conditionLeaves.value[0] };
  return { op: 'and', children: conditionLeaves.value.map(c => ({ ...c })) };
}

async function resolveBodyForSave(): Promise<any> {
  if (isPrompt.value) { try { return JSON.parse(form.body); } catch { return form.body; } }
  if (isEdgeDsl.value) { return form.editor_mode === 'simple' ? edgeBodyFromForm() : JSON.parse(form.body); }
  if (isDlp.value) { return form.editor_mode === 'simple' ? dlpBodyFromForm() : JSON.parse(form.body); }
  if (isKernel.value) { return JSON.parse(form.body); }
  if (form.editor_mode === 'simple' && conditionLeaves.value.length) {
    const data = await admin<any>('/rules/compile-condition', { method: 'POST', body: JSON.stringify({ layer: effectiveLayer(), runtime: form.runtime, condition: readConditionPayload() }) });
    return data.script;
  }
  try { return JSON.parse(form.body); } catch { return form.body; }
}

async function validateScript() {
  try {
    const body = await resolveBodyForSave();
    const data = await admin<any>('/rules/validate-script', { method: 'POST', body: JSON.stringify({ layer: effectiveLayer(), runtime: form.runtime, body }) });
    validateOk.value = !!data.valid;
    const warns = data.warnings || [];
    validateMsg.value = data.valid ? (warns.length ? t('rules.valid-pass-with-warn', [warns.join('; ')]) : t('rules.valid-pass')) : t('rules.valid-fail', [(data.errors || []).join('; ')]);
  } catch (e: any) { validateOk.value = false; validateMsg.value = e.message; }
}

function onSaveShortcut() {
  saveWithDiff();
}

function saveWithDiff() {
  if (editMeta.value && editMeta.value.rollout_state === 'disabled') { feedback.log(t('rules.disabled-cant-edit'), 'warn'); return; }
  if (editMeta.value && inExecutionPlane(editMeta.value.rollout_state)) { feedback.log(t('rules.running-cant-edit', [editMeta.value.rollout_state]), 'warn'); return; }
  if (previousBody.value && form.body !== previousBody.value) {
    diffConfirmRef.value?.open();
  } else {
    doSave();
  }
}

function cancelSave() { /* user cancelled */ }

async function doSave() {
  const ruleId = isNew.value ? form.rule_id.trim() : selectedRuleId.value;
  if (!ruleId) { feedback.log(t('rules.id-required'), 'warn'); return; }
  const layer = isNew.value ? effectiveLayer() : editMeta.value.layer;
  const runtime = isNew.value ? form.runtime : editMeta.value.runtime;
  if (form.bind_scope === 'service' && isEdgeForm.value) {
    const ids = String(form.bind_app_ids || '').split(',').map(s => s.trim()).filter(Boolean);
    if (!ids.length) { feedback.log(t('rules.edge-service-required'), 'warn'); return; }
  }
  if (form.bind_scope === 'tool') {
    const tools = String(form.bind_tools || '').split(',').map(s => s.trim()).filter(Boolean);
    const servers = String(form.bind_mcp_servers || '').split(',').map(s => s.trim()).filter(Boolean);
    if (!tools.length && !servers.length) { feedback.log(t('rules.tool-bind-required'), 'warn'); return; }
  }
  saving.value = true;
  try {
    const body = await resolveBodyForSave();
    if (isDlp.value && body.entity_type === 'custom_regex' && !(body.pattern || '').trim()) {
      feedback.log(t('rules.custom-regex-required'), 'warn'); saving.value = false; return;
    }
    if (runtime === 'lua' || runtime === 'groovy') {
      const vr = await admin<any>('/rules/validate-script', { method: 'POST', body: JSON.stringify({ layer, runtime, body }) });
      validateOk.value = !!vr.valid;
      validateMsg.value = vr.valid ? t('rules.valid-pass') : t('rules.valid-fail', [(vr.errors || []).join('; ')]);
      if (!vr.valid) { feedback.log(validateMsg.value, 'err'); saving.value = false; return; }
    }
    const isAsync = !!form.is_async;
    const payload = {
      rule_id: ruleId,
      bundle_id: session.bundleId,
      layer, runtime,
      reason_code: form.reason,
      risk_score: isDlp.value ? 0 : Number(form.risk),
      intent_action: isAsync ? 'allow' : (isDlp.value ? 'allow' : form.intent),
      scope: buildScope(),
      body,
      editor_mode: isPrompt.value ? null : (form.editor_mode === 'simple' ? 'simple' : 'advanced'),
      condition: (isPrompt.value || isEdgeForm.value || form.editor_mode !== 'simple') ? null : readConditionPayload(),
      is_async: isAsync,
      async_action_config: buildAsyncCfg()
    };
    try {
      await admin('/rules', { method: 'POST', body: JSON.stringify(payload) });
      feedback.log(isNew.value ? t('rules.created') : t('rules.saved'), 'ok');
      previousBody.value = form.body;
      isNew.value = false;
      await selectRule(ruleId);
      await loadRules();
    } catch (e: any) {
      feedback.log(t('rules.save-fail', [e.message]), 'err');
    }
  } catch (e: any) {
    feedback.log(e.message, 'err');
  } finally {
    saving.value = false;
  }
}

async function activate() {
  if (!selectedRuleId.value) return;
  try { await admin('/rules/' + encodeURIComponent(selectedRuleId.value) + '/rollout/publish', { method: 'POST' }); feedback.log(t('rules.btn-activate'), 'ok'); await selectRule(selectedRuleId.value); await loadRules(); }
  catch (e: any) { feedback.log(e.message, 'err'); }
}
async function disable() {
  if (!selectedRuleId.value) return;
  try { await admin('/rules/' + encodeURIComponent(selectedRuleId.value) + '/rollout/disable', { method: 'POST' }); feedback.log(t('rules.btn-disable'), 'ok'); await selectRule(selectedRuleId.value); await loadRules(); }
  catch (e: any) { feedback.log(e.message, 'err'); }
}
async function recover() {
  if (!selectedRuleId.value) return;
  try { await admin('/rules/' + encodeURIComponent(selectedRuleId.value) + '/rollout/recover', { method: 'POST' }); feedback.log(t('rules.btn-enable'), 'ok'); await selectRule(selectedRuleId.value); await loadRules(); }
  catch (e: any) { feedback.log(e.message, 'err'); }
}

onMounted(async () => { await loadListsAndCums(); await loadRules(); });
watch(() => rules.currentLayer, () => { editorVisible.value = false; rules.resetDirty(); loadRules(); });
watch(() => session.tenant, async () => { await loadListsAndCums(); await loadRules(); });
</script>
