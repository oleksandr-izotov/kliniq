import { defineConfig, devices } from '@playwright/test';

/**
 * E2E config for the SvelteKit app.
 *
 * The Spring backend (https://localhost:8443) is NOT booted here — bring
 * it up separately via `./gradlew bootRun` (or rely on `reuseExistingServer`
 * locally). The dev server runs through Vite so the /api proxy stays
 * active; preview-mode wouldn't proxy and the SPA would 404 on auth calls.
 *
 * Both servers self-sign their TLS in dev; `ignoreHTTPSErrors: true`
 * lets Playwright trust the mkcert chain inside the browser context.
 */
export default defineConfig({
	webServer: {
		command: 'pnpm dev',
		url: 'https://localhost:5173',
		// Reuse an already-running dev server (local) — Playwright still
		// starts the command if nothing is on the URL, so CI works the
		// same way without extra plumbing.
		reuseExistingServer: true,
		timeout: 120_000,
		ignoreHTTPSErrors: true
	},
	use: {
		baseURL: 'https://localhost:5173',
		ignoreHTTPSErrors: true,
		trace: 'retain-on-failure'
	},
	testMatch: '**/*.e2e.{ts,js}',
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
