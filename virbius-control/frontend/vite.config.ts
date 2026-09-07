import { createLogger, defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import { fileURLToPath, URL } from 'node:url';

const CONTROL = 'http://127.0.0.1:8080';

const logger = createLogger();
const origError = logger.error.bind(logger);
let lastProxyWarn = 0;
logger.error = (msg, options) => {
  const text = typeof msg === 'string' ? msg : String(msg);
  if (text.includes('http proxy error') && (text.includes('ECONNREFUSED') || text.includes('ECONNRESET'))) {
    const now = Date.now();
    if (now - lastProxyWarn > 5000) {
      lastProxyWarn = now;
      origError(
        `virbius-control 未在 ${CONTROL} 监听。请先启动调试配置 “Virbius Control …” 或 compound “Virbius: Control + Engine + Auth”（Vite 只提供前端，API 要等 Control 起来）。`,
        { timestamp: true }
      );
    }
    return;
  }
  origError(msg, options);
};

function proxyToControl() {
  return {
    target: CONTROL,
    changeOrigin: true,
    xfwd: true,
    configure(proxy: { on: (event: string, fn: (...args: unknown[]) => void) => void }) {
      proxy.on('proxyReq', (proxyReq: { setHeader: (k: string, v: string) => void }, req: { headers?: { host?: string } }) => {
        const host = req.headers?.host;
        if (host) proxyReq.setHeader('X-Forwarded-Host', host);
      });
      proxy.on('error', (_err, _req, res: { headersSent?: boolean; writableEnded?: boolean; writeHead?: Function; end?: Function }) => {
        if (res?.writeHead && !res.headersSent && !res.writableEnded) {
          res.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' });
          res.end?.(
            JSON.stringify({
              code: 503,
              message: `virbius-control 未启动（${CONTROL}）。请先在 Cursor 调试里启动 Control。`
            })
          );
        }
      });
    }
  };
}

export default defineConfig({
  customLogger: logger,
  plugins: [vue()],
  base: '/ui/',
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  build: {
    outDir: '../src/main/resources/static/ui',
    emptyOutDir: true,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'chart': ['chart.js', 'vue-chartjs'],
          'vendor': ['vue', 'vue-router', 'pinia', 'vue-i18n']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': proxyToControl(),
      '/ui/login': proxyToControl(),
      '/ui/callback': proxyToControl(),
      '/ui/logout': proxyToControl(),
      '/login': { target: 'http://127.0.0.1:8083', changeOrigin: true }
    }
  }
});
