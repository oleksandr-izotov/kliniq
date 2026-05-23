<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { ApiError_ } from '$lib/auth/api';
	import { scheduleApi, type ScheduleDto, type WeekScheduleDto } from '$lib/api/schedule';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import { operatingRoomsApi, type OperatingRoomDto } from '$lib/api/operatingRooms';
	import { usersApi, type SurgeonSummaryDto } from '$lib/api/users';
	import { bookingsApi, type BookingDto } from '$lib/api/bookings';
	import {
		addDays,
		formatLocalTime,
		localMinutesOfDay,
		localToIso,
		timeStringToMinutes,
		todayInZone
	} from '$lib/util/datetime';
	import {
		subscribeToBookingEvents,
		type BookingEventPayload,
		type BookingEventStream
	} from '$lib/util/eventSource';
	import BookingFormDialog from '$lib/components/bookings/BookingFormDialog.svelte';
	import BookingSidePanel from '$lib/components/bookings/BookingSidePanel.svelte';
	import EmptyState from '$lib/components/EmptyState.svelte';

	const PIXELS_PER_MINUTE = 1.2; // 60 min ≈ 72px row height
	const SLOT_MINUTES = 30;

	let clinic = $state<ClinicSettingsDto | null>(null);
	let rooms = $state<readonly OperatingRoomDto[]>([]);
	let surgeons = $state<readonly SurgeonSummaryDto[]>([]);
	let schedule = $state<ScheduleDto | null>(null);
	let weekSchedule = $state<WeekScheduleDto | null>(null);

	let view = $state<'day' | 'week'>('day');
	let date = $state(''); // YYYY-MM-DD; populated once we know the clinic zone
	let weekFrom = $state(''); // YYYY-MM-DD; first day of the week
	let weekOrId = $state<string | null>(null); // OR currently picked for the week-view
	let loading = $state(true);
	let scheduleLoading = $state(false);
	let bootError = $state<string | null>(null);

	// ---- Dialog state ----------------------------------------------------
	let dialogOpen = $state(false);
	let dialogMode = $state<'create' | 'edit'>('create');
	let dialogPrefill = $state<{ date: string; time: string; operatingRoomId?: string } | undefined>(
		undefined
	);
	let dialogExisting = $state<BookingDto | undefined>(undefined);

	// ---- Side panel state ------------------------------------------------
	let selectedBooking = $state<BookingDto | null>(null);

	// ---- Drag-drop reschedule state --------------------------------------
	// `draggingId` drives the source booking's opacity; `dragOverOrId` rings
	// the slot column currently under the cursor. Both clear on dragend.
	let draggingId = $state<string | null>(null);
	let dragOverOrId = $state<string | null>(null);

	// ---- Real-time stream ------------------------------------------------
	let stream: BookingEventStream | null = null;

	onMount(async () => {
		try {
			[clinic, rooms, surgeons] = await Promise.all([
				clinicApi.get(),
				operatingRoomsApi.list(),
				usersApi.listSurgeons()
			]);
			date = todayInZone(clinic.timezone);
			weekFrom = date;
			weekOrId = rooms[0]?.id ?? null;
			await loadSchedule();
			// Subscribe AFTER initial state is in place — onConnect fires on
			// open + every reconnect and triggers a fresh schedule fetch, so
			// any events we missed while disconnected get reconciled in the
			// next round-trip.
			stream = subscribeToBookingEvents({
				onEvent: handleBookingEvent,
				onConnect: () => void loadSchedule()
			});
		} catch (e) {
			bootError = describe(e);
		} finally {
			loading = false;
		}
	});

	onDestroy(() => stream?.close());

	async function handleBookingEvent(event: BookingEventPayload) {
		// Refresh the visible day (cheap; the day-view query is one round-trip).
		void loadSchedule();
		// If the event names the booking that's currently open in the side
		// panel, re-fetch it so its status pill / available-actions reflect
		// what the other tab just did. We do this in addition to the
		// schedule refresh because the panel reads from `selectedBooking`,
		// not from the schedule, and otherwise wouldn't pick up the change.
		if (selectedBooking && selectedBooking.id === event.bookingId) {
			try {
				selectedBooking = await bookingsApi.get(event.bookingId);
			} catch {
				// Ignore — the next user action on the panel will surface
				// any persistent issue.
			}
		}
	}

	async function loadSchedule() {
		if (!clinic) return;
		scheduleLoading = true;
		try {
			if (view === 'week' && weekOrId) {
				weekSchedule = await scheduleApi.week(weekOrId, weekFrom);
			} else {
				schedule = await scheduleApi.day(date);
			}
		} catch (e) {
			toast.error(describe(e));
		} finally {
			scheduleLoading = false;
		}
	}

	function setView(next: 'day' | 'week') {
		if (next === view) return;
		view = next;
		void loadSchedule();
	}

	function shiftWeek(deltaDays: number) {
		weekFrom = addDays(weekFrom, deltaDays);
		void loadSchedule();
	}

	function describe(e: unknown): string {
		if (e instanceof ApiError_) return e.payload.message || 'Server error.';
		return 'Network error. Please try again.';
	}

	function shiftDate(deltaDays: number) {
		date = addDays(date, deltaDays);
		void loadSchedule();
	}

	function dateChanged() {
		void loadSchedule();
	}

	// ---- Grid math -------------------------------------------------------
	// Working hours come back as "HH:mm:ss"; we anchor the grid on those.
	const startMin = $derived(clinic ? timeStringToMinutes(clinic.workingHoursStart) : 8 * 60);
	const endMin = $derived(clinic ? timeStringToMinutes(clinic.workingHoursEnd) : 20 * 60);
	const totalMin = $derived(endMin - startMin);
	const totalPx = $derived(totalMin * PIXELS_PER_MINUTE);

	const hourTicks = $derived.by(() => {
		const ticks: { minute: number; label: string }[] = [];
		// Round the first hour mark to the next full hour past start.
		const first = Math.ceil(startMin / 60) * 60;
		for (let m = first; m <= endMin; m += 60) {
			const h = Math.floor(m / 60);
			ticks.push({ minute: m, label: `${h.toString().padStart(2, '0')}:00` });
		}
		return ticks;
	});

	const tz = $derived(view === 'week' ? weekSchedule?.timezone : schedule?.timezone);

	// Total bookings across all rooms for the visible day. Drives the empty-state
	// overlay: when zero, the grid still renders (slots stay clickable / drop
	// targets) but a branded illustration floats over it as a "what next" hint.
	const dayBookingCount = $derived(
		schedule ? schedule.operatingRooms.reduce((n, or) => n + or.bookings.length, 0) : 0
	);

	function bookingTop(b: BookingDto): number {
		const zone = tz;
		if (!zone) return 0;
		const m = localMinutesOfDay(b.startsAt, zone);
		return Math.max(0, m - startMin) * PIXELS_PER_MINUTE;
	}

	function bookingHeight(b: BookingDto): number {
		const zone = tz;
		if (!zone) return 0;
		const s = localMinutesOfDay(b.startsAt, zone);
		const e = localMinutesOfDay(b.endsAt, zone);
		return Math.max(20, (e - s) * PIXELS_PER_MINUTE);
	}

	function statusColor(status: BookingDto['status']): string {
		switch (status) {
			case 'SCHEDULED':
				return 'bg-primary/15 border-primary/40 text-foreground';
			case 'IN_PROGRESS':
				return 'bg-amber-500/20 border-amber-500/50 text-foreground';
			case 'COMPLETED':
				return 'bg-emerald-500/15 border-emerald-500/40 text-muted-foreground';
			case 'CANCELLED':
				return 'bg-muted border-border text-muted-foreground line-through';
		}
	}

	// ---- Drag-drop reschedule -------------------------------------------
	// HTML5 native drag-drop only — mobile/touch users keep the existing
	// edit-dialog flow as the fallback. The booking's id, source OR, and
	// original duration travel through dataTransfer so the drop handler
	// doesn't need to look the booking up by id in component state.
	type DragPayload = {
		id: string;
		sourceOperatingRoomId: string;
		durationMs: number;
	};

	function handleBookingDragStart(e: DragEvent, b: BookingDto) {
		// Terminal-state bookings (COMPLETED / CANCELLED) shouldn't move.
		if (b.status !== 'SCHEDULED' && b.status !== 'IN_PROGRESS') {
			e.preventDefault();
			return;
		}
		if (!e.dataTransfer) return;
		const payload: DragPayload = {
			id: b.id,
			sourceOperatingRoomId: b.operatingRoomId,
			durationMs: new Date(b.endsAt).getTime() - new Date(b.startsAt).getTime()
		};
		e.dataTransfer.setData('application/json', JSON.stringify(payload));
		e.dataTransfer.effectAllowed = 'move';
		draggingId = b.id;
	}

	function handleBookingDragEnd() {
		draggingId = null;
		dragOverOrId = null;
	}

	function handleSlotDragOver(e: DragEvent, orId: string) {
		if (!draggingId) return;
		e.preventDefault(); // mandatory so the matching drop event fires
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		dragOverOrId = orId;
	}

	function handleSlotDragLeave(orId: string) {
		if (dragOverOrId === orId) dragOverOrId = null;
	}

	async function handleSlotDrop(e: DragEvent, targetOrId: string) {
		e.preventDefault();
		dragOverOrId = null;
		draggingId = null;
		if (!e.dataTransfer || !clinic || !schedule) return;

		const raw = e.dataTransfer.getData('application/json');
		if (!raw) return;
		let payload: DragPayload;
		try {
			payload = JSON.parse(raw) as DragPayload;
		} catch {
			return;
		}

		// Find the original booking (might live in any OR's bookings list)
		const booking = schedule.operatingRooms
			.flatMap((or) => or.bookings)
			.find((b) => b.id === payload.id);
		if (!booking) return;

		// Map drop Y → minutes-of-day, snap to SLOT_MINUTES
		const target = e.currentTarget as HTMLElement;
		const rect = target.getBoundingClientRect();
		const offsetY = e.clientY - rect.top;
		const minutesFromColStart = offsetY / PIXELS_PER_MINUTE;
		const absoluteMinutes =
			Math.round((startMin + minutesFromColStart) / SLOT_MINUTES) * SLOT_MINUTES;
		const newEndMinutes = absoluteMinutes + payload.durationMs / 60_000;

		if (absoluteMinutes < 0 || newEndMinutes > 24 * 60) {
			toast.error('Booking would fall outside the day.');
			return;
		}

		const newStartTime = `${Math.floor(absoluteMinutes / 60)
			.toString()
			.padStart(2, '0')}:${(absoluteMinutes % 60).toString().padStart(2, '0')}`;
		const newStartIso = localToIso(date, newStartTime, clinic.timezone);
		const newEndIso = new Date(new Date(newStartIso).getTime() + payload.durationMs).toISOString();

		// No-op if nothing changed
		if (newStartIso === booking.startsAt && targetOrId === booking.operatingRoomId) return;

		// Optimistic local mutation — SSE will reconcile on success, we
		// revert manually on failure.
		const oldStart = booking.startsAt;
		const oldEnd = booking.endsAt;
		const oldOrId = booking.operatingRoomId;
		applyLocalMove(payload.id, targetOrId, newStartIso, newEndIso);

		try {
			await bookingsApi.update(payload.id, {
				operatingRoomId: targetOrId,
				surgeonId: booking.surgeonId,
				startsAt: newStartIso,
				endsAt: newEndIso,
				opType: booking.opType,
				notes: booking.notes ?? ''
			});
		} catch (err) {
			applyLocalMove(payload.id, oldOrId, oldStart, oldEnd);
			if (err instanceof ApiError_) {
				if (err.payload.code === 'BOOKING_CONFLICT') {
					toast.error('That slot is already booked.');
				} else {
					toast.error(err.payload.message || 'Could not move the booking.');
				}
			} else {
				toast.error('Network error.');
			}
		}
	}

	function applyLocalMove(id: string, targetOrId: string, newStart: string, newEnd: string) {
		if (!schedule) return;
		// Re-bucket by operatingRoomId after updating the moved booking's
		// fields. Keeps the day-view in sync whether the drop was within
		// the same OR (re-time) or cross-OR (re-room + re-time).
		const flat = schedule.operatingRooms.flatMap((or) => or.bookings);
		const updated = flat.map((b) =>
			b.id === id ? { ...b, startsAt: newStart, endsAt: newEnd, operatingRoomId: targetOrId } : b
		);
		schedule = {
			...schedule,
			operatingRooms: schedule.operatingRooms.map((or) => ({
				...or,
				bookings: updated.filter((b) => b.operatingRoomId === or.id)
			}))
		};
	}

	// ---- Click handling --------------------------------------------------
	function openCreate(roomId: string, prefilledDate: string, event: MouseEvent) {
		const target = event.currentTarget as HTMLElement;
		const rect = target.getBoundingClientRect();
		const offsetMin = Math.round((event.clientY - rect.top) / PIXELS_PER_MINUTE);
		// Snap to the SLOT_MINUTES grid.
		const snapped = Math.max(0, Math.floor(offsetMin / SLOT_MINUTES) * SLOT_MINUTES);
		const m = startMin + snapped;
		const time = `${Math.floor(m / 60)
			.toString()
			.padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}`;
		dialogPrefill = { date: prefilledDate, time, operatingRoomId: roomId };
		dialogExisting = undefined;
		dialogMode = 'create';
		dialogOpen = true;
	}

	function selectBooking(b: BookingDto) {
		selectedBooking = b;
	}

	function closePanel() {
		selectedBooking = null;
	}

	function startEditFromPanel() {
		if (!selectedBooking) return;
		dialogExisting = selectedBooking;
		dialogPrefill = undefined;
		dialogMode = 'edit';
		dialogOpen = true;
	}

	function onDialogClose() {
		dialogOpen = false;
	}

	function onDialogSaved(saved: BookingDto) {
		dialogOpen = false;
		toast.success(dialogMode === 'edit' ? 'Booking updated.' : 'Booking created.');
		// Keep the side panel pointed at the same booking when this was an
		// edit — the saved row is fresher than what the panel had.
		if (dialogMode === 'edit') selectedBooking = saved;
		void loadSchedule();
	}

	function onPanelChanged(b: BookingDto) {
		selectedBooking = b;
		void loadSchedule();
	}
</script>

<svelte:head>
	<title>Schedule · Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-4 sm:p-6">
	<div class="mx-auto w-full max-w-7xl space-y-4">
		<header class="flex flex-wrap items-end justify-between gap-3">
			<div class="space-y-1">
				<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
				<h1 class="text-3xl font-bold tracking-tight">Schedule</h1>
				{#if clinic}
					<p class="text-sm text-muted-foreground">
						{clinic.name} · {clinic.timezone} · working {clinic.workingHoursStart.slice(
							0,
							5
						)}–{clinic.workingHoursEnd.slice(0, 5)}
					</p>
				{/if}
			</div>
			<div class="flex flex-wrap items-center gap-2">
				<!-- View toggle -->
				<div class="flex overflow-hidden rounded-md border border-input">
					<button
						type="button"
						class="px-3 py-1 text-sm {view === 'day'
							? 'bg-primary text-primary-foreground'
							: 'bg-background hover:bg-muted'}"
						onclick={() => setView('day')}
						aria-pressed={view === 'day'}
					>
						Day
					</button>
					<button
						type="button"
						class="px-3 py-1 text-sm {view === 'week'
							? 'bg-primary text-primary-foreground'
							: 'bg-background hover:bg-muted'}"
						onclick={() => setView('week')}
						aria-pressed={view === 'week'}
					>
						Week
					</button>
				</div>

				{#if view === 'day'}
					<Button variant="outline" size="sm" onclick={() => shiftDate(-1)} disabled={loading}>
						← Prev
					</Button>
					<Input
						type="date"
						bind:value={date}
						onchange={dateChanged}
						disabled={loading}
						class="w-36 sm:w-44"
					/>
					<Button variant="outline" size="sm" onclick={() => shiftDate(1)} disabled={loading}>
						Next →
					</Button>
					<Button
						variant="outline"
						size="sm"
						onclick={() => {
							if (!clinic) return;
							date = todayInZone(clinic.timezone);
							void loadSchedule();
						}}
						disabled={loading}
					>
						Today
					</Button>
				{:else}
					<select
						bind:value={weekOrId}
						onchange={loadSchedule}
						disabled={loading || rooms.length === 0}
						class="h-9 rounded-md border border-input bg-background px-3 py-1 text-sm"
					>
						{#each rooms as r (r.id)}
							<option value={r.id}>{r.code} · {r.name}</option>
						{/each}
					</select>
					<Button variant="outline" size="sm" onclick={() => shiftWeek(-7)} disabled={loading}>
						← Prev
					</Button>
					<Input
						type="date"
						bind:value={weekFrom}
						onchange={loadSchedule}
						disabled={loading}
						class="w-44"
					/>
					<Button variant="outline" size="sm" onclick={() => shiftWeek(7)} disabled={loading}>
						Next →
					</Button>
					<Button
						variant="outline"
						size="sm"
						onclick={() => {
							if (!clinic) return;
							weekFrom = todayInZone(clinic.timezone);
							void loadSchedule();
						}}
						disabled={loading}
					>
						This week
					</Button>
				{/if}
			</div>
		</header>

		{#if loading}
			<p class="text-sm text-muted-foreground">Loading…</p>
		{:else if bootError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{bootError}
			</p>
		{:else if rooms.length === 0}
			<div class="rounded-2xl border p-8 text-center">
				<p class="text-sm text-muted-foreground">
					No operating rooms to show. <a href="/operating-rooms" class="underline">Add one</a> to start
					scheduling.
				</p>
			</div>
		{:else if view === 'day' && schedule}
			<!--
				Day-view layout: each OR is a self-contained card with its own
				time gutter on the left and slot column on the right. Cards lay
				out in a CSS auto-fit grid — single column below `lg`, multi-
				column on larger viewports (`minmax(280px, 1fr)` means
				"as many columns as fit at ≥280 px each"). Replaces the original
				min-w-max horizontal-scroll layout which forced phones into a
				horizontal-scroll-of-shame.
			-->
			<div class="relative">
				{#if dayBookingCount === 0}
					<!--
						No bookings for this day. The grid below still renders so every
						slot stays clickable / a drop target — this illustration floats
						over it as a hint and is click-through (pointer-events-none).
					-->
					<div class="pointer-events-none absolute inset-x-0 top-0 z-10 flex justify-center pt-8">
						<div class="rounded-3xl bg-background/80 px-4 py-2 shadow-sm backdrop-blur-sm">
							<EmptyState
								image="/illustrations/empty-bookings.webp"
								title="No bookings on {date}"
								description="Click an empty slot to book, or drag a card here from another day."
							/>
						</div>
					</div>
				{/if}
				<div
					class="grid grid-cols-1 gap-4 lg:[grid-template-columns:repeat(auto-fit,minmax(280px,1fr))]"
				>
					{#each schedule.operatingRooms as or (or.id)}
						<article class="overflow-hidden rounded-2xl border">
							<header
								class="flex h-14 flex-col items-center justify-center border-b border-border bg-muted/40 px-2"
							>
								<span class="font-mono text-xs text-muted-foreground">{or.code}</span>
								<span class="truncate text-sm font-medium">{or.name}</span>
								{#if or.status !== 'ACTIVE'}
									<span class="rounded bg-muted px-1 text-[10px] text-muted-foreground uppercase">
										{or.status}
									</span>
								{/if}
							</header>
							<div class="flex">
								<!-- Time gutter -->
								<div class="w-12 shrink-0 border-r bg-muted/40 sm:w-16">
									<div class="relative" style:height="{totalPx}px">
										{#each hourTicks as t (t.minute)}
											<div
												class="absolute -translate-y-1/2 px-2 text-right text-xs text-muted-foreground"
												style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"
											>
												{t.label}
											</div>
										{/each}
									</div>
								</div>
								<!-- svelte-ignore a11y_click_events_have_key_events -->
								<!-- svelte-ignore a11y_no_static_element_interactions -->
								<div
									class="relative flex-1 cursor-cell hover:bg-muted/30 {dragOverOrId === or.id
										? 'ring-2 ring-primary/40 ring-inset'
										: ''}"
									style:height="{totalPx}px"
									onclick={(e) => openCreate(or.id, date, e)}
									ondragover={(e) => handleSlotDragOver(e, or.id)}
									ondragleave={() => handleSlotDragLeave(or.id)}
									ondrop={(e) => handleSlotDrop(e, or.id)}
									aria-label="Create booking in {or.code}"
								>
									<!-- Hour grid lines -->
									{#each hourTicks as t (t.minute)}
										<div
											class="pointer-events-none absolute right-0 left-0 border-t border-border/50"
											style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"
										></div>
									{/each}
									<!-- Booking blocks -->
									{#each or.bookings as b (b.id)}
										{@const canDrag = b.status === 'SCHEDULED' || b.status === 'IN_PROGRESS'}
										<button
											type="button"
											class="absolute right-1 left-1 cursor-pointer rounded border px-2 py-1 text-left text-xs shadow-sm {statusColor(
												b.status
											)} {selectedBooking?.id === b.id ? 'ring-2 ring-primary' : ''} {draggingId ===
											b.id
												? 'opacity-50'
												: ''}"
											style:top="{bookingTop(b)}px"
											style:height="{bookingHeight(b)}px"
											draggable={canDrag}
											ondragstart={(e) => handleBookingDragStart(e, b)}
											ondragend={handleBookingDragEnd}
											onclick={(e) => {
												e.stopPropagation();
												selectBooking(b);
											}}
										>
											<span class="block font-medium">
												{formatLocalTime(b.startsAt, schedule.timezone)}–{formatLocalTime(
													b.endsAt,
													schedule.timezone
												)}
											</span>
											<span class="block truncate text-muted-foreground">{b.opType}</span>
										</button>
									{/each}
								</div>
							</div>
						</article>
					{/each}
				</div>
			</div>

			{#if scheduleLoading}
				<p class="text-xs text-muted-foreground">Refreshing…</p>
			{/if}
		{:else if view === 'week' && weekSchedule}
			{@const ws = weekSchedule}
			<!--
				Week-view keeps the horizontal-scroll layout — 7 day columns
				don't stack usefully on a phone. `snap-x snap-mandatory` on the
				scroller + `snap-start` on each day column gives the scroll a
				per-day snap so swiping feels deliberate instead of slippery.
			-->
			<div class="snap-x snap-mandatory overflow-x-auto rounded-2xl border">
				<div class="flex min-w-max">
					<!-- Time gutter (same as day-view) -->
					<div class="w-16 shrink-0 border-r bg-muted/40">
						<div class="flex h-12 items-center justify-center border-b border-border text-xs">
							{ws.operatingRoom.code}
						</div>
						<div class="relative" style:height="{totalPx}px">
							{#each hourTicks as t (t.minute)}
								<div
									class="absolute -translate-y-1/2 px-2 text-right text-xs text-muted-foreground"
									style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"
								>
									{t.label}
								</div>
							{/each}
						</div>
					</div>

					<!-- Per-day columns (always 7) -->
					{#each ws.days as day (day.date)}
						<div
							class="w-44 shrink-0 snap-start border-r border-border last:border-r-0"
							data-testid="week-col-{day.date}"
						>
							<div
								class="flex h-12 flex-col items-center justify-center border-b border-border px-2"
							>
								<span class="text-xs text-muted-foreground">
									{new Date(`${day.date}T00:00:00Z`).toLocaleDateString(undefined, {
										weekday: 'short',
										timeZone: 'UTC'
									})}
								</span>
								<span class="text-sm font-medium">{day.date.slice(5)}</span>
							</div>
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="relative w-full cursor-cell hover:bg-muted/30"
								style:height="{totalPx}px"
								onclick={(e) => openCreate(ws.operatingRoom.id, day.date, e)}
								aria-label="Create booking on {day.date} in {ws.operatingRoom.code}"
							>
								{#each hourTicks as t (t.minute)}
									<div
										class="pointer-events-none absolute right-0 left-0 border-t border-border/50"
										style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"
									></div>
								{/each}
								{#each day.bookings as b (b.id)}
									<button
										type="button"
										class="absolute right-1 left-1 cursor-pointer rounded border px-2 py-1 text-left text-xs shadow-sm {statusColor(
											b.status
										)} {selectedBooking?.id === b.id ? 'ring-2 ring-primary' : ''}"
										style:top="{bookingTop(b)}px"
										style:height="{bookingHeight(b)}px"
										onclick={(e) => {
											e.stopPropagation();
											selectBooking(b);
										}}
									>
										<span class="block font-medium">
											{formatLocalTime(b.startsAt, ws.timezone)}–{formatLocalTime(
												b.endsAt,
												ws.timezone
											)}
										</span>
										<span class="block truncate text-muted-foreground">{b.opType}</span>
									</button>
								{/each}
							</div>
						</div>
					{/each}
				</div>
			</div>

			{#if scheduleLoading}
				<p class="text-xs text-muted-foreground">Refreshing…</p>
			{/if}
		{/if}
	</div>
</main>

{#if clinic}
	<BookingFormDialog
		open={dialogOpen}
		mode={dialogMode}
		timezone={clinic.timezone}
		{rooms}
		{surgeons}
		existing={dialogExisting}
		prefill={dialogPrefill}
		defaultMinutes={clinic.defaultBookingMinutes}
		onClose={onDialogClose}
		onSaved={onDialogSaved}
	/>
	{#if selectedBooking}
		<BookingSidePanel
			booking={selectedBooking}
			timezone={clinic.timezone}
			{rooms}
			{surgeons}
			onClose={closePanel}
			onEdit={startEditFromPanel}
			onChanged={onPanelChanged}
		/>
	{/if}
{/if}
