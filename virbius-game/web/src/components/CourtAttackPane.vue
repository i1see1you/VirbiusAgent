<script setup>
import { computed } from "vue";

const props = defineProps({
  sending: { type: Boolean, default: false },
  error: { type: String, default: "" },
});

const emit = defineEmits(["initiate"]);
const attack = defineModel({ type: String, default: "" });
const MAX = 10000;
const canSend = computed(() => attack.value.trim().length > 0 && !props.sending);
const countLabel = computed(() => `${attack.value.length.toLocaleString("en-US")} / ${MAX.toLocaleString("en-US")}`);

function submit() {
  if (!canSend.value) return;
  emit("initiate", attack.value.trim());
}
</script>

<template>
  <section class="attack-pane" aria-labelledby="attack-title">
    <header class="attack-head">
      <h2 id="attack-title">添加攻击</h2>
      <p>这是用来攻击该应用的工具。</p>
    </header>

    <div class="mcp-shell">
      <div class="pdf-scroll">
        <article class="pdf-page">
          <header class="pdf-banner">公开立案材料</header>
          <div class="pdf-body">
            <p class="court-case">State v. Roberts, 2023 WL 4829183</p>
            <label class="sr" for="court-attack">攻击文本</label>
            <div class="mcp-slot-wrap">
              <textarea
                id="court-attack"
                class="pdf-slot court-slot"
                v-model="attack"
                maxlength="10000"
                placeholder="在此插入攻击…"
                :disabled="sending"
                spellcheck="false"
              ></textarea>
              <span class="mcp-count">{{ countLabel }}</span>
            </div>
          </div>
        </article>
      </div>

      <div class="mcp-foot">
        <p v-if="error" class="err attack-err">{{ error }}</p>
        <button class="initiate" type="button" :disabled="!canSend" @click="submit">
          {{ sending ? "判定中" : "发起攻击" }}
        </button>
      </div>
    </div>
  </section>
</template>
