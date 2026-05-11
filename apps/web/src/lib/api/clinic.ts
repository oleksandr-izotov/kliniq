import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

/**
 * `onboardedAt` is added in Sprint 5 Day 58 (backend migration V5) but
 * lives outside the generated DTO until the next `pnpm gen:api` against
 * the deployed spec. The intersection augments the generated type with
 * the additional field without forking generated.ts.
 */
export type ClinicSettingsDto = Schemas['ClinicSettingsDto'] & {
	onboardedAt: string | null;
};
export type UpdateClinicSettingsRequest = Schemas['UpdateClinicSettingsRequest'];

/**
 * Typed wrapper around `/api/v1/clinic/settings`. GET is open to any
 * authenticated user; PATCH and POST /onboard require ADMIN — non-admins
 * get a clean ApiError_(403) which the page renders as a "read-only" hint.
 */
export const clinicApi = {
	get(): Promise<ClinicSettingsDto> {
		return apiRequest<ClinicSettingsDto>('GET', '/api/v1/clinic/settings', {});
	},

	update(input: UpdateClinicSettingsRequest): Promise<ClinicSettingsDto> {
		return apiRequest<ClinicSettingsDto>('PATCH', '/api/v1/clinic/settings', { body: input });
	},

	/**
	 * Stamp `onboardedAt = now()` on the singleton settings row. Idempotent
	 * (re-calling after the column is already non-null returns the existing
	 * timestamp). Invoked by the OnboardingWizard on its final "Done" step.
	 */
	onboard(): Promise<ClinicSettingsDto> {
		return apiRequest<ClinicSettingsDto>('POST', '/api/v1/clinic/settings/onboard', {});
	}
};
