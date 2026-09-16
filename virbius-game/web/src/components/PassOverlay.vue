<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { burst } from "../confetti.js";

const props = defineProps({
  score: { type: Number, required: true },
  level: { type: String, required: true },
  title: { type: String, default: "" },
  hasNext: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "next"]);

const canvas = ref(null);
const shown = ref(0);
const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
let stop = () => {};

const title = computed(
  () => props.title || (props.level === "L3" ? "本关全部档位通关" : props.level === "L2" ? "第 2 档通关" : "第 1 档通关"),
);

onMounted(() => {
  stop = burst(canvas.value, { reduced });
  const from = 0;
  const to = props.score;
  const t0 = performance.now();
  const dur = reduced ? 1 : 700;
  const step = (now) => {
    const t = Math.min(1, (now - t0) / dur);
    const ease = 1 - (1 - t) ** 3;
    shown.value = Math.round(from + (to - from) * ease);
    if (t < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
  document.getElementById("pass-continue")?.focus();
});

onUnmounted(() => stop());

watch(
  () => props.score,
  () => {},
);

function onKey(e) {
  if (e.key === "Escape") emit("close");
}
window.addEventListener("keydown", onKey);
onUnmounted(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div class="pass" role="dialog" aria-modal="true" aria-labelledby="pass-title" @click.self="emit('close')">
    <canvas ref="canvas" class="pass__fx" aria-hidden="true"></canvas>
    <div class="pass__card">
      <div class="pass__rays" aria-hidden="true"></div>
      <div class="pass__seal">通关</div>
      <p id="pass-title" class="pass__title">{{ title }}</p>
      <p class="pass__score"><span>{{ shown }}</span> / 100</p>
      <p class="pass__sub">目标 75 分已达成</p>
      <div class="pass__actions">
        <button v-if="hasNext" id="pass-continue" type="button" class="btn btn--gold" @click="emit('next')">
          挑战下一档
        </button>
        <button v-else id="pass-continue" type="button" class="btn btn--gold" @click="emit('close')">
          继续刷分
        </button>
        <button type="button" class="btn btn--ghost" @click="emit('close')">回到对局</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pass {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  background: rgba(4, 10, 22, 0.72);
  backdrop-filter: blur(10px);
}

.pass__fx {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.pass__card {
  position: relative;
  text-align: center;
  padding: 2.4rem 2.2rem 1.8rem;
  min-width: min(92vw, 380px);
}

.pass__rays {
  position: absolute;
  inset: -40px;
  background: repeating-conic-gradient(from 0deg, rgba(245, 193, 93, 0.14) 0 12deg, transparent 12deg 24deg);
  mask: radial-gradient(circle, black 28%, transparent 68%);
  animation: spin 18s linear infinite;
  z-index: 0;
}

.pass__seal,
.pass__title,
.pass__score,
.pass__sub,
.pass__actions {
  position: relative;
  z-index: 1;
}

.pass__seal {
  width: 112px;
  height: 112px;
  margin: 0 auto 1.1rem;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-family: "Noto Sans SC", sans-serif;
  font-weight: 700;
  font-size: 1.45rem;
  letter-spacing: 0.12em;
  color: #3b2504;
  background: radial-gradient(circle at 30% 25%, #ffe7a8, #f5c15d 42%, #c4891d);
  box-shadow: 0 12px 30px rgba(245, 193, 93, 0.35), inset 0 0 0 6px rgba(90, 50, 8, 0.18);
  transform: rotate(-8deg) scale(2.2);
  animation: stamp 560ms cubic-bezier(0.16, 1.2, 0.32, 1) forwards;
}

.pass__title {
  margin: 0;
  font-family: "Noto Sans SC", sans-serif;
  font-size: 1.8rem;
  font-weight: 700;
  color: #f8fafc;
}

.pass__score {
  margin: 0.35rem 0 0;
  font-family: Syne, sans-serif;
  font-size: 2.6rem;
  font-weight: 800;
  color: #f5c15d;
}

.pass__score span {
  font-variant-numeric: tabular-nums;
}

.pass__sub {
  margin: 0.2rem 0 1.4rem;
  color: #94a3b8;
}

.pass__actions {
  display: flex;
  gap: 0.6rem;
  justify-content: center;
  flex-wrap: wrap;
}

@keyframes stamp {
  to {
    transform: rotate(-8deg) scale(1);
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .pass__seal,
  .pass__rays {
    animation: none;
    transform: rotate(-8deg) scale(1);
  }
}
</style>
