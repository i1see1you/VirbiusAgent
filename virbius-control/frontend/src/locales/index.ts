import { createI18n } from 'vue-i18n';
import zh from './zh';
import en from './en';

function detectLocale(): 'zh' | 'en' {
  try {
    const saved = localStorage.getItem('virbius.i18n.locale');
    if (saved === 'zh' || saved === 'en') return saved;
  } catch { /* ignore */ }
  const params = new URLSearchParams(window.location.search);
  const langParam = params.get('lang');
  if (langParam && langParam.startsWith('zh')) return 'zh';
  if (langParam === 'en') return 'en';
  const navLang = (navigator.language || '').toLowerCase();
  return navLang.startsWith('zh') ? 'zh' : 'en';
}

const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'en',
  messages: { zh, en }
});

export default i18n;
