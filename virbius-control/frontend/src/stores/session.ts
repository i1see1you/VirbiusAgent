import { defineStore } from 'pinia';
import { ref, watch } from 'vue';
import { configureApi } from '@/api/client';

const LS = {
  apiKey: 'virbius.ops.apiKey',
  locale: 'virbius.i18n.locale',
  sidebar: 'virbius.ops.sidebarCollapsed'
};

function read(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function write(key: string, val: string) {
  try { localStorage.setItem(key, val); } catch { /* ignore */ }
}

export const useSessionStore = defineStore('session', () => {
  const tenant = ref('default');
  const apiKey = ref(read(LS.apiKey) || '');
  const bundleId = ref('poc-default');
  const bundleVer = ref('0.1.0');
  const sidebarCollapsed = ref(read(LS.sidebar) === '1');
  const locale = ref<'zh' | 'en'>((read(LS.locale) as any) || 'zh');

  function syncApi() {
    configureApi({ tenant: tenant.value, apiKey: apiKey.value });
  }

  function setTenant(t: string) {
    tenant.value = t || 'default';
    syncApi();
  }
  function setApiKey(k: string) {
    apiKey.value = k;
    write(LS.apiKey, k);
    syncApi();
  }
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value;
    write(LS.sidebar, sidebarCollapsed.value ? '1' : '0');
  }
  function setLocale(l: 'zh' | 'en') {
    locale.value = l;
    write(LS.locale, l);
  }

  syncApi();
  watch(tenant, syncApi);

  return { tenant, apiKey, bundleId, bundleVer, sidebarCollapsed, locale,
    setTenant, setApiKey, toggleSidebar, setLocale, syncApi };
});
