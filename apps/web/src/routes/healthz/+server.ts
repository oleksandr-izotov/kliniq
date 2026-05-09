import { json } from '@sveltejs/kit';

/**
 * Liveness probe for Coolify / k8s / curl-from-laptop. Returns 200 with
 * a tiny JSON body so the SvelteKit Node server is the thing that
 * actually answered. Doesn't hit the backend, doesn't hit the DB —
 * the backend has its own `/actuator/health` for that — this route
 * just confirms the SPA runtime is alive.
 *
 * Public route. No data leakage worth caring about; the prod URL is
 * already known to anyone scanning the load balancer.
 */
export const GET = () => json({ status: 'ok' });
