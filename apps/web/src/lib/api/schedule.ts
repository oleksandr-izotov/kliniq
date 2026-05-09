import { apiRequest } from '../auth/api';
import type { Schemas } from './index';
import type { BookingDto } from './bookings';

export type ScheduleDto = Schemas['ScheduleDto'];
export type OperatingRoomScheduleDto = Schemas['OperatingRoomScheduleDto'];

/**
 * Hand-written wire shapes for the week-view: the controller returns
 * `ResponseEntity<*>` to keep the OR_NOT_FOUND error envelope on the
 * same code path, which strips both DTOs out of the OpenAPI spec.
 * Same dance we did for InvitationPreview / BookingConflictDetail.
 * Keep in sync with WeekScheduleDto + DayBookingsDto on the backend.
 */
export interface DayBookingsDto {
	date: string;
	bookings: readonly BookingDto[];
}

export interface WeekScheduleDto {
	operatingRoom: OperatingRoomScheduleDto;
	timezone: string;
	from: string;
	days: readonly DayBookingsDto[];
}

/**
 * Day-view: every visible OR with its bookings. The clinic's IANA timezone
 * comes back on every response so the SPA renders booking times in the
 * clinic's local zone without guessing. MAINTENANCE rooms are hidden by
 * default; opting in surfaces them with a status pill so admins can see
 * what's down.
 *
 * Week-view is OR-centric: pick one OR via [week] and get exactly seven
 * days of its bookings. The picker on the SPA decides which OR is
 * visible; the schedule endpoint doesn't filter by status.
 */
export const scheduleApi = {
	day(date?: string, includeMaintenance = false): Promise<ScheduleDto> {
		const params = new URLSearchParams();
		if (date) params.set('date', date);
		if (includeMaintenance) params.set('includeMaintenance', 'true');
		const qs = params.toString();
		return apiRequest<ScheduleDto>('GET', `/api/v1/schedule${qs ? `?${qs}` : ''}`, {});
	},

	week(operatingRoomId: string, from: string): Promise<WeekScheduleDto> {
		const params = new URLSearchParams({ operatingRoomId, from });
		return apiRequest<WeekScheduleDto>('GET', `/api/v1/schedule/week?${params.toString()}`, {});
	}
};
