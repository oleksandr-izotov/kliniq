import { defineConfig } from 'vitest/config';

// Vitest config split out from vite.config.ts so the production Docker
// build doesn't need vitest in node_modules. Vitest discovers this file
// automatically (preferred over vite.config.ts when both exist).

export default defineConfig({
	test: {
		expect: { requireAssertions: true },
		projects: [
			{
				extends: './vite.config.ts',
				test: {
					name: 'server',
					environment: 'node',
					include: ['src/**/*.{test,spec}.{js,ts}'],
					exclude: ['src/**/*.svelte.{test,spec}.{js,ts}']
				}
			}
		]
	}
});
