<script setup>
import { onMounted, ref, watch } from "vue";
import { api } from "../api.js";

const rows = ref([]);
const apps = ref([]);
const scope = ref("all");
const error = ref("");

async function load() {
  error.value = "";
  try {
    const q = scope.value === "all" ? "" : `?app=${scope.value}`;
    const data = await api("/api/leaderboard" + q);
    rows.value = data.rows;
  } catch (e) {
    error.value = e.message;
  }
}

onMounted(async () => {
  try {
    apps.value = (await api("/api/apps")).apps;
  } catch (e) {
    error.value = e.message;
  }
  await load();
});
watch(scope, load);
</script>

<template>
  <main class="page">
    <header class="page-head">
      <p class="kicker">全部关卡</p>
      <h1>排行榜</h1>
      <p class="lead">按各档最高分相加排序。同分少尝试的在前。</p>
    </header>
    <label class="scope">
      关卡
      <select v-model="scope">
        <option value="all">全部合计</option>
        <option v-for="a in apps" :key="a.slug" :value="a.alias">{{ a.title }}</option>
      </select>
    </label>
    <p v-if="error" class="err">{{ error }}</p>
    <div v-else-if="!rows.length" class="empty">还没有成绩。注册之后去打一关。</div>
    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>名次</th>
            <th>玩家</th>
            <th>总分</th>
            <th>第 1 档</th>
            <th>第 2 档</th>
            <th>第 3 档</th>
            <th>尝试</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.username">
            <td>{{ r.rank }}</td>
            <td>{{ r.username }}</td>
            <td>{{ r.total }}</td>
            <td>{{ r.l1 }}</td>
            <td>{{ r.l2 }}</td>
            <td>{{ r.l3 }}</td>
            <td>{{ r.tries }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </main>
</template>
