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
	// One worker. Every e2e test registers + verifies a fresh user, and
	// firing four parallel register flows at the dev backend trips the
	// per-IP register/login rate limiters intermittently. Sequential runs
	// add a few seconds for the whole suite but eliminate the flake.
	workers: 1,
	// Clears Redis rate-limit + login-backoff counters and stale
	// @kliniq.test rows before any worker runs. Sequential register calls
	// otherwise pile up against a 5/min/IP quota if the suite kicks off
	// mid-window after an earlier run.
	globalSetup: './e2e/globalSetup.ts',
	testMatch: '**/*.e2e.{ts,js}',
	projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
});
