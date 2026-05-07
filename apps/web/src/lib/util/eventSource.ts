/**
 * Thin typed wrapper around the browser's native `EventSource`. The
 * backend pushes booking events at /api/v1/events as `booking.created`,
 * `booking.updated`, `booking.cancelled`, `booking.started`, and
 * `booking.completed` — see `BookingEventKind` on the API side.
 *
 * Reconnect is handled by the browser natively (every ~3 seconds with
 * jitter on a dropped connection), so this wrapper doesn't reimplement
 * backoff. It does surface `onConnect` so callers can refresh their
 * server state once a fresh stream is established — the connection
 * cuts mean the SPA may have missed events while disconnected, so a
 * one-shot refetch on reconnect closes the gap.
 */
export type BookingEventKind = 'CREATED' | 'UPDATED' | 'CANCELLED' | 'STARTED' | 'COMPLETED';

export interface BookingEventPayload {
	kind: BookingEventKind;
	bookingId: string;
	operatingRoomId: string;
	occurredAt: string;
}

export interface BookingEventStreamOptions {
	onEvent: (event: BookingEventPayload) => void;
	/** Fires on initial open and on every successful reconnect. */
	onConnect?: () => void;
	/** Fires when the underlying EventSource transitions to its error state. */
	onDisconnect?: () => void;
}

export interface BookingEventStream {
	close(): void;
}

const WIRE_KINDS: ReadonlyArray<{ wire: string; kind: BookingEventKind }> = [
	{ wire: 'booking.created', kind: 'CREATED' },
	{ wire: 'booking.updated', kind: 'UPDATED' },
	{ wire: 'booking.cancelled', kind: 'CANCELLED' },
	{ wire: 'booking.started', kind: 'STARTED' },
	{ wire: 'booking.completed', kind: 'COMPLETED' }
];

export function subscribeToBookingEvents(opts: BookingEventStreamOptions): BookingEventStream {
	const es = new EventSource('/api/v1/events');

	es.addEventListener('open', () => opts.onConnect?.());
	es.addEventListener('error', () => opts.onDisconnect?.());

	for (const { wire } of WIRE_KINDS) {
		es.addEventListener(wire, ((evt: MessageEvent) => {
			try {
				const payload = JSON.parse(evt.data) as BookingEventPayload;
				opts.onEvent(payload);
			} catch (err) {
				// Malformed payload — log and drop. The backend serializes
				// every event the same way, so a parse failure is a
				// version-skew bug rather than something to retry.
				console.warn('booking event parse failed', err, evt.data);
			}
		}) as EventListener);
	}

	return {
		close: () => es.close()
	};
}
