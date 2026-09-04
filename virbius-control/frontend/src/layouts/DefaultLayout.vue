<template>
  <el-config-provider :locale="epLocale">
    <div class="v-layout">
      <aside class="v-sidebar" :class="{ collapsed: session.sidebarCollapsed }">
        <div class="v-sidebar-head">
          <span class="v-sidebar-title">{{ t('nav.title') }}</span>
          <button class="v-sidebar-toggle" @click="session.toggleSidebar"
            :title="session.sidebarCollapsed ? t('nav.expand') : t('nav.collapse')">
            {{ session.sidebarCollapsed ? '▶' : '◀' }}
          </button>
        </div>
        <nav class="v-sidebar-nav">
          <div class="v-nav-section">
            <div class="v-nav-section-label">{{ t('nav.group-ops') }}</div>
            <button v-for="n in opsItems" :key="n.to" class="v-nav-item"
              :class="{ active: isActive(n.to) }" :title="t(n.label)" @click="go(n.to)">
              <span class="v-nav-icon" v-html="icons[n.name]"></span>
              <span class="v-nav-label">{{ t(n.label) }}</span>
            </button>
          </div>

          <div class="v-nav-section">
            <div class="v-nav-section-label">{{ t('nav.group-policy') }}</div>
            <div class="v-nav-group" :class="{ expanded: rulesExpanded }">
              <button class="v-nav-item v-nav-group-head"
                :class="{
                  active: rulesOnPage && !rulesChildrenVisible,
                  'in-section': rulesOnPage && rulesChildrenVisible
                }"
                :title="t('nav.rules')" @click="onRulesHead">
                <span class="v-nav-icon" v-html="icons.rules"></span>
                <span class="v-nav-label">{{ t('nav.rules') }}</span>
                <span class="v-nav-chevron">▼</span>
              </button>
              <div class="v-nav-sublist">
                <button v-for="l in layers" :key="l.key" class="v-nav-sub"
                  :class="{ active: isActive('/rules') && rules.currentLayer === l.key }"
                  :title="t(l.label)"
                  @click="selectLayer(l.key)">
                  <span class="v-nav-label">{{ t(l.label) }}</span>
                </button>
              </div>
            </div>
            <button v-for="n in policyItems" :key="n.to" class="v-nav-item"
              :class="{ active: isActive(n.to) }" :title="t(n.label)" @click="go(n.to)">
              <span class="v-nav-icon" v-html="icons[n.name]"></span>
              <span class="v-nav-label">{{ t(n.label) }}</span>
            </button>
          </div>

          <div class="v-nav-section">
            <div class="v-nav-section-label">{{ t('nav.group-assets') }}</div>
            <button v-for="n in assetItems" :key="n.to" class="v-nav-item"
              :class="{ active: isActive(n.to) }" :title="t(n.label)" @click="go(n.to)">
              <span class="v-nav-icon" v-html="icons[n.name]"></span>
              <span class="v-nav-label">{{ t(n.label) }}</span>
            </button>
          </div>

          <div class="v-nav-section">
            <div class="v-nav-section-label">{{ t('nav.group-platform') }}</div>
            <button v-for="n in platformItems" :key="n.to" class="v-nav-item"
              :class="{ active: isActive(n.to) }" :title="t(n.label)" @click="go(n.to)">
              <span class="v-nav-icon" v-html="icons[n.name]"></span>
              <span class="v-nav-label">{{ t(n.label) }}</span>
            </button>
          </div>
        </nav>
      </aside>

      <div class="v-main">
        <header class="v-topbar">
          <label class="topbar-field">
            <span>{{ t('topbar.tenant') }}</span>
            <el-select :model-value="tenantModel" filterable @change="onTenantChange">
              <el-option v-for="tn in tenants" :key="tn.id"
                :label="tn.id + ' · ' + (tn.name || '')" :value="tn.id" />
            </el-select>
          </label>
          <el-button size="small" @click="toggleLang">{{ locale === 'zh' ? t('topbar.lang-zh') : t('topbar.lang-en') }}</el-button>
        </header>

        <div class="v-scroll">
          <router-view :key="routeKey" />
        </div>
      </div>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { ElMessage, ElMessageBox } from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import en from 'element-plus/es/locale/lang/en';
import { useSessionStore } from '@/stores/session';
import { useRulesStore, type LayerKey } from '@/stores/rules';
import { useFeedbackStore } from '@/stores/feedback';
import { adminRoot } from '@/api/client';
import { field } from '@/utils/format';

const { t, locale } = useI18n();
const router = useRouter();
const route = useRoute();
const session = useSessionStore();
const rules = useRulesStore();
const feedback = useFeedbackStore();

const epLocale = computed(() => (locale.value === 'zh' ? zhCn : en));

const icons: Record<string, string> = {
  tenants: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="2.5"/><path d="M2 17c0-3 2-5 5-5s5 2 5 5"/><circle cx="14" cy="7" r="2.5"/><path d="M11 17c0-3 2-5 4-5s4 2 4 5"/></svg>',
  lists: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 5h14M3 10h14M3 15h14"/></svg>',
  cumulatives: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 17V4m5 13V8m5 9v-6m5 6V3"/></svg>',
  tools: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="10" cy="10" r="3"/><path d="M10 7V3m0 14v-4m3-3h4M3 10h4"/></svg>',
  license: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M5 18V3h10v15l-5-3-5 3z"/></svg>',
  rules: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 3h12v14H4z"/><path d="M7 7h6M7 11h6"/></svg>',
  rollout: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 3v12M6 11l4 4 4-4"/><path d="M3 17h14"/></svg>',
  'audit-center': '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="9" r="5"/><path d="M13 13l4 4"/></svg>',
  monitor: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 16l5-7 4 3 5-8"/></svg>',
  challenge: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M10 3l2.5 5 5.5.5-4 4 1 5.5L10 15l-5 3 1-5.5-4-4L7.5 8 10 3z"/></svg>',
  trace: '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="5" cy="5" r="1.5"/><circle cx="15" cy="10" r="1.5"/><circle cx="10" cy="16" r="1.5"/><path d="M6 6l8 3M16 11l-6 4"/></svg>'
};

const opsItems = [
  { to: '/challenge', name: 'challenge', label: 'nav.challenge' },
  { to: '/monitor', name: 'monitor', label: 'nav.monitor' },
  { to: '/trace', name: 'trace', label: 'nav.trace' },
  { to: '/audit-center', name: 'audit-center', label: 'nav.audit-center' }
];
const policyItems = [
  { to: '/rollout', name: 'rollout', label: 'nav.rollout' }
];
const assetItems = [
  { to: '/lists', name: 'lists', label: 'nav.lists' },
  { to: '/cumulatives', name: 'cumulatives', label: 'nav.cumulatives' },
  { to: '/tools', name: 'tools', label: 'nav.tools' },
  { to: '/license', name: 'license', label: 'nav.license' }
];
const platformItems = [
  { to: '/tenants', name: 'tenants', label: 'nav.tenants' }
];
const layers: { key: LayerKey; label: string }[] = [
  { key: 'cloud', label: 'nav.cloud' },
  { key: 'gateway', label: 'nav.gateway' },
  { key: 'edge', label: 'nav.edge' },
  { key: 'kernel', label: 'nav.kernel' }
];

const rulesExpanded = ref(true);
const tenants = ref<{ id: string; name: string }[]>([]);
const tenantModel = computed(() => session.tenant);
const rulesOnPage = computed(() => route.path === '/rules');
const rulesChildrenVisible = computed(() => rulesExpanded.value && !session.sidebarCollapsed);

const routeKey = computed(() => route.path + ':' + session.tenant + ':' + rules.currentLayer);

watch(() => feedback.logMsg, (msg) => {
  if (!msg) return;
  if (feedback.logLevel !== 'err') {
    const typeMap: Record<string, 'success' | 'warning' | 'info'> = {
      ok: 'success', warn: 'warning', info: 'info'
    };
    ElMessage({ message: msg, type: typeMap[feedback.logLevel] || 'info', duration: 3000, showClose: true });
  }
  feedback.clear();
});

function isActive(to: string) { return route.path === to; }

async function go(to: string) {
  if (to !== '/rules' && route.path === '/rules' && rules.ruleFormDirty) {
    try { await ElMessageBox.confirm(t('rules.confirm-unsaved'), { type: 'warning' }); }
    catch { return; }
    rules.resetDirty();
  }
  feedback.clear();
  router.push(to);
}

async function onRulesHead() {
  if (route.path !== '/rules') {
    feedback.clear();
    router.push('/rules');
  } else {
    rulesExpanded.value = !rulesExpanded.value;
  }
}

async function selectLayer(l: LayerKey) {
  if (rules.ruleFormDirty) {
    try { await ElMessageBox.confirm(t('rules.confirm-unsaved'), { type: 'warning' }); }
    catch { return; }
    rules.resetDirty();
  }
  rulesExpanded.value = true;
  rules.setLayer(l);
  feedback.clear();
  if (route.path !== '/rules') router.push('/rules');
}

async function loadTenants() {
  try {
    const data = await adminRoot<any[]>('/tenants');
    tenants.value = (data || []).map((x: any) => ({
      id: field(x, 'tenant_id', 'tenantId') || '',
      name: field(x, 'name') || ''
    }));
    if (tenants.value.length && !tenants.value.some(x => x.id === tenantModel.value)) {
      session.setTenant(tenants.value[0].id);
    }
  } catch (e: any) {
    feedback.log(e.message, 'err');
  }
}

function onTenantChange(v: string) {
  session.setTenant(v);
}
function toggleLang() {
  const next = locale.value === 'zh' ? 'en' : 'zh';
  locale.value = next;
  session.setLocale(next as any);
}

onMounted(loadTenants);
</script>
