import { cleanupTestData } from './helpers';

/**
 * Runs once before any worker spawns. Clears rate-limit counters and
 * login-backoff keys in Redis plus stale @kliniq.test rows in Postgres.
 *
 * The suite registers ~one fresh user per test file (auth, booking,
 * mobile, passkey, realtime). With `workers: 1` they run back-to-back
 * inside the 60-second `POST /auth/register` quota window, so the
 * counters need to start at zero or the back of the suite trips the
 * limiter on a perfectly fresh DB.
 */
export default async function globalSetup(): Promise<void> {
	await cleanupTestData();
}
