import adapter from '@sveltejs/adapter-node';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		// Explicit Node adapter so the prod Docker build is reproducible and
		// independent of platform auto-detection. Output goes to `build/`,
		// runs with `node build` — see apps/web/Dockerfile.
		adapter: adapter(),
		// CSP lives here (not in Caddy) because SvelteKit injects inline
		// scripts for hydration and theme-detection that need to either be
		// allowlisted via hashes or signed with per-request nonces. With
		// `mode: 'auto'` SvelteKit picks hashes for prerendered routes and
		// nonces for SSR routes — adapter-node SSR's everything so we'll
		// get nonces injected into a <meta> tag in each rendered page.
		//
		// Caddy keeps the other headers (X-Content-Type-Options,
		// Referrer-Policy, Permissions-Policy, HSTS) — those don't need
		// per-page values and are simpler to manage at the edge.
		csp: {
			mode: 'auto',
			directives: {
				'default-src': ['self'],
				'img-src': ['self', 'data:'],
				'font-src': ['self', 'data:'],
				'script-src': ['self'],
				'style-src': ['self', 'unsafe-inline'],
				'connect-src': [
					'self',
					'https://*.sentry.io',
					'https://*.ingest.sentry.io',
					'https://*.ingest.de.sentry.io'
				],
				'frame-ancestors': ['none'],
				'base-uri': ['self'],
				'form-action': ['self']
			}
		}
	}
};

export default config;
