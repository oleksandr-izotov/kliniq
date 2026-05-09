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
		adapter: adapter()
	}
};

export default config;
