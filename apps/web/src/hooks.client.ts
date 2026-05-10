import * as Sentry from '@sentry/sveltekit';
import { handleErrorWithSentry } from '@sentry/sveltekit';
import { env } from '$env/dynamic/public';
import type { HandleClientError } from '@sveltejs/kit';

// Client-side Sentry init. `enabled: false` when DSN is missing (local dev,
// preview builds) keeps the SDK from emitting warnings or sending phantom
// events. Source maps are uploaded by the Sentry vite plugin at build time
// — see vite.config.ts — so production stack traces resolve to real
// .svelte / .ts files in the dashboard.
Sentry.init({
	dsn: env.PUBLIC_SENTRY_DSN,
	enabled: Boolean(env.PUBLIC_SENTRY_DSN),
	environment: 'prod',
	tracesSampleRate: 0,
	replaysSessionSampleRate: 0,
	replaysOnErrorSampleRate: 0
});

export const handleError: HandleClientError = handleErrorWithSentry();
