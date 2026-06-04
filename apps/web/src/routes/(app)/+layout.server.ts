import { redirect } from '@sveltejs/kit';
import type { LayoutServerLoad } from './$types';

/**
 * Gate the entire app behind a valid session. Anonymous visitors get
 * bounced to /login with a `next` hint so the login page can deep-link
 * them back to where they were headed.
 */
export const load: LayoutServerLoad = ({ locals, url }) => {
	if (!locals.user) {
		// An anonymous visitor landing on the app root (e.g. from a portfolio
		// link) gets the marketing page first — a logical chain into the product
		// instead of being dropped straight onto the sign-in form. Deep links
		// into a specific app route still go to /login with a ?next hint.
		if (url.pathname === '/') throw redirect(303, '/welcome');
		const next = url.pathname + url.search;
		throw redirect(303, `/login?next=${encodeURIComponent(next)}`);
	}
	return { user: locals.user };
};
