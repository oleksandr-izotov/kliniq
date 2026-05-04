import type { ApiUser } from '$lib/auth/api';

declare global {
	namespace App {
		interface Locals {
			user: ApiUser | null;
		}
		interface PageData {
			user: ApiUser | null;
		}
	}
}

export {};
