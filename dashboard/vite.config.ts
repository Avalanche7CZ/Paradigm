import { defineConfig } from 'vite';
export default defineConfig({
  base: './',
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: { outDir: 'dist', target: 'es2020', sourcemap: false, emptyOutDir: true },
  preview: { proxy: {} },
  server: { proxy: { '/api': 'http://127.0.0.1:8765' } },
});
