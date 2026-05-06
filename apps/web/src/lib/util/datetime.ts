import { CalendarDateTime, parseAbsolute, parseDate, toZoned } from '@internationalized/date';

/** Add `days` to a YYYY-MM-DD string, returning the same format. */
export function addDays(date: string, days: number): string {
	const d = parseDate(date).add({ days });
	return `${d.year.toString().padStart(4, '0')}-${d.month.toString().padStart(2, '0')}-${d.day
		.toString()
		.padStart(2, '0')}`;
}

/**
 * Build an absolute ISO instant ("…Z" or "…+02:00") from a local "wall
 * clock" reading the user typed into a date + time input, interpreted in
 * the clinic's IANA zone. Day 25's booking modal needs this to send
 * Spring's `OffsetDateTime` parser something it'll accept without doing
 * the SPA-side timezone math by hand.
 */
export function localToIso(date: string, time: string, tz: string): string {
	const [yearS, monthS, dayS] = date.split('-');
	const [hourS, minuteS] = time.split(':');
	const cdt = new CalendarDateTime(
		Number(yearS),
		Number(monthS),
		Number(dayS),
		Number(hourS),
		Number(minuteS),
		0
	);
	return toZoned(cdt, tz).toAbsoluteString();
}

/** ISO instant → wall-clock parts in the clinic's zone. */
export function isoToLocalParts(iso: string, tz: string): { date: string; time: string } {
	const zoned = parseAbsolute(iso, tz);
	const date = `${zoned.year.toString().padStart(4, '0')}-${zoned.month
		.toString()
		.padStart(2, '0')}-${zoned.day.toString().padStart(2, '0')}`;
	const time = `${zoned.hour.toString().padStart(2, '0')}:${zoned.minute
		.toString()
		.padStart(2, '0')}`;
	return { date, time };
}

/** "HH:mm" formatted in the clinic's zone — used for booking-block labels. */
export function formatLocalTime(iso: string, tz: string): string {
	return isoToLocalParts(iso, tz).time;
}

/**
 * Minutes since clinic-local midnight on the booking's local day. Used by
 * the schedule grid to position absolute booking blocks within an OR
 * column. We measure from midnight (not working-hours start) so bookings
 * that drift outside the configured window still land somewhere sane.
 */
export function localMinutesOfDay(iso: string, tz: string): number {
	const zoned = parseAbsolute(iso, tz);
	return zoned.hour * 60 + zoned.minute;
}

/** "HH:mm:ss" → minutes since midnight. Clinic working hours come back this way. */
export function timeStringToMinutes(time: string): number {
	const [h, m] = time.split(':').map(Number);
	return h * 60 + m;
}

/** Today in the clinic's zone, formatted YYYY-MM-DD. */
export function todayInZone(tz: string): string {
	const fmt = new Intl.DateTimeFormat('en-CA', {
		timeZone: tz,
		year: 'numeric',
		month: '2-digit',
		day: '2-digit'
	});
	return fmt.format(new Date());
}
