import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// One origin for the browser (ADR-0013, rule G10): in development the dev server forwards /api
// to the gateway, so the browser never makes a cross-origin call and no CORS is configured.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:18080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
