import type { LayoutServerLoad } from './$types';

/** Hydrate $page.data.user across the app so client code skips a /me round-trip. */
export const load: LayoutServerLoad = ({ locals }) => {
	return { user: locals.user };
};
