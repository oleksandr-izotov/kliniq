import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

export type BookingDto = Schemas['BookingDto'];
export type CreateBookingRequest = Schemas['CreateBookingRequest'];
export type UpdateBookingRequest = Schemas['UpdateBookingRequest'];
export type CancelBookingRequest = Schemas['CancelBookingRequest'];
export type BookingStatus = BookingDto['status'];

export interface BookingFilter {
	operatingRoomId?: string;
	surgeonId?: string;
	from?: string;
	to?: string;
	status?: BookingStatus;
}

function buildQuery(filter: BookingFilter): string {
	const params = new URLSearchParams();
	if (filter.operatingRoomId) params.set('operatingRoomId', filter.operatingRoomId);
	if (filter.surgeonId) params.set('surgeonId', filter.surgeonId);
	if (filter.from) params.set('from', filter.from);
	if (filter.to) params.set('to', filter.to);
	if (filter.status) params.set('status', filter.status);
	const qs = params.toString();
	return qs ? `?${qs}` : '';
}

/**
 * Typed wrapper around `/api/v1/bookings`. Reads + create/edit are open to
 * any STAFF+ user. Lifecycle endpoints (cancel/start/complete) take the
 * booking through its FSM — the backend rejects illegal transitions with
 * 409 ILLEGAL_TRANSITION, which the SPA surfaces as a toast.
 */
export const bookingsApi = {
	list(filter: BookingFilter = {}): Promise<readonly BookingDto[]> {
		return apiRequest<readonly BookingDto[]>('GET', `/api/v1/bookings${buildQuery(filter)}`, {});
	},

	get(id: string): Promise<BookingDto> {
		return apiRequest<BookingDto>('GET', `/api/v1/bookings/${id}`, {});
	},

	create(input: CreateBookingRequest): Promise<BookingDto> {
		return apiRequest<BookingDto>('POST', '/api/v1/bookings', { body: input });
	},

	update(id: string, input: UpdateBookingRequest): Promise<BookingDto> {
		return apiRequest<BookingDto>('PATCH', `/api/v1/bookings/${id}`, { body: input });
	},

	cancel(id: string, reason?: string): Promise<BookingDto> {
		return apiRequest<BookingDto>('POST', `/api/v1/bookings/${id}/cancel`, {
			body: reason ? { reason } : {}
		});
	},

	start(id: string): Promise<BookingDto> {
		return apiRequest<BookingDto>('POST', `/api/v1/bookings/${id}/start`, {});
	},

	complete(id: string): Promise<BookingDto> {
		return apiRequest<BookingDto>('POST', `/api/v1/bookings/${id}/complete`, {});
	}
};
