<script setup>
import { computed } from "vue";
import AttackPane from "./AttackPane.vue";

const props = defineProps({
  files: { type: Array, default: () => [] },
  poisonTool: { type: String, required: true },
  sending: { type: Boolean, default: false },
  error: { type: String, default: "" },
  mcpServer: { type: String, default: "weather" },
});

const emit = defineEmits(["initiate"]);
const attack = defineModel({ type: String, default: "" });

const poisonFile = computed(() => props.files.find((f) => f.name === props.poisonTool) || props.files[0]);
const bar = computed(() => `工具 ${poisonFile.value?.name || props.poisonTool} · ${props.mcpServer} MCP`);

function dump(spec) {
  if (!spec) return "";
  const ordered = {
    name: spec.name,
    description: spec.description,
    inputSchema: spec.inputSchema,
  };
  if (spec.annotations) ordered.annotations = spec.annotations;
  return JSON.stringify(ordered, null, 2);
}

function splitDesc(spec) {
  const dumped = dump(spec);
  const needle = '"description": "';
  const i = dumped.indexOf(needle);
  if (i < 0) {
    return { before: dumped, after: "" };
  }
  const start = i + needle.length;
  const end = dumped.indexOf('"', start);
  return {
    before: dumped.slice(0, start),
    after: dumped.slice(end),
  };
}

const slot = computed(() => splitDesc(poisonFile.value));
</script>

<template>
  <AttackPane
    v-model="attack"
    :bar="bar"
    :before="slot.before"
    :after="slot.after"
    :sending="sending"
    :error="error"
    @initiate="emit('initiate', $event)"
  />
</template>
