import { createRouter, createWebHistory } from "vue-router";
import { api } from "./api.js";

const routes = [
  { path: "/", name: "home", component: () => import("./pages/Home.vue") },
  { path: "/login", name: "login", component: () => import("./pages/Login.vue"), meta: { guest: true } },
  { path: "/register", name: "register", component: () => import("./pages/Register.vue"), meta: { guest: true } },
  { path: "/leaderboard", name: "board", component: () => import("./pages/Leaderboard.vue") },
  { path: "/settings", name: "settings", component: () => import("./pages/Settings.vue") },
  { path: "/play/:alias", name: "play", component: () => import("./pages/Play.vue"), meta: { auth: true } },
];

const router = createRouter({ history: createWebHistory(), routes });

router.beforeEach(async (to) => {
  if (!to.meta.auth && !to.meta.guest) return true;
  const me = await api("/api/me");
  if (to.meta.auth && !me.user) return { name: "login", query: { next: to.fullPath } };
  if (to.meta.guest && me.user) return { name: "home" };
  return true;
});

export default router;
