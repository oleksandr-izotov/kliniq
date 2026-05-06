import { apiRequest } from '../auth/api';
import type { Schemas } from './index';

export type ScheduleDto = Schemas['ScheduleDto'];
export type OperatingRoomScheduleDto = Schemas['OperatingRoomScheduleDto'];

/**
 * Day-view: every visible OR with its bookings. The clinic's IANA timezone
 * comes back on every response so the SPA renders booking times in the
 * clinic's local zone without guessing. MAINTENANCE rooms are hidden by
 * default; opting in surfaces them with a status pill so admins can see
 * what's down.
 */
export const scheduleApi = {
	day(date?: string, includeMaintenance = false): Promise<ScheduleDto> {
		const params = new URLSearchParams();
		if (date) params.set('date', date);
		if (includeMaintenance) params.set('includeMaintenance', 'true');
		const qs = params.toString();
		return apiRequest<ScheduleDto>('GET', `/api/v1/schedule${qs ? `?${qs}` : ''}`, {});
	}
};
