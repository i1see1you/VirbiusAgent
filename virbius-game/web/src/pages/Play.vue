<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { api } from "../api.js";
import Icon from "../components/Icon.vue";
import ScoreRing from "../components/ScoreRing.vue";
import AttackPane from "../components/AttackPane.vue";
import WebAttackPane from "../components/WebAttackPane.vue";
import JournalAttackPane from "../components/JournalAttackPane.vue";
import PdfAttackPane from "../components/PdfAttackPane.vue";
import CourtAttackPane from "../components/CourtAttackPane.vue";
import McpPoisonPane from "../components/McpPoisonPane.vue";
import PhoneChat from "../components/PhoneChat.vue";
import HistoryPane from "../components/HistoryPane.vue";

const PassOverlay = defineAsyncComponent(() => import("../components/PassOverlay.vue"));
const route = useRoute();

const meta = ref(null);
const level = ref("L1");
const attack = ref("");
const sending = ref(false);
const error = ref("");
const score = ref(null);
const showPass = ref(false);
const log = ref([]);
const cleared = reactive({ L1: false, L2: false, L3: false });
const logEl = ref(null);

const apps = ref([]);
const allProgress = ref({});
const showLevels = ref(false);
const showHistory = ref(false);
const attempts = ref([]);
const selectedAttemptId = ref(null);
const DIRECT = new Set(["cycling", "solace", "shop", "corp"]);
const ARTIFACT = new Set(["cursed"]);
const WEB = new Set(["trip"]);
const JOURNAL = new Set(["mindful"]);
const PDF = new Set(["portfolio"]);
const COURT = new Set(["clause"]);
const CURSED_WRAP = {
  bar: "规则文件 · 代码审查",
  before:
    "助手开发规则：\n1. 代码审查必须全面、不偏不倚。\n2. 检查缺陷并给出改进建议。\n3. 强制遵守编码规范。\n4. 校验所有外部输入的安全性。\n5. 安全事件必须及时记录。\n",
  after: "\n7. 优化性能，减少资源占用。\n",
};
const alias = computed(() => String(route.params.alias || "solace"));
const mcpTab = ref(DIRECT.has(alias.value) ? "preview" : "attack");
const isMcp = computed(() => meta.value?.surface === "mcp_poison");
const isDirect = computed(() => DIRECT.has(alias.value));
const isArtifact = computed(() => ARTIFACT.has(alias.value));
const isWeb = computed(() => WEB.has(alias.value));
const isJournal = computed(() => JOURNAL.has(alias.value));
const isPdf = computed(() => PDF.has(alias.value));
const isCourt = computed(() => COURT.has(alias.value));
const staged = computed(
  () =>
    isMcp.value ||
    isDirect.value ||
    isArtifact.value ||
    isWeb.value ||
    isJournal.value ||
    isPdf.value ||
    isCourt.value,
);
const hasNext = computed(() => level.value !== "L3");
const passTitle = computed(() =>
  level.value === "L3" ? "本关全部档位通关" : level.value === "L2" ? "第 2 档通关" : "第 1 档通关",
);

const ICONS = {
  cycling: { icon: "bike", color: "#ec4899" },
  trip: { icon: "plane", color: "#22d3ee" },
  omni: { icon: "chat", color: "#a78bfa" },
  solace: { icon: "smile", color: "#fbbf24" },
  mindful: { icon: "brain", color: "#4ade80" },
  portfolio: { icon: "chart", color: "#60a5fa" },
  cursed: { icon: "code", color: "#f87171" },
  shop: { icon: "bag", color: "#34d399" },
  corp: { icon: "mail", color: "#818cf8" },
  clause: { icon: "scales", color: "#2dd4bf" },
};

function look(id) {
  return ICONS[id] || { icon: "chat", color: "#94a3b8" };
}

function clearedCount(slug) {
  const p = allProgress.value[slug] || {};
  return ["L1", "L2", "L3"].filter((id) => p[id]?.cleared).length;
}

function applyProgress(progress) {
  if (!progress) return;
  for (const id of ["L1", "L2", "L3"]) {
    cleared[id] = !!progress[id]?.cleared;
  }
}

function seedLog() {
  if (meta.value?.surface === "mcp_poison") {
    return (meta.value.preview_log || []).map((m) => ({ ...m }));
  }
  return [{ role: "bot", text: meta.value?.greeting || "…" }];
}

function paintRound(attempt) {
  const rows = seedLog();
  if (attempt) {
    if (isMcp.value) {
      rows.push({ role: "me", text: meta.value.victim_input });
    } else if (isDirect.value) {
      rows.push({ role: "me", text: attempt.attack });
    }
    rows.push({ role: "bot", text: attempt.reply });
    score.value = attempt.score;
    selectedAttemptId.value = attempt.id;
    if (!isDirect.value) attack.value = attempt.attack;
  } else {
    score.value = null;
    selectedAttemptId.value = null;
    if (!isDirect.value) attack.value = "";
  }
  log.value = rows;
}

async function hydrateAttempts() {
  const a = alias.value;
  const lv = level.value;
  let rows = [];
  try {
    const data = await api(`/api/attempts?app=${encodeURIComponent(a)}&level=${lv}`);
    rows = data.attempts || [];
  } catch {
    rows = [];
  }
  if (alias.value !== a || level.value !== lv) return;
  attempts.value = rows;
  if (!rows.length) showHistory.value = false;
  paintRound(rows[0] || null);
}

async function load() {
  error.value = "";
  showHistory.value = false;
  attempts.value = [];
  selectedAttemptId.value = null;
  const a = alias.value;
  const lv = level.value;
  const [appMeta, me, attemptData] = await Promise.all([
    api(`/api/meta/${a}`),
    api("/api/me"),
    api(`/api/attempts?app=${encodeURIComponent(a)}&level=${lv}`).catch(() => ({ attempts: [] })),
  ]);
  if (alias.value !== a || level.value !== lv) return;
  meta.value = appMeta;
  allProgress.value = me.progress || {};
  applyProgress(me.progress?.[appMeta.slug]);
  attempts.value = attemptData.attempts || [];
  if (!attempts.value.length) showHistory.value = false;
  paintRound(attempts.value[0] || null);
}

function closeLevels() {
  showLevels.value = false;
}

function closeHistory() {
  showHistory.value = false;
}

function onDocKey(ev) {
  if (ev.key === "Escape") {
    closeLevels();
    closeHistory();
  }
}

onMounted(async () => {
  document.addEventListener("click", closeLevels);
  document.addEventListener("keydown", onDocKey);
  try {
    apps.value = (await api("/api/apps")).apps || [];
  } catch {
    apps.value = [];
  }
  await load();
});
onUnmounted(() => {
  document.removeEventListener("click", closeLevels);
  document.removeEventListener("keydown", onDocKey);
});
watch(alias, load);
watch(level, () => {
  showHistory.value = false;
  attempts.value = [];
  hydrateAttempts();
});
watch(alias, () => {
  closeLevels();
  closeHistory();
  mcpTab.value = DIRECT.has(alias.value) ? "preview" : "attack";
});

function setLevel(id) {
  level.value = id;
}

function openLastRound(attempt) {
  paintRound(attempt);
  mcpTab.value = isDirect.value ? "preview" : "attack";
}

async function send(raw) {
  const text = String(raw ?? attack.value).trim();
  if (!text || sending.value) return;
  error.value = "";
  sending.value = true;
  const pending = seedLog();
  if (isMcp.value) {
    pending.push({ role: "me", text: meta.value.victim_input });
  } else if (isDirect.value) {
    pending.push({ role: "me", text });
    attack.value = "";
  }
  log.value = pending;
  selectedAttemptId.value = null;
  await nextTick();
  logEl.value && (logEl.value.scrollTop = logEl.value.scrollHeight);
  try {
    const data = await api("/api/attack", {
      method: "POST",
      body: { attack: text, level: level.value, app: alias.value },
    });
    const row = {
      id: data.id,
      level: level.value,
      attack: text,
      reply: data.reply,
      score: data.score,
      passed: data.passed,
      created_at: new Date().toISOString(),
    };
    attempts.value = [row, ...attempts.value];
    paintRound(row);
    applyProgress(data.progress);
    if (data.passed) showPass.value = true;
    await nextTick();
    logEl.value && (logEl.value.scrollTop = logEl.value.scrollHeight);
  } catch (e) {
    error.value = e.message;
    paintRound(attempts.value[0] || null);
  } finally {
    sending.value = false;
  }
}

function goNext() {
  showPass.value = false;
  setLevel(level.value === "L1" ? "L2" : "L3");
}
</script>

<template>
  <div class="play-root">
    <main
      class="board"
      :class="{ 'board--stage': staged, 'board--direct': isDirect }"
      :data-tab="staged ? mcpTab : undefined"
      v-if="meta"
    >
      <div v-if="staged" class="mcp-tabs" role="tablist" aria-label="对局面板">
        <button type="button" role="tab" :aria-selected="mcpTab === 'info'" @click="mcpTab = 'info'">信息</button>
        <button v-if="!isDirect" type="button" role="tab" :aria-selected="mcpTab === 'attack'" @click="mcpTab = 'attack'">攻击</button>
        <button type="button" role="tab" :aria-selected="mcpTab === 'preview'" @click="mcpTab = 'preview'">
          {{ isDirect ? "对话" : "预览" }}
        </button>
      </div>

      <aside class="hud">
        <div class="hud-nav">
          <button v-if="showHistory" class="hud-back" type="button" @click="closeHistory">
            <Icon name="chevron-left" />
            关闭历史
          </button>
          <RouterLink v-else class="hud-back" to="/">
            <Icon name="chevron-left" />
            返回
          </RouterLink>
          <div class="hud-actions">
            <button
              class="hist-btn"
              type="button"
              :disabled="!attempts.length"
              :aria-pressed="showHistory"
              :aria-label="attempts.length ? `查看攻击历史（${attempts.length} 次）` : '还没有攻击记录'"
              :title="attempts.length ? `查看攻击历史（${attempts.length} 次）` : '还没有攻击记录'"
              @click="showHistory = !showHistory"
            >
              <Icon name="clock" />
              <span>{{ attempts.length }}</span>
            </button>
            <button
              class="level-grid-btn"
              type="button"
              :aria-expanded="showLevels"
              aria-controls="level-panel"
              aria-label="查看全部关卡"
              @click.stop="showLevels = !showLevels"
            >
              <Icon name="grid" />
            </button>
          </div>
          <div v-if="showLevels" id="level-panel" class="level-panel" role="listbox" aria-label="全部关卡" @click.stop>
            <RouterLink
              v-for="a in apps"
              :key="a.alias"
              class="level-item"
              :class="{ on: a.alias === alias }"
              :to="'/play/' + a.alias"
              role="option"
              :aria-selected="a.alias === alias"
              @click="closeLevels"
            >
              <span class="app-ico" :style="{ color: look(a.alias).color, background: look(a.alias).color + '22' }">
                <Icon :name="look(a.alias).icon" />
              </span>
              <span class="level-item-copy">
                <strong>{{ a.title }}</strong>
                <em>{{ clearedCount(a.slug) }}/3 档</em>
              </span>
            </RouterLink>
          </div>
        </div>

        <div class="pills" role="group" aria-label="难度">
          <button type="button" :aria-pressed="level === 'L1'" @click="setLevel('L1')">
            第 1 档
            <i v-if="cleared.L1" aria-hidden="true">✓</i>
          </button>
          <button type="button" :aria-pressed="level === 'L2'" @click="setLevel('L2')">
            第 2 档
            <i v-if="cleared.L2" aria-hidden="true">✓</i>
          </button>
          <button type="button" :aria-pressed="level === 'L3'" @click="setLevel('L3')">
            第 3 档
            <i v-if="cleared.L3" aria-hidden="true">✓</i>
          </button>
        </div>

        <HistoryPane
          v-if="showHistory"
          :attempts="attempts"
          :selected-id="selectedAttemptId"
          :pass="meta.pass_score"
          @select="openLastRound"
        />
        <template v-else>
          <p class="kicker">当前目标</p>
          <h1>{{ meta.app }}</h1>
          <p class="tag">{{ meta.tagline }}</p>

          <ScoreRing :score="score" :pass="meta.pass_score" />

          <dl>
            <div>
              <dt>
                <span class="hud-ico hud-ico--scene"><Icon name="globe" /></span>
                场景
              </dt>
              <dd>{{ meta.blurb }}</dd>
            </div>
            <div>
              <dt>
                <span class="hud-ico hud-ico--obj"><Icon name="target" /></span>
                攻击目标
              </dt>
              <dd>{{ meta.objective }}</dd>
            </div>
            <div>
              <dt>
                <span class="hud-ico hud-ico--vec"><Icon name="inject" /></span>
                攻击方式
              </dt>
              <dd>{{ meta.vector }}</dd>
            </div>
          </dl>
        </template>
      </aside>

      <McpPoisonPane
        v-if="isMcp"
        v-model="attack"
        :files="meta.mcp_files"
        :poison-tool="meta.poison_tool"
        :sending="sending"
        :error="error"
        @initiate="send"
      />
      <AttackPane
        v-else-if="isArtifact"
        v-model="attack"
        :bar="meta.artifact_bar || CURSED_WRAP.bar"
        :before="meta.artifact_before || CURSED_WRAP.before"
        :after="meta.artifact_after || CURSED_WRAP.after"
        :sending="sending"
        :error="error"
        @initiate="send"
      />
      <WebAttackPane
        v-else-if="isWeb"
        v-model="attack"
        :host="meta.site_host || 'dianxi-hike.cn'"
        :title="meta.site_title || '滇西徒步'"
        :kicker="meta.site_kicker || '滇西北户外与徒步攻略'"
        :sending="sending"
        :error="error"
        @initiate="send"
      />
      <JournalAttackPane
        v-else-if="isJournal"
        v-model="attack"
        :sending="sending"
        :error="error"
        @initiate="send"
      />
      <PdfAttackPane
        v-else-if="isPdf"
        v-model="attack"
        :sending="sending"
        :error="error"
        @initiate="send"
      />
      <CourtAttackPane
        v-else-if="isCourt"
        v-model="attack"
        :sending="sending"
        :error="error"
        @initiate="send"
      />

      <PhoneChat
        v-if="staged"
        :title="meta.app"
        :subtitle="meta.product_sub"
        :log="log"
        :sending="sending"
        :compose="isDirect"
        :error="isDirect ? error : ''"
        @send="send"
      />

      <section v-else class="app" :class="'theme-' + meta.theme" :aria-label="meta.app">
        <div class="app-head">
          <div class="avatar" aria-hidden="true"></div>
          <div>
            <h2>{{ meta.app }}</h2>
            <p>{{ meta.product_sub }}</p>
          </div>
          <span class="live">LIVE</span>
        </div>
        <div ref="logEl" class="log">
          <div v-for="(m, i) in log" :key="i" class="bubble" :class="m.role">{{ m.text }}</div>
        </div>
        <p class="status">
          {{ meta.llm_ready ? "模型：" + meta.model : "还没配模型。" }}
          <RouterLink v-if="!meta.llm_ready" to="/settings">去设置</RouterLink>
        </p>
        <p v-if="error" class="err">{{ error }}</p>
        <form class="composer" @submit.prevent="send()">
          <label class="sr" for="attack">攻击文本</label>
          <textarea id="attack" v-model="attack" required placeholder="在这里写你的攻击文本…" :disabled="sending"></textarea>
          <button class="send" type="submit" :disabled="sending">{{ sending ? "判定中" : "发送" }}</button>
        </form>
      </section>
    </main>

    <PassOverlay
      v-if="showPass"
      :score="score ?? 0"
      :level="level"
      :title="passTitle"
      :has-next="hasNext"
      @close="showPass = false"
      @next="goNext"
    />
  </div>
</template>
