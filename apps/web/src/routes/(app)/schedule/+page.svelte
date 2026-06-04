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
	import ModeToggle from '$lib/components/ModeToggle.svelte';
	import PlusIcon from '@lucide/svelte/icons/plus';
	import ChevronLeftIcon from '@lucide/svelte/icons/chevron-left';
	import ChevronRightIcon from '@lucide/svelte/icons/chevron-right';

	const PIXELS_PER_MINUTE = 1.2; // 60 min ≈ 72px row height
	const SLOT_MINUTES = 30;
	const HEADER_PX = 56; // sticky column-header row height (matches CSS)
	const COMPACT_PX = 70; // blocks shorter than this hide their footer

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
	// targets) but a small text hint floats over it.
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

	// ---- Live "now" indicator -------------------------------------------
	// Ticks once a minute; drives the dashed now-line + its time tag. Only
	// shown when the visible day is *today* in the clinic zone.
	let nowMs = $state(Date.now());
	onMount(() => {
		const id = setInterval(() => (nowMs = Date.now()), 60_000);
		return () => clearInterval(id);
	});

	const nowMin = $derived(tz ? localMinutesOfDay(new Date(nowMs).toISOString(), tz) : 0);
	const today = $derived(clinic ? todayInZone(clinic.timezone) : '');
	const showNow = $derived(!!tz && nowMin >= startMin && nowMin <= endMin);
	const nowLabel = $derived(
		`${Math.floor(nowMin / 60)
			.toString()
			.padStart(2, '0')}:${(nowMin % 60).toString().padStart(2, '0')}`
	);
	// Crumb date: long, readable; week shows the range start.
	const crumbDate = $derived(view === 'week' ? `Week of ${weekFrom}` : date);

	// ---- Surgeon / status display helpers --------------------------------
	function surgeonName(id: string): string {
		return surgeons.find((s) => s.id === id)?.displayName ?? 'Unknown';
	}
	function initialsOf(name: string): string {
		return (
			name
				.split(/\s+/)
				.filter(Boolean)
				.slice(0, 2)
				.map((p) => p[0]?.toUpperCase() ?? '')
				.join('') || '?'
		);
	}
	function statusLabel(s: BookingDto['status']): string {
		return s.replace('_', ' ');
	}

	// Toolbar "New booking" — opens create prefilled with a sensible default
	// slot (now, snapped) on the visible OR/day.
	function openCreateCta() {
		if (!clinic || rooms.length === 0) return;
		const base = showNow ? Math.round(nowMin / SLOT_MINUTES) * SLOT_MINUTES : startMin;
		const time = `${Math.floor(base / 60)
			.toString()
			.padStart(2, '0')}:${(base % 60).toString().padStart(2, '0')}`;
		dialogPrefill = {
			date: view === 'week' ? weekFrom : date,
			time,
			operatingRoomId: view === 'week' ? (weekOrId ?? rooms[0].id) : rooms[0].id
		};
		dialogExisting = undefined;
		dialogMode = 'create';
		dialogOpen = true;
	}
</script>

<svelte:head>
	<title>Schedule · Kliniq</title>
</svelte:head>

<div class="contents">
	<div class="mx-auto w-full max-w-7xl space-y-4">
		<header class="flex flex-wrap items-start justify-between gap-3">
			<div class="space-y-1">
				<h1 class="t-h1">Schedule</h1>
				{#if clinic}
					<p class="t-mono text-xs text-muted-foreground">
						{clinic.name} · {crumbDate} · {clinic.timezone}
					</p>
				{/if}
			</div>
			<div class="flex flex-wrap items-center gap-2">
				<!-- Day / Week segmented control -->
				<div class="inline-flex rounded-[10px] bg-muted p-[3px]" role="group" aria-label="View">
					<button
						type="button"
						class="rounded-[7px] px-3.5 py-1.5 text-sm font-medium transition-colors {view === 'day'
							? 'bg-card text-foreground shadow-sm'
							: 'text-muted-foreground hover:text-foreground'}"
						onclick={() => setView('day')}
						aria-pressed={view === 'day'}
					>
						Day
					</button>
					<button
						type="button"
						class="rounded-[7px] px-3.5 py-1.5 text-sm font-medium transition-colors {view ===
						'week'
							? 'bg-card text-foreground shadow-sm'
							: 'text-muted-foreground hover:text-foreground'}"
						onclick={() => setView('week')}
						aria-pressed={view === 'week'}
					>
						Week
					</button>
				</div>

				{#if view === 'week'}
					<select
						bind:value={weekOrId}
						onchange={loadSchedule}
						disabled={loading || rooms.length === 0}
						class="h-9 rounded-[10px] border border-input bg-card px-3 text-sm font-medium"
					>
						{#each rooms as r (r.id)}
							<option value={r.id}>{r.code} · {r.name}</option>
						{/each}
					</select>
				{/if}

				<!-- Prev / date / next -->
				<Button
					variant="outline"
					size="icon-sm"
					onclick={() => (view === 'week' ? shiftWeek(-7) : shiftDate(-1))}
					disabled={loading}
					aria-label="Previous"
				>
					<ChevronLeftIcon class="size-4" />
				</Button>
				{#if view === 'day'}
					<Input
						type="date"
						bind:value={date}
						onchange={dateChanged}
						disabled={loading}
						class="h-9 w-36 sm:w-44"
					/>
				{:else}
					<Input
						type="date"
						bind:value={weekFrom}
						onchange={loadSchedule}
						disabled={loading}
						class="h-9 w-36 sm:w-44"
					/>
				{/if}
				<Button
					variant="outline"
					size="icon-sm"
					onclick={() => (view === 'week' ? shiftWeek(7) : shiftDate(1))}
					disabled={loading}
					aria-label="Next"
				>
					<ChevronRightIcon class="size-4" />
				</Button>
				<Button
					variant="outline"
					size="sm"
					onclick={() => {
						if (!clinic) return;
						if (view === 'week') weekFrom = todayInZone(clinic.timezone);
						else date = todayInZone(clinic.timezone);
						void loadSchedule();
					}}
					disabled={loading}
				>
					{view === 'week' ? 'This week' : 'Today'}
				</Button>

				<ModeToggle />

				<Button class="cta-gradient gap-1.5" onclick={openCreateCta} disabled={loading}>
					<PlusIcon class="size-4" /> New booking
				</Button>
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
			{@const ors = schedule.operatingRooms}
			<!--
				Day-view: a single board — a CSS grid of [time-gutter | OR columns].
				The gutter (sticky-left) and the OR headers (sticky-top) stay pinned
				while the board scrolls. Columns are minmax(260px,1fr): ~3 share the
				width, more scroll horizontally. Booking blocks are absolutely placed
				inside each slot column by time; a dashed "now" line spans all columns.
			-->
			<div class="relative">
				{#if dayBookingCount === 0}
					<!-- Grid still renders (slots stay clickable / drop targets); this
						floats over it as a hint and is click-through. -->
					<div class="pointer-events-none absolute inset-x-0 top-24 z-20 flex justify-center">
						<div class="glass rounded-2xl px-6 py-4 text-center">
							<h3 class="text-sm font-semibold">No bookings on {date}</h3>
							<p class="mt-1 text-xs text-muted-foreground">
								Click an empty slot to book a procedure.
							</p>
						</div>
					</div>
				{/if}
				<div class="board glass overflow-hidden rounded-2xl">
					<div class="board-scroll">
						<div
							class="sched-grid"
							style="grid-template-columns: 56px repeat({ors.length}, minmax(260px, 1fr))"
						>
							<div class="axis-h"></div>
							{#each ors as or (or.id)}
								<div class="col-h">
									<span class="t-mono text-[11px] text-muted-foreground">{or.code}</span>
									<span class="truncate text-[13.5px] font-semibold">{or.name}</span>
									{#if or.status !== 'ACTIVE'}<span class="or-pill">{or.status}</span>{/if}
								</div>
							{/each}

							<div class="axis" style:height="{totalPx}px">
								{#each hourTicks as t (t.minute)}
									<span class="tk" style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px">
										{t.label}
									</span>
								{/each}
							</div>
							{#each ors as or (or.id)}
								<!-- svelte-ignore a11y_click_events_have_key_events -->
								<!-- svelte-ignore a11y_no_static_element_interactions -->
								<div
									class="slots"
									class:dragover={dragOverOrId === or.id}
									style:height="{totalPx}px"
									onclick={(e) => openCreate(or.id, date, e)}
									ondragover={(e) => handleSlotDragOver(e, or.id)}
									ondragleave={() => handleSlotDragLeave(or.id)}
									ondrop={(e) => handleSlotDrop(e, or.id)}
									aria-label="Create booking in {or.code}"
								>
									{#each hourTicks as t (t.minute)}
										<div
											class="line"
											style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"
										></div>
									{/each}
									{#each or.bookings as b (b.id)}
										{@const canDrag = b.status === 'SCHEDULED' || b.status === 'IN_PROGRESS'}
										{@const compact = bookingHeight(b) < COMPACT_PX}
										<button
											type="button"
											class="bk"
											class:SCHEDULED={b.status === 'SCHEDULED'}
											class:IN_PROGRESS={b.status === 'IN_PROGRESS'}
											class:COMPLETED={b.status === 'COMPLETED'}
											class:CANCELLED={b.status === 'CANCELLED'}
											class:compact
											class:sel={selectedBooking?.id === b.id}
											class:drag={draggingId === b.id}
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
											<span class="accent"></span>
											<span class="tm">
												{formatLocalTime(b.startsAt, schedule.timezone)}–{formatLocalTime(
													b.endsAt,
													schedule.timezone
												)}
											</span>
											<span class="op">{b.opType}</span>
											{#if !compact}
												<span class="ft">
													<span class="mav">{initialsOf(surgeonName(b.surgeonId))}</span>
													<span class="who">{surgeonName(b.surgeonId)}</span>
													<span class="stp">{statusLabel(b.status)}</span>
												</span>
											{/if}
										</button>
									{/each}
								</div>
							{/each}

							{#if showNow && date === today}
								<div
									class="now"
									style:top="{HEADER_PX + (nowMin - startMin) * PIXELS_PER_MINUTE}px"
								>
									<span class="now-tag">{nowLabel}</span>
								</div>
							{/if}
						</div>
					</div>
				</div>
			</div>

			{#if scheduleLoading}
				<p class="text-xs text-muted-foreground">Refreshing…</p>
			{/if}
		{:else if view === 'week' && weekSchedule}
			{@const ws = weekSchedule}
			<!--
				Week-view: same board, 7 day-columns for the selected OR. The
				"today" column is tinted and carries its own per-day now-line.
			-->
			<div class="board glass overflow-hidden rounded-2xl">
				<div class="board-scroll">
					<div
						class="sched-grid week"
						style="grid-template-columns: 56px repeat(7, minmax(150px, 1fr))"
					>
						<div class="axis-h">
							<span class="t-mono text-[11px] text-muted-foreground">{ws.operatingRoom.code}</span>
						</div>
						{#each ws.days as day (day.date)}
							<div class="col-h wk" class:today={day.date === today}>
								<span class="c">
									{new Date(`${day.date}T00:00:00Z`).toLocaleDateString(undefined, {
										weekday: 'short',
										timeZone: 'UTC'
									})}
								</span>
								<span class="n">{day.date.slice(5)}</span>
							</div>
						{/each}

						<div class="axis" style:height="{totalPx}px">
							{#each hourTicks as t (t.minute)}
								<span class="tk" style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px">
									{t.label}
								</span>
							{/each}
						</div>
						{#each ws.days as day (day.date)}
							{@const isToday = day.date === today}
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="slots"
								class:today={isToday}
								style:height="{totalPx}px"
								data-testid="week-col-{day.date}"
								onclick={(e) => openCreate(ws.operatingRoom.id, day.date, e)}
								aria-label="Create booking on {day.date} in {ws.operatingRoom.code}"
							>
								{#each hourTicks as t (t.minute)}
									<div class="line" style:top="{(t.minute - startMin) * PIXELS_PER_MINUTE}px"></div>
								{/each}
								{#if isToday && showNow}
									<div
										class="now-local"
										style:top="{(nowMin - startMin) * PIXELS_PER_MINUTE}px"
									></div>
								{/if}
								{#each day.bookings as b (b.id)}
									{@const compact = bookingHeight(b) < COMPACT_PX}
									<button
										type="button"
										class="bk"
										class:SCHEDULED={b.status === 'SCHEDULED'}
										class:IN_PROGRESS={b.status === 'IN_PROGRESS'}
										class:COMPLETED={b.status === 'COMPLETED'}
										class:CANCELLED={b.status === 'CANCELLED'}
										class:compact
										class:sel={selectedBooking?.id === b.id}
										style:top="{bookingTop(b)}px"
										style:height="{bookingHeight(b)}px"
										onclick={(e) => {
											e.stopPropagation();
											selectBooking(b);
										}}
									>
										<span class="accent"></span>
										<span class="tm">
											{formatLocalTime(b.startsAt, ws.timezone)}–{formatLocalTime(
												b.endsAt,
												ws.timezone
											)}
										</span>
										<span class="op">{b.opType}</span>
									</button>
								{/each}
							</div>
						{/each}
					</div>
				</div>
			</div>

			{#if scheduleLoading}
				<p class="text-xs text-muted-foreground">Refreshing…</p>
			{/if}
		{/if}
	</div>
</div>

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

<style>
	/* ===========================================================================
	 * Schedule board — single sticky grid: [time-gutter | OR columns].
	 * Faithful port of the redesign's Luminous/Midnight board, expressed against
	 * the app's own semantic tokens so light/dark flip automatically.
	 * ======================================================================== */
	.board {
		box-shadow: var(--shadow-lg);
	}
	.board-scroll {
		max-height: min(560px, calc(100vh - 290px));
		overflow: auto;
		overscroll-behavior: contain;
	}
	.board-scroll::-webkit-scrollbar {
		width: 9px;
		height: 9px;
	}
	.board-scroll::-webkit-scrollbar-thumb {
		background: color-mix(in oklch, var(--foreground) 16%, transparent);
		border-radius: 99px;
		border: 3px solid transparent;
		background-clip: padding-box;
	}

	.sched-grid {
		display: grid;
		position: relative;
	}

	/* ---- sticky headers / gutter ---- */
	.axis-h,
	.col-h {
		height: 56px;
		position: sticky;
		top: 0;
		z-index: 4;
		display: flex;
		align-items: center;
		background: color-mix(in oklch, var(--background) 90%, transparent);
		backdrop-filter: blur(8px);
		border-bottom: 1px solid var(--border);
	}
	.axis-h {
		left: 0;
		z-index: 5;
		padding-left: 8px;
	}
	.col-h {
		flex-direction: column;
		justify-content: center;
		gap: 1px;
		padding: 0 12px;
		border-left: 1px solid color-mix(in oklch, var(--border) 55%, transparent);
	}
	.col-h.wk {
		align-items: center;
		gap: 2px;
	}
	.col-h.wk .c {
		font-size: 10.5px;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--muted-foreground);
	}
	.col-h.wk .n {
		font-size: 15px;
		font-weight: 700;
	}
	.col-h.today {
		background: color-mix(in oklch, var(--primary) 9%, var(--background));
	}
	.col-h.today .n {
		color: var(--primary);
	}
	.or-pill {
		margin-top: 2px;
		padding: 1px 6px;
		border-radius: 999px;
		font-size: 9px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
		color: var(--muted-foreground);
		background: var(--muted);
	}

	.axis {
		position: sticky;
		left: 0;
		z-index: 3;
		background: color-mix(in oklch, var(--background) 90%, transparent);
		backdrop-filter: blur(8px);
	}
	.axis .tk {
		position: absolute;
		right: 8px;
		transform: translateY(-50%);
		font-family: var(--font-mono);
		font-size: 10.5px;
		color: var(--muted-foreground);
	}

	/* ---- slot columns ---- */
	.slots {
		position: relative;
		cursor: cell;
		border-left: 1px solid color-mix(in oklch, var(--border) 55%, transparent);
	}
	.slots:hover {
		background: color-mix(in oklch, var(--foreground) 2%, transparent);
	}
	.slots.today {
		background: color-mix(in oklch, var(--primary) 5%, transparent);
	}
	.slots.dragover {
		box-shadow: inset 0 0 0 2px color-mix(in oklch, var(--primary) 45%, transparent);
	}
	.line {
		position: absolute;
		left: 0;
		right: 0;
		pointer-events: none;
		border-top: 1px solid color-mix(in oklch, var(--border) 50%, transparent);
	}

	/* ---- now indicator ---- */
	.now {
		position: absolute;
		left: 56px;
		right: 0;
		height: 0;
		z-index: 2;
		pointer-events: none;
		border-top: 2px dashed var(--primary);
	}
	.now::before {
		content: '';
		position: absolute;
		left: 0;
		top: -4px;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--primary);
		box-shadow: 0 0 0 3px color-mix(in oklch, var(--primary) 22%, transparent);
	}
	.now-tag {
		position: absolute;
		left: 8px;
		top: 50%;
		transform: translateY(-50%);
		padding: 2px 6px;
		border-radius: 6px;
		font-family: var(--font-mono);
		font-size: 10px;
		font-weight: 600;
		line-height: 1.3;
		color: var(--primary-foreground);
		background: var(--primary);
	}
	.now-local {
		position: absolute;
		left: 0;
		right: 0;
		height: 0;
		z-index: 2;
		pointer-events: none;
		border-top: 2px dashed var(--primary);
	}
	.now-local::before {
		content: '';
		position: absolute;
		left: -3px;
		top: -4px;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--primary);
		box-shadow: 0 0 0 3px color-mix(in oklch, var(--primary) 22%, transparent);
	}
	:global(.dark) .now,
	:global(.dark) .now-local {
		filter: drop-shadow(0 0 6px color-mix(in oklch, var(--primary) 70%, transparent));
	}

	/* ---- booking blocks (Luminous) ---- */
	.bk {
		position: absolute;
		left: 7px;
		right: 7px;
		z-index: 1;
		padding: 8px 11px;
		border-radius: 12px;
		overflow: hidden;
		text-align: left;
		cursor: pointer;
		border: 1px solid color-mix(in oklch, var(--foreground) 5%, transparent);
		transition:
			transform 0.14s var(--ease),
			box-shadow 0.14s var(--ease);
	}
	.bk:hover {
		transform: translateY(-1px);
	}
	.bk.compact {
		display: flex;
		flex-direction: column;
		justify-content: center;
		padding: 5px 10px;
	}
	.bk .accent {
		position: absolute;
		left: 0;
		top: 0;
		bottom: 0;
		width: 4px;
	}
	.bk .tm {
		display: block;
		font-family: var(--font-mono);
		font-size: 10.5px;
		font-weight: 500;
		color: var(--muted-foreground);
	}
	.bk .op {
		display: block;
		margin-top: 1px;
		font-size: 13px;
		font-weight: 600;
		letter-spacing: -0.01em;
		color: var(--foreground);
	}
	.bk.compact .op {
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bk .ft {
		display: flex;
		align-items: center;
		gap: 6px;
		margin-top: 7px;
	}
	.bk .mav {
		display: flex;
		flex-shrink: 0;
		align-items: center;
		justify-content: center;
		width: 19px;
		height: 19px;
		border-radius: 50%;
		font-size: 9px;
		font-weight: 600;
		color: var(--muted-foreground);
		background: color-mix(in oklch, var(--foreground) 12%, transparent);
	}
	.bk .who {
		font-size: 11px;
		color: var(--muted-foreground);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}
	.bk .stp {
		margin-left: auto;
		flex-shrink: 0;
		padding: 2px 7px;
		border-radius: 999px;
		font-size: 9.5px;
		font-weight: 600;
		letter-spacing: 0.04em;
		text-transform: uppercase;
	}
	.bk.sel {
		box-shadow: 0 0 0 2px var(--primary);
	}
	.bk.drag {
		opacity: 0.5;
	}

	.bk.SCHEDULED {
		background: linear-gradient(
			135deg,
			color-mix(in oklch, var(--primary) 14%, var(--card)),
			var(--card)
		);
		box-shadow: 0 6px 16px -10px color-mix(in oklch, var(--foreground) 40%, transparent);
	}
	.bk.SCHEDULED .accent {
		background: var(--primary);
	}
	.bk.SCHEDULED .stp {
		background: color-mix(in oklch, var(--primary) 18%, transparent);
		color: color-mix(in oklch, var(--primary) 80%, black);
	}
	.bk.IN_PROGRESS {
		background: linear-gradient(
			135deg,
			color-mix(in oklch, var(--status-progress) 16%, var(--card)),
			var(--card)
		);
		box-shadow: 0 6px 16px -10px color-mix(in oklch, var(--status-progress) 40%, transparent);
	}
	.bk.IN_PROGRESS .accent {
		background: var(--status-progress);
	}
	.bk.IN_PROGRESS .stp {
		background: color-mix(in oklch, var(--status-progress) 22%, transparent);
		color: color-mix(in oklch, var(--status-progress) 70%, black);
	}
	.bk.COMPLETED {
		background: linear-gradient(135deg, var(--muted), var(--card));
	}
	.bk.COMPLETED .accent {
		background: var(--muted-foreground);
	}
	.bk.COMPLETED .op {
		color: var(--muted-foreground);
	}
	.bk.COMPLETED .stp {
		background: var(--muted);
		color: var(--muted-foreground);
	}
	.bk.CANCELLED {
		background: var(--muted);
	}
	.bk.CANCELLED .accent {
		background: color-mix(in oklch, var(--muted-foreground) 55%, transparent);
	}
	.bk.CANCELLED .op {
		color: var(--muted-foreground);
		text-decoration: line-through;
	}
	.bk.CANCELLED .stp {
		background: var(--muted);
		color: var(--muted-foreground);
	}

	/* ---- booking blocks (Midnight override) ---- */
	:global(.dark) .bk {
		border-color: transparent;
		backdrop-filter: blur(4px);
	}
	:global(.dark) .bk .accent {
		display: none;
	}
	:global(.dark) .bk .mav {
		background: rgba(255, 255, 255, 0.12);
		color: var(--foreground);
	}
	:global(.dark) .bk.SCHEDULED {
		background: color-mix(in oklch, var(--primary) 14%, transparent);
		border-color: color-mix(in oklch, var(--primary) 50%, transparent);
		box-shadow: 0 0 24px -6px color-mix(in oklch, var(--primary) 55%, transparent);
	}
	:global(.dark) .bk.SCHEDULED .stp {
		background: color-mix(in oklch, var(--primary) 22%, transparent);
		color: color-mix(in oklch, var(--primary) 70%, white);
	}
	:global(.dark) .bk.IN_PROGRESS {
		background: color-mix(in oklch, var(--status-progress) 16%, transparent);
		border-color: color-mix(in oklch, var(--status-progress) 55%, transparent);
		box-shadow: 0 0 26px -6px color-mix(in oklch, var(--status-progress) 55%, transparent);
	}
	:global(.dark) .bk.IN_PROGRESS .stp {
		background: color-mix(in oklch, var(--status-progress) 22%, transparent);
		color: color-mix(in oklch, var(--status-progress) 75%, white);
	}
	:global(.dark) .bk.COMPLETED {
		background: rgba(148, 163, 184, 0.12);
		border-color: rgba(148, 163, 184, 0.32);
	}
	:global(.dark) .bk.CANCELLED {
		background: rgba(148, 163, 184, 0.1);
		border-color: rgba(148, 163, 184, 0.26);
	}
	:global(.dark) .bk.sel {
		box-shadow:
			0 0 0 2px var(--primary),
			0 0 24px -4px color-mix(in oklch, var(--primary) 70%, transparent);
	}
</style>
