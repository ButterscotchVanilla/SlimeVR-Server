import react from '@vitejs/plugin-react';
import { defineConfig, PluginOption } from 'vite';
import { execSync } from 'child_process';
import path from 'path';
import { visualizer } from 'rollup-plugin-visualizer';
import { VitePWA } from 'vite-plugin-pwa';

const commitHash = execSync('git rev-parse --verify --short HEAD').toString().trim();
const versionTag = execSync('git --no-pager tag --sort -taggerdate --points-at HEAD')
  .toString()
  .split('\n')[0]
  .trim();
const lastVersionTag = execSync('git describe --tags --abbrev=0 HEAD').toString().trim();
// If not empty then it's not clean
const gitClean = execSync('git status --porcelain').toString() ? false : true;

console.log(`version is ${versionTag || commitHash}${gitClean ? '' : '-dirty'}`);

// Detect fluent file changes
export function i18nHotReload(): PluginOption {
  return {
    name: 'i18n-hot-reload',
    handleHotUpdate({ file, server }) {
      if (file.endsWith('.ftl')) {
        console.log('Fluent files updated');
        server.ws.send({
          type: 'custom',
          event: 'locales-update',
        });
      }
    },
  };
}

// https://vitejs.dev/config/
export default defineConfig({
  define: {
    __COMMIT_HASH__: JSON.stringify(commitHash),
    __VERSION_TAG__: JSON.stringify(versionTag),
    __LAST_VERSION_TAG__: JSON.stringify(lastVersionTag),
    __GIT_CLEAN__: gitClean,
  },
  plugins: [
    react(),
    i18nHotReload(),
    visualizer() as PluginOption,
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: [
        'favicon.ico',
        'logo.svg',
        'logo192.png',
        'logo512.png',
        'logo-maskable.svg',
        'logo-maskable192.png',
        'logo-maskable512.png',
      ],
      strategies: 'generateSW',
      workbox: {
        maximumFileSizeToCacheInBytes: 3000000
      },
      manifest: {
        name: 'SlimeVR Web GUI',
        // eslint-disable-next-line camelcase
        short_name: 'SlimeVR GUI',
        description: 'A web interface for controlling the SlimeVR Server app',
        display: 'standalone',
        // eslint-disable-next-line camelcase
        theme_color: '#663499',
        // eslint-disable-next-line camelcase
        background_color: '#663499',
        icons: [
          {
            src: 'logo.svg',
            type: 'image/svg+xml',
            sizes: 'any 512x512 192x192',
          },
          {
            src: 'favicon.ico',
            sizes: '64x64 32x32 24x24 16x16',
            type: 'image/x-icon',
          },
          {
            src: 'logo192.png',
            type: 'image/png',
            sizes: '192x192',
          },
          {
            src: 'logo512.png',
            type: 'image/png',
            sizes: '512x512',
          },
          {
            src: 'logo-maskable.svg',
            type: 'image/svg+xml',
            sizes: 'any 512x512 192x192',
            purpose: 'any maskable',
          },
          {
            src: 'logo-maskable192.png',
            type: 'image/png',
            sizes: '192x192',
            purpose: 'any maskable',
          },
          {
            src: 'logo-maskable512.png',
            type: 'image/png',
            sizes: '512x512',
            purpose: 'any maskable',
          },
        ],
      },
    }),
  ],
  build: {
    target: 'es2022',
    emptyOutDir: true,
    commonjsOptions: {
      include: [/solarxr-protocol/, /node_modules/],
    },
  },
  optimizeDeps: {
    esbuildOptions: {
      target: 'es2022',
    },
    needsInterop: ['solarxr-protocol'],
    include: ['solarxr-protocol'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern',
      },
    },
  },
});
