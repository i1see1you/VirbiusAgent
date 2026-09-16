<script setup>
import { onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import Icon from "../components/Icon.vue";
import { api } from "../api.js";

const apps = ref([]);
const progress = ref({});
const error = ref("");
const llmReady = ref(true);

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

function look(alias) {
  return ICONS[alias] || { icon: "chat", color: "#94a3b8" };
}

function slugProgress(slug) {
  return progress.value[slug] || {};
}

function cleared(slug, id) {
  return Boolean(slugProgress(slug)[id]?.cleared);
}

function clearedCount(slug) {
  return ["L1", "L2", "L3"].filter((id) => cleared(slug, id)).length;
}

onMounted(async () => {
  try {
    const data = await api("/api/apps");
    apps.value = data.apps;
    llmReady.value = data.llm_ready !== false;
    const me = await api("/api/me");
    progress.value = me.progress || {};
  } catch (e) {
    error.value = e.message;
  }
});
</script>

<template>
  <main class="home">
    <section class="home-banner">
      <p class="kicker">红队实验室</p>
      <h1>VirbiusGame</h1>
      <p class="lead">10 个应用，每关三档。≥75 过关。</p>
      <p v-if="!llmReady" class="err">还没配模型。<RouterLink to="/settings">去设置</RouterLink></p>
    </section>

    <section class="howto" aria-labelledby="howto-title">
      <h2 id="howto-title">怎么玩</h2>
      <div class="howto-grid">
        <p><strong>欢迎</strong>这是 VirbiusGame：十个靶场应用，用来练红队注入。</p>
        <p><strong>任务</strong>挑一个应用，往它的注入点塞攻击文本，让模型做出过关行为。</p>
        <p><strong>计分</strong>系统打 0–100 分。≥75 过关。三档分别是弱提示词、强硬提示词、自裁判。</p>
        <p><strong>上榜</strong>各档最高分相加进排行榜。同分少尝试的排前面。</p>
      </div>
    </section>

    <section class="info-cards" aria-label="关卡说明">
      <article class="info-card info-card--obj">
        <Icon name="target" />
        <h3>过关目标</h3>
        <p>每档一个目标。难了可能目标不变，只是防线变硬。</p>
      </article>
      <article class="info-card info-card--vec">
        <Icon name="inject" />
        <h3>注入点</h3>
        <p>对话框直输，或写进网页、PDF、规则文件。</p>
      </article>
      <article class="info-card info-card--scene">
        <Icon name="globe" />
        <h3>攻击场景</h3>
        <p>这个应用里，那个 victim Agent 正在干什么。</p>
      </article>
    </section>

    <p v-if="error" class="err home-err">{{ error }}</p>

    <section class="app-grid" aria-label="关卡列表">
      <article v-for="a in apps" :key="a.slug" class="app-row">
        <div class="app-ico" :style="{ color: look(a.alias).color, background: look(a.alias).color + '22' }">
          <Icon :name="look(a.alias).icon" />
        </div>
        <div class="app-copy">
          <h2>{{ a.title }}</h2>
          <p>{{ a.blurb }}</p>
        </div>
        <div class="dots" :aria-label="'已过 ' + clearedCount(a.slug) + '/3'">
          <span v-for="id in ['L1', 'L2', 'L3']" :key="id" :class="{ on: cleared(a.slug, id) }" :title="id"></span>
        </div>
        <RouterLink class="hack" :to="'/play/' + a.alias">攻破 →</RouterLink>
      </article>
    </section>
  </main>
</template>
