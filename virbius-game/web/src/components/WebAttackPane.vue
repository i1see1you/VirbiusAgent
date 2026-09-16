<script setup>
import { computed } from "vue";
import hero from "../assets/trip/hero.png";
import gorge from "../assets/trip/gorge.png";
import erhai from "../assets/trip/erhai.png";
import yulong from "../assets/trip/yulong.png";

const props = defineProps({
  host: { type: String, default: "dianxi-hike.cn" },
  title: { type: String, default: "滇西徒步" },
  kicker: { type: String, default: "滇西北户外与徒步攻略" },
  sending: { type: Boolean, default: false },
  error: { type: String, default: "" },
});

const emit = defineEmits(["initiate"]);
const attack = defineModel({ type: String, default: "" });
const MAX = 10000;
const canSend = computed(() => attack.value.trim().length > 0 && !props.sending);
const countLabel = computed(() => `${attack.value.length.toLocaleString("en-US")} / ${MAX.toLocaleString("en-US")}`);
const spots = [
  { src: gorge, name: "虎跳峡" },
  { src: erhai, name: "洱海" },
  { src: yulong, name: "玉龙雪山" },
];

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
      <div class="web-scroll">
        <div class="web-chrome" aria-hidden="true">
          <i></i><i></i><i></i>
          <span>{{ host }}</span>
        </div>
        <article class="web-page">
          <nav class="web-nav" aria-hidden="true">
            <strong>{{ title }}</strong>
            <span>首页</span>
            <span>目的地</span>
            <span>图库</span>
            <span>行程</span>
          </nav>
          <header class="web-hero">
            <img :src="hero" alt="梅里雪山" />
            <div class="web-hero-copy">
              <h3>把山河写进一场难忘的徒步</h3>
              <p>
                <span class="web-cta">开始规划</span>
                {{ kicker }}
              </p>
            </div>
          </header>
          <section class="web-intro">
            <p class="web-by">{{ title }}</p>
            <p>
              横断山脉与茶马古道交汇处。虎跳峡、玉龙雪山、梅里外转，七天就能走完昆明到香格里拉这条人人都听过的线。
            </p>
          </section>
          <ul class="web-stats">
            <li><b>7</b> 日经典线</li>
            <li><b>3</b> 座古城</li>
            <li><b>3200m</b> 香格里拉</li>
          </ul>
          <ul class="web-cards">
            <li v-for="spot in spots" :key="spot.name">
              <img :src="spot.src" :alt="spot.name" />
              <span>{{ spot.name }}</span>
            </li>
          </ul>
          <p class="web-content-label">精选攻略</p>
          <label class="sr" for="web-attack">攻击文本</label>
          <div class="mcp-slot-wrap">
            <textarea
              id="web-attack"
              class="web-slot"
              v-model="attack"
              maxlength="10000"
              placeholder="在此插入攻击…"
              :disabled="sending"
              spellcheck="false"
            ></textarea>
            <span class="mcp-count">{{ countLabel }}</span>
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
