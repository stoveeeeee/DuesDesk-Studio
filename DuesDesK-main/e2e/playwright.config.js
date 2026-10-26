// @ts-check
const { defineConfig } = require('@playwright/test');

const port = process.env.E2E_PORT || '8765';

// The workflows change the (throwaway) chapter data, so they run one at a time: light theme first, then dark.
module.exports = defineConfig({
    testDir: './tests',
    timeout: 90_000,
    expect: { timeout: 10_000 },
    workers: 1,
    fullyParallel: false,
    reporter: [['list']],
    use: {
        baseURL: `http://127.0.0.1:${port}`,
        acceptDownloads: true,
        trace: 'retain-on-failure',
        viewport: { width: 1440, height: 960 }
    },
    projects: [
        { name: 'light', use: { browserName: 'chromium', colorScheme: 'light' } },
        { name: 'dark', use: { browserName: 'chromium', colorScheme: 'dark' } }
    ],
    webServer: {
        command: 'node start-app.mjs',
        url: `http://127.0.0.1:${port}/health`,
        timeout: 240_000,
        reuseExistingServer: false,
        stdout: 'pipe'
    }
});
