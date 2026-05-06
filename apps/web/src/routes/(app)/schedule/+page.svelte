<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { ApiError_ } from '$lib/auth/api';
	import { scheduleApi, type ScheduleDto } from '$lib/api/schedule';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import { operatingRoomsApi, type OperatingRoomDto } from '$lib/api/operatingRooms';
	import { usersApi, type SurgeonSummaryDto } from '$lib/api/users';
	import type { BookingDto } from '$lib/api/bookings';
	import {
		addDays,
		formatLocalTime,
		localMinutesOfDay,
		timeStringToMinutes,
		todayInZone
	} from '$lib/util/datetime';
	import BookingFormDialog from '$lib/components/bookings/BookingFormDialog.svelte';
	import BookingSidePanel from '$lib/components/bookings/BookingSidePanel.svelte';

	const PIXELS_PER_MINUTE = 1.2; // 60 min ≈ 72px row height
	const SLOT_MINUTES = 30;

	let clinic = $state<ClinicSettingsDto | null>(null);
	let rooms = $state<readonly OperatingRoomDto[]>([]);
	let surgeons = $state<readonly SurgeonSummaryDto[]>([]);
	let schedule = $state<ScheduleDto | null>(null);

	let date = $state(''); // YYYY-MM-DD; populated once we know the clinic zone
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

	onMount(async () => {
		try {
			[clinic, rooms, surgeons] = await Promise.all([
				clinicApi.get(),
				operatingRoomsApi.list(),
				usersApi.listSurgeons()
			]);
			date = todayInZone(clinic.timezone);
			await loadSchedule();
		} catch (e) {
			bootError = describe(e);
		} finally {
			loading = false;
		}
	});

	async function loadSchedule() {
		if (!clinic) return;
		scheduleLoading = true;
		try {
			schedule = await scheduleApi.day(date);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			scheduleLoading = false;
		}
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

	function bookingTop(b: BookingDto): number {
		if (!schedule) return 0;
		const m = localMinutesOfDay(b.startsAt, schedule.timezone);
		return Math.max(0, m - startMin) * PIXELS_PER_MINUTE;
	}

	function bookingHeight(b: BookingDto): number {
		if (!schedule) return 0;
		const s = localMinutesOfDay(b.startsAt, schedule.timezone);
		const e = localMinutesOfDay(b.endsAt, schedule.timezone);
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

	// ---- Click handling --------------------------------------------------
	function openCreate(roomId: string, event: MouseEvent) {
		const target = event.currentTarget as HTMLElement;
		const rect = target.getBoundingClientRect();
		const offsetMin = Math.round((event.clientY - rect.top) / PIXELS_PER_MINUTE);
		// Snap to the SLOT_MINUTES grid.
		const snapped = Math.max(0, Math.floor(offsetMin / SLOT_MINUTES) * SLOT_MINUTES);
		const m = startMin + snapped;
		const time = `${Math.floor(m / 60)
			.toString()
			.padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}`;
		dialogPrefill = { date, time, operatingRoomId: roomId };
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

<main class="min-h-screen bg-background p-6">
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
				<Button variant="outline" size="sm" onclick={() => shiftDate(-1)} disabled={loading}>
					← Prev
				</Button>
				<Input
					type="date"
					bind:value={date}
					onchange={dateChanged}
					disabled={loading}
					class="w-44"
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
		{:else if !schedule || schedule.operatingRooms.length === 0}
			<div class="rounded-2xl border p-8 text-center">
				<p class="text-sm text-muted-foreground">
					No operating rooms to show. <a href="/operating-rooms" class="underline">Add one</a> to start
					scheduling.
				</p>
			</div>
		{:else}
			<div class="overflow-x-auto rounded-2xl border">
				<div class="flex min-w-max">
					<!-- Time gutter -->
					<div class="w-16 shrink-0 border-r bg-muted/40">
						<div class="flex h-12 items-center justify-center border-b border-border text-xs">
							Time
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

					<!-- Per-OR columns -->
					{#each schedule.operatingRooms as or (or.id)}
						<div class="w-56 shrink-0 border-r border-border last:border-r-0">
							<div
								class="flex h-12 flex-col items-center justify-center border-b border-border px-2"
							>
								<span class="font-mono text-xs text-muted-foreground">{or.code}</span>
								<span class="truncate text-sm font-medium">{or.name}</span>
								{#if or.status !== 'ACTIVE'}
									<span class="rounded bg-muted px-1 text-[10px] text-muted-foreground uppercase">
										{or.status}
									</span>
								{/if}
							</div>
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div
								class="relative w-full cursor-cell hover:bg-muted/30"
								style:height="{totalPx}px"
								onclick={(e) => openCreate(or.id, e)}
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
