import { apiRequest } from '../../auth/api';
import type { Schemas } from '../index';

export type InvitationDto = Schemas['InvitationDto'];
export type CreateInvitationRequest = Schemas['CreateInvitationRequest'];

export const adminInvitationsApi = {
	list(includeHistory = false): Promise<readonly InvitationDto[]> {
		const qs = includeHistory ? '?includeHistory=true' : '';
		return apiRequest<readonly InvitationDto[]>('GET', `/api/v1/admin/invitations${qs}`, {});
	},

	create(input: CreateInvitationRequest): Promise<InvitationDto> {
		return apiRequest<InvitationDto>('POST', '/api/v1/admin/invitations', { body: input });
	},

	/** Idempotent — already-revoked rows return 409 ALREADY_TERMINAL. */
	revoke(id: string): Promise<void> {
		return apiRequest<void>('DELETE', `/api/v1/admin/invitations/${id}`, {});
	}
};
