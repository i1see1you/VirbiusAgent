<template>
  <div class="script-editor" ref="editorHost" />
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, shallowRef, nextTick, computed } from 'vue';
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, highlightSpecialChars, crosshairCursor, dropCursor, rectangularSelection } from '@codemirror/view';
import { EditorState, Compartment, type Extension } from '@codemirror/state';
import { basicSetup } from 'codemirror';
import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { StreamLanguage } from '@codemirror/language';
import { lua } from '@codemirror/legacy-modes/mode/lua';
import { oneDark } from '@codemirror/theme-one-dark';
import { autocompletion, type CompletionContext, type CompletionSource, type CompletionResult } from '@codemirror/autocomplete';
import { linter, type Diagnostic } from '@codemirror/lint';
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { searchKeymap, highlightSelectionMatches } from '@codemirror/search';
import { indentOnInput, bracketMatching, foldGutter, foldKeymap, syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language';
import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete';

const props = withDefaults(defineProps<{
  modelValue: string;
  language?: 'groovy' | 'lua' | 'json' | 'prompt';
  readOnly?: boolean;
  completionSources?: CompletionSource[];
  lintFn?: ((view: EditorView) => Diagnostic[]) | null;
  minHeight?: string;
  maxHeight?: string;
}>(), {
  language: 'groovy',
  readOnly: false,
  completionSources: () => [],
  lintFn: null,
  minHeight: '200px',
  maxHeight: '600px'
});

const emit = defineEmits<{
  'update:modelValue': [value: string];
  'save': [];
}>();

const editorHost = ref<HTMLElement>();
const viewRef = shallowRef<EditorView | null>(null);
const langCompartment = new Compartment();
const completionCompartment = new Compartment();
const lintCompartment = new Compartment();
const readOnlyCompartment = new Compartment();

function getLangExtension(lang: string): Extension {
  switch (lang) {
    case 'groovy':
      return javascript();
    case 'lua':
      return StreamLanguage.define(lua);
    case 'json':
      return json();
    case 'prompt':
      return [];
    default:
      return [];
  }
}

function buildCompletionExt(sources: CompletionSource[]): Extension {
  if (!sources.length) return [];
  return autocompletion({
    override: sources,
    activateOnTyping: true,
    maxRenderedOptions: 50,
    optionClass: (option) => `cm-completion-${option.type || 'default'}`
  });
}

function buildLintExt(fn: ((view: EditorView) => Diagnostic[]) | null): Extension {
  if (!fn) return [];
  return linter(fn, { delay: 600 });
}

function buildReadOnlyExt(ro: boolean): Extension {
  return EditorState.readOnly.of(ro);
}

function saveKeymap(): Extension {
  return keymap.of([{
    key: 'Mod-s',
    run: () => { emit('save'); return true; },
    preventDefault: true
  }]);
}

const baseTheme = EditorView.theme({
  '&': {
    minHeight: props.minHeight,
    maxHeight: props.maxHeight,
    fontSize: '13px',
    border: '1px solid #d1d5db',
    borderRadius: '6px',
    overflow: 'hidden'
  },
  '.cm-content': {
    fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace',
    caretColor: '#e2e8f0'
  },
  '.cm-gutters': {
    backgroundColor: '#1e293b',
    color: '#64748b',
    border: 'none',
    minWidth: '40px'
  },
  '.cm-activeLineGutter': {
    backgroundColor: '#334155'
  },
  '.cm-activeLine': {
    backgroundColor: 'rgba(51,65,85,0.3)'
  },
  '.cm-selectionBackground': {
    backgroundColor: '#264f78 !important'
  },
  '&.cm-focused .cm-selectionBackground': {
    backgroundColor: '#264f78 !important'
  },
  '.cm-cursor': {
    borderLeftColor: '#e2e8f0'
  },
  '.cm-tooltip': {
    backgroundColor: '#1e293b',
    color: '#e2e8f0',
    border: '1px solid #475569',
    borderRadius: '4px'
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': {
    backgroundColor: '#334155'
  },
  '.cm-diagnostic-error': {
    borderLeft: '3px solid #ef4444',
    backgroundColor: 'rgba(239,68,68,0.08)'
  },
  '.cm-diagnostic-warning': {
    borderLeft: '3px solid #f59e0b',
    backgroundColor: 'rgba(245,158,11,0.08)'
  },
  '.cm-lintRange-error': {
    background: 'rgba(239,68,68,0.12) !important'
  },
  '.cm-lintRange-warning': {
    background: 'rgba(245,158,11,0.12) !important'
  }
});

const baseExtensions: Extension = [
  basicSetup,
  langCompartment.of(getLangExtension(props.language)),
  completionCompartment.of(buildCompletionExt(props.completionSources)),
  lintCompartment.of(buildLintExt(props.lintFn)),
  readOnlyCompartment.of(buildReadOnlyExt(props.readOnly)),
  oneDark,
  baseTheme,
  saveKeymap(),
  EditorView.updateListener.of((update) => {
    if (update.docChanged) {
      const val = update.state.doc.toString();
      emit('update:modelValue', val);
    }
  })
];

onMounted(() => {
  if (!editorHost.value) return;
  const state = EditorState.create({
    doc: props.modelValue || '',
    extensions: baseExtensions
  });
  const view = new EditorView({
    state,
    parent: editorHost.value
  });
  viewRef.value = view;
});

onBeforeUnmount(() => {
  viewRef.value?.destroy();
  viewRef.value = null;
});

watch(() => props.modelValue, (val) => {
  const view = viewRef.value;
  if (!view) return;
  const current = view.state.doc.toString();
  if (val !== current) {
    view.dispatch({
      changes: { from: 0, to: current.length, insert: val || '' }
    });
  }
});

watch(() => props.language, (lang) => {
  viewRef.value?.dispatch({
    effects: langCompartment.reconfigure(getLangExtension(lang))
  });
});

watch(() => props.completionSources, (sources) => {
  viewRef.value?.dispatch({
    effects: completionCompartment.reconfigure(buildCompletionExt(sources))
  });
}, { deep: true });

watch(() => props.lintFn, (fn) => {
  viewRef.value?.dispatch({
    effects: lintCompartment.reconfigure(buildLintExt(fn))
  });
});

watch(() => props.readOnly, (ro) => {
  viewRef.value?.dispatch({
    effects: readOnlyCompartment.reconfigure(buildReadOnlyExt(ro))
  });
});

defineExpose({
  insertAtCursor(text: string) {
    const view = viewRef.value;
    if (!view) return;
    const from = view.state.selection.main.from;
    const to = view.state.selection.main.to;
    view.dispatch({ changes: { from, to, insert: text } });
    view.focus();
  },
  focus() {
    viewRef.value?.focus();
  },
  refresh() {
    viewRef.value?.requestMeasure();
  },
  getView(): EditorView | null {
    return viewRef.value;
  }
});
</script>

<style scoped>
.script-editor {
  position: relative;
}
.script-editor :deep(.cm-editor) {
  outline: none;
}
.script-editor :deep(.cm-editor.cm-focused) {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.2);
}
.script-editor :deep(.cm-scroller) {
  overflow: auto;
}
</style>
