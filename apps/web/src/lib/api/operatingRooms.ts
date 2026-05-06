import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

export type OperatingRoomDto = Schemas['OperatingRoomDto'];
export type CreateOperatingRoomRequest = Schemas['CreateOperatingRoomRequest'];
export type UpdateOperatingRoomRequest = Schemas['UpdateOperatingRoomRequest'];

/**
 * Typed wrapper around `/api/v1/operating-rooms`. Reads are open to any
 * authenticated user; writes require MANAGER+ — an attempt by a STAFF
 * user surfaces as ApiError_(403). Callers handle role gating in the UI.
 */
export const operatingRoomsApi = {
	list(includeRetired = false): Promise<readonly OperatingRoomDto[]> {
		const qs = includeRetired ? '?includeRetired=true' : '';
		return apiRequest<readonly OperatingRoomDto[]>('GET', `/api/v1/operating-rooms${qs}`, {});
	},

	get(id: string): Promise<OperatingRoomDto> {
		return apiRequest<OperatingRoomDto>('GET', `/api/v1/operating-rooms/${id}`, {});
	},

	create(input: CreateOperatingRoomRequest): Promise<OperatingRoomDto> {
		return apiRequest<OperatingRoomDto>('POST', '/api/v1/operating-rooms', { body: input });
	},

	update(id: string, input: UpdateOperatingRoomRequest): Promise<OperatingRoomDto> {
		return apiRequest<OperatingRoomDto>('PATCH', `/api/v1/operating-rooms/${id}`, { body: input });
	},

	/** Soft-delete: status -> RETIRED. 409 if active bookings reference it. */
	archive(id: string): Promise<void> {
		return apiRequest<void>('DELETE', `/api/v1/operating-rooms/${id}`, {});
	}
};
