import { redirect } from '@sveltejs/kit';
import type { LayoutServerLoad } from './$types';

/**
 * Gate the entire app behind a valid session. Anonymous visitors get
 * bounced to /login with a `next` hint so the login page can deep-link
 * them back to where they were headed.
 */
export const load: LayoutServerLoad = ({ locals, url }) => {
	if (!locals.user) {
		const next = url.pathname + url.search;
		throw redirect(303, `/login?next=${encodeURIComponent(next)}`);
	}
	return { user: locals.user };
};
