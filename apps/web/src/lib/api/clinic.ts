import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

export type ClinicSettingsDto = Schemas['ClinicSettingsDto'];
export type UpdateClinicSettingsRequest = Schemas['UpdateClinicSettingsRequest'];

/**
 * Typed wrapper around `/api/v1/clinic/settings`. GET is open to any
 * authenticated user; PATCH requires ADMIN — non-admins get a clean
 * ApiError_(403) which the page renders as a "read-only" hint.
 */
export const clinicApi = {
	get(): Promise<ClinicSettingsDto> {
		return apiRequest<ClinicSettingsDto>('GET', '/api/v1/clinic/settings', {});
	},

	update(input: UpdateClinicSettingsRequest): Promise<ClinicSettingsDto> {
		return apiRequest<ClinicSettingsDto>('PATCH', '/api/v1/clinic/settings', { body: input });
	}
};
