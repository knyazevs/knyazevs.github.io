// @ts-check
import { defineConfig } from 'astro/config';
import svelte from '@astrojs/svelte';
import { docsPlugin } from './src/lib/docs-vite-plugin.ts';

export default defineConfig({
  integrations: [svelte()],
  vite: {
    plugins: [docsPlugin()],
  },
});