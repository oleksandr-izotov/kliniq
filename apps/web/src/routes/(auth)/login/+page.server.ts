import { redirect } from '@sveltejs/kit';
import type { PageServerLoad } from './$types';

/** Already-authenticated visitors don't need the login page. */
export const load: PageServerLoad = ({ locals }) => {
	if (locals.user) throw redirect(303, '/');
};
