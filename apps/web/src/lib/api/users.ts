import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

export type SurgeonSummaryDto = Schemas['SurgeonSummaryDto'];

/**
 * Surgeon picker data source for the booking modal. Active surgeons only —
 * the backend already filters out disabled accounts and non-surgeons, so
 * this list is safe to render directly as <option> rows.
 */
export const usersApi = {
	listSurgeons(): Promise<readonly SurgeonSummaryDto[]> {
		return apiRequest<readonly SurgeonSummaryDto[]>('GET', '/api/v1/users/surgeons', {});
	}
};
