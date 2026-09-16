<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink, RouterView, useRoute, useRouter } from "vue-router";
import Icon from "./components/Icon.vue";
import { api } from "./api.js";

const router = useRouter();
const route = useRoute();
const user = ref(null);
const sideOpen = ref(false);

const lobby = computed(() => route.name === "home" || route.name === "board" || route.name === "settings");
const hello = computed(() => (user.value ? user.value.username : ""));

async function refresh() {
  const me = await api("/api/me");
  user.value = me.user;
}

onMounted(refresh);
router.afterEach(() => {
  sideOpen.value = false;
  refresh();
});

async function logout() {
  await api("/api/logout", { method: "POST" });
  user.value = null;
  router.push("/");
}
</script>

<template>
  <div class="shell" :class="{ 'shell--lobby': lobby, 'shell--side-open': sideOpen }">
    <div v-if="lobby && sideOpen" class="side-mask" @click="sideOpen = false"></div>
    <aside v-if="lobby" class="side" id="site-nav">
      <RouterLink class="side-brand" to="/">VirbiusGame</RouterLink>
      <p class="side-kicker">游戏</p>
      <nav class="side-nav">
        <RouterLink to="/">
          <Icon name="flag" />
          关卡
        </RouterLink>
        <RouterLink to="/leaderboard">
          <Icon name="trophy" />
          排行榜
        </RouterLink>
        <RouterLink to="/settings">
          <Icon name="gear" />
          设置
        </RouterLink>
      </nav>
      <div class="side-foot">
        <template v-if="hello">
          <p class="who">{{ hello }}</p>
          <button type="button" class="btn btn--ghost btn--tiny" @click="logout">退出</button>
        </template>
        <template v-else>
          <RouterLink class="side-login" to="/login">登录</RouterLink>
          <RouterLink class="nav-cta" to="/register">注册</RouterLink>
        </template>
      </div>
    </aside>

    <div class="main">
      <header v-if="lobby" class="lobby-bar">
        <button
          class="side-toggle"
          type="button"
          :aria-expanded="sideOpen"
          aria-controls="site-nav"
          @click="sideOpen = !sideOpen"
        >
          菜单
        </button>
        <RouterLink class="brand" to="/"><b>VirbiusGame</b></RouterLink>
      </header>

      <header v-else class="top">
        <RouterLink class="brand" to="/">
          <b>VirbiusGame</b>
          <span>红队实验室</span>
        </RouterLink>
        <nav class="nav">
          <RouterLink to="/">关卡</RouterLink>
          <RouterLink to="/leaderboard">排行榜</RouterLink>
          <RouterLink to="/settings">设置</RouterLink>
          <template v-if="hello">
            <span class="who">{{ hello }}</span>
            <button type="button" class="btn btn--ghost btn--tiny" @click="logout">退出</button>
          </template>
          <template v-else>
            <RouterLink to="/login">登录</RouterLink>
            <RouterLink class="nav-cta" to="/register">注册</RouterLink>
          </template>
        </nav>
      </header>

      <nav v-if="lobby" class="main-tabs" aria-label="分区">
        <RouterLink to="/">关卡</RouterLink>
        <RouterLink to="/leaderboard">排行榜</RouterLink>
        <RouterLink to="/settings">设置</RouterLink>
      </nav>

      <RouterView />
    </div>
  </div>
</template>
