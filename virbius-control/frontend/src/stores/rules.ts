import { defineStore } from 'pinia';
import { ref } from 'vue';

export type LayerKey = 'cloud' | 'gateway' | 'edge' | 'kernel';

export const LAYER_RUNTIMES: Record<LayerKey, string[]> = {
  cloud: ['prompt', 'groovy'],
  gateway: ['lua'],
  edge: ['lua-dsl', 'dlp-dsl'],
  kernel: ['falco', 'landlock', 'gvisor']
};

export const FLOW_STEPS = ['draft', 'dry_run', 'canary', 'full'];

export const useRulesStore = defineStore('rules', () => {
  const currentLayer = ref<LayerKey>('cloud');
  const contextVars = ref<any[]>([]); // logical vars from bindings
  const ruleFormDirty = ref(false);

  function setLayer(l: LayerKey) {
    currentLayer.value = l;
  }
  function markDirty() { ruleFormDirty.value = true; }
  function resetDirty() { ruleFormDirty.value = false; }

  return { currentLayer, contextVars, ruleFormDirty, setLayer, markDirty, resetDirty };
});
