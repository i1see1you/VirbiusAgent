<script setup>
import { computed } from "vue";

const props = defineProps({
  score: { type: Number, default: null },
  pass: { type: Number, default: 75 },
});

const shown = computed(() => (props.score == null ? "—" : String(props.score)));
const pct = computed(() => (props.score == null ? 0 : Math.min(100, props.score)));
const dash = computed(() => 2 * Math.PI * 42);
const offset = computed(() => dash.value * (1 - pct.value / 100));
const ok = computed(() => props.score != null && props.score >= props.pass);
</script>

<template>
  <div class="ring" :class="{ ok }">
    <svg viewBox="0 0 100 100" aria-hidden="true">
      <circle class="track" cx="50" cy="50" r="42" />
      <circle class="fill" cx="50" cy="50" r="42" :stroke-dasharray="dash" :stroke-dashoffset="offset" />
    </svg>
    <div class="num">{{ shown }}</div>
    <p>{{ score == null ? `目标 ${pass}` : ok ? "已过关" : `目标 ${pass}` }}</p>
  </div>
</template>

<style scoped>
.ring {
  position: relative;
  width: 148px;
  height: 148px;
  margin: 0 auto;
}

svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

circle {
  fill: none;
  stroke-width: 8;
}

.track {
  stroke: rgba(255, 255, 255, 0.08);
}

.fill {
  stroke: #38bdf8;
  stroke-linecap: round;
  transition: stroke-dashoffset 500ms ease, stroke 200ms ease;
}

.ok .fill {
  stroke: #f5c15d;
}

.num {
  position: absolute;
  inset: 0 0 22px;
  display: grid;
  place-items: center;
  font-family: Syne, sans-serif;
  font-size: 2.2rem;
  font-weight: 800;
  color: #f8fafc;
}

p {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 18px;
  margin: 0;
  text-align: center;
  font-size: 0.72rem;
  letter-spacing: 0.08em;
  color: #94a3b8;
}

.ok p {
  color: #f5c15d;
}
</style>
