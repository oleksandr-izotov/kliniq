import { defineConfig } from 'vite';
import tailwindcss from '@tailwindcss/vite';
import { sveltekit } from '@sveltejs/kit/vite';
import fs from 'node:fs';
import path from 'node:path';

// Vite's prod build runs through this file, so we keep the import
// dependency surface to runtime-only packages — vitest configuration
// lives in vitest.config.ts to avoid pulling a test-only dep into
// the production image.

// Load mkcert-issued certs from the monorepo-level certs/ directory.
// They aren't committed; run `mkcert localhost 127.0.0.1 ::1` once locally.
const certsDir = path.resolve(__dirname, '../../certs');
const certPath = path.join(certsDir, 'localhost.pem');
const keyPath = path.join(certsDir, 'localhost-key.pem');
const certsExist = fs.existsSync(certPath) && fs.existsSync(keyPath);

export default defineConfig({
	plugins: [tailwindcss(), sveltekit()],
	server: {
		port: 5173,
		strictPort: true,
		https: certsExist
			? { cert: fs.readFileSync(certPath), key: fs.readFileSync(keyPath) }
			: undefined,
		proxy: {
			// Forward backend paths to the local Spring Boot API. Backend serves
			// REST endpoints under /api/v1/* and actuator under /actuator/*. Since
			// both are first-class on the backend, we proxy each prefix as-is —
			// no rewrite, so frontend code uses the same paths that the API exposes.
			'/api': { target: 'https://localhost:8443', changeOrigin: true, secure: false },
			'/actuator': { target: 'https://localhost:8443', changeOrigin: true, secure: false }
		}
	}
});
