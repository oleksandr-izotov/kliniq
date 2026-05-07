import { error } from '@sveltejs/kit';
import type { LayoutServerLoad } from './$types';

/**
 * Server-side gate for everything under `/admin/*`. The (app) layout
 * already redirects anonymous visitors to /login; this layer adds the
 * ADMIN-only check on top so a STAFF or MANAGER user gets a 403 page
 * instead of a screen of "missing data" toasts when the API rejects
 * each call. The backend's hasRole("ADMIN") matcher is the durable
 * guard — this is the friendly UX equivalent.
 */
export const load: LayoutServerLoad = async ({ parent }) => {
	const { user } = await parent();
	if (user.role !== 'ADMIN') {
		error(403, 'You need admin privileges to access this area.');
	}
	return {};
};
