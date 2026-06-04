<script lang="ts">
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import { ApiError_ } from '$lib/auth/api';
	import { bookingsApi, type BookingDto } from '$lib/api/bookings';
	import type { OperatingRoomDto } from '$lib/api/operatingRooms';
	import type { SurgeonSummaryDto } from '$lib/api/users';
	import { formatLocalTime, isoToLocalParts } from '$lib/util/datetime';

	interface Props {
		booking: BookingDto;
		timezone: string;
		rooms: readonly OperatingRoomDto[];
		surgeons: readonly SurgeonSummaryDto[];
		onClose: () => void;
		onEdit: () => void;
		/** Called with the fresh booking after a lifecycle transition. */
		onChanged: (b: BookingDto) => void;
	}

	let { booking, timezone, rooms, surgeons, onClose, onEdit, onChanged }: Props = $props();

	let acting = $state<null | 'cancel' | 'start' | 'complete'>(null);

	const room = $derived(rooms.find((r) => r.id === booking.operatingRoomId));
	const surgeon = $derived(surgeons.find((s) => s.id === booking.surgeonId));
	const day = $derived(isoToLocalParts(booking.startsAt, timezone).date);

	// FSM gates — match backend BookingStatus.canTransitionTo.
	const canEdit = $derived(booking.status === 'SCHEDULED' || booking.status === 'IN_PROGRESS');
	const canStart = $derived(booking.status === 'SCHEDULED');
	const canComplete = $derived(booking.status === 'IN_PROGRESS');
	const canCancel = $derived(booking.status === 'SCHEDULED' || booking.status === 'IN_PROGRESS');

	async function transition(action: 'cancel' | 'start' | 'complete') {
		if (action === 'cancel') {
			const ok = confirm(
				`Cancel this booking? This frees the slot for new bookings and writes an audit row.`
			);
			if (!ok) return;
		}
		acting = action;
		try {
			let saved: BookingDto;
			if (action === 'cancel') saved = await bookingsApi.cancel(booking.id);
			else if (action === 'start') saved = await bookingsApi.start(booking.id);
			else saved = await bookingsApi.complete(booking.id);
			onChanged(saved);
			toast.success(
				action === 'cancel'
					? 'Booking cancelled.'
					: action === 'start'
						? 'Booking started.'
						: 'Booking completed.'
			);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			acting = null;
		}
	}

	function describe(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'ILLEGAL_TRANSITION':
				return 'That action is not allowed in the current state.';
			case 'NOT_FOUND':
				return 'Booking not found — refresh the schedule.';
			default:
				return e.payload.message || 'Server error. Please try again.';
		}
	}

	function statusBadgeClass(status: BookingDto['status']): string {
		switch (status) {
			case 'SCHEDULED':
				return 'bg-primary/15 text-primary border-primary/30';
			case 'IN_PROGRESS':
				return 'bg-amber-500/20 text-amber-700 dark:text-amber-300 border-amber-500/40';
			case 'COMPLETED':
				return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30';
			case 'CANCELLED':
				return 'bg-muted text-muted-foreground border-border';
		}
	}
</script>

<div class="scrim" onclick={onClose} aria-hidden="true"></div>
<aside class="panel" aria-label="Booking details">
	<header class="mb-5 flex items-start justify-between gap-3">
		<div class="space-y-1.5">
			<h2 class="text-[17px] font-semibold">Booking</h2>
			<span
				class="inline-flex items-center rounded-full border px-2 py-0.5 text-xs tracking-wide uppercase {statusBadgeClass(
					booking.status
				)}"
			>
				{booking.status.replace('_', ' ').toLowerCase()}
			</span>
		</div>
		<Button variant="outline" size="sm" onclick={onClose} aria-label="Close panel">Close</Button>
	</header>

	<!-- Surgeon hero -->
	<div
		class="mb-5 flex items-center gap-3 rounded-2xl border border-border bg-foreground/[0.03] p-3"
	>
		<div
			class="grid size-11 shrink-0 place-items-center rounded-full bg-gradient-to-br from-primary/70 to-primary text-sm font-bold text-primary-foreground"
		>
			{#if surgeon}{surgeon.displayName
					.split(/\s+/)
					.filter(Boolean)
					.slice(0, 2)
					.map((p) => p[0]?.toUpperCase() ?? '')
					.join('')}{:else}?{/if}
		</div>
		<div class="min-w-0">
			<div class="truncate text-sm font-semibold">
				{surgeon ? surgeon.displayName : 'Unknown surgeon'}
			</div>
			{#if surgeon}
				<div class="text-xs text-muted-foreground capitalize">
					{surgeon.specialty.toLowerCase()}
				</div>
			{/if}
		</div>
	</div>

	<dl class="grid grid-cols-[max-content_1fr] gap-x-3.5 gap-y-2.5 text-sm">
		<dt class="text-muted-foreground">Operating room</dt>
		<dd class="font-medium">
			{#if room}
				<span class="t-mono text-xs text-muted-foreground">{room.code}</span> · {room.name}
			{:else}
				<span class="text-muted-foreground">unknown</span>
			{/if}
		</dd>

		<dt class="text-muted-foreground">When</dt>
		<dd class="font-medium">
			{day} ·
			<span class="t-mono"
				>{formatLocalTime(booking.startsAt, timezone)}–{formatLocalTime(
					booking.endsAt,
					timezone
				)}</span
			>
		</dd>

		<dt class="text-muted-foreground">Operation</dt>
		<dd class="font-medium">{booking.opType}</dd>

		<dt class="text-muted-foreground">Patient ref</dt>
		<dd class="t-mono text-xs">{booking.patientRef}</dd>

		{#if booking.notes}
			<dt class="text-muted-foreground">Notes</dt>
			<dd class="whitespace-pre-wrap">{booking.notes}</dd>
		{/if}
	</dl>

	<div class="mt-6 space-y-2">
		<div class="flex flex-wrap gap-2">
			{#if canEdit}
				<Button variant="outline" size="sm" onclick={onEdit} disabled={acting !== null}>
					Edit
				</Button>
			{/if}
			{#if canStart}
				<Button size="sm" onclick={() => transition('start')} disabled={acting !== null}>
					{acting === 'start' ? 'Starting…' : 'Start'}
				</Button>
			{/if}
			{#if canComplete}
				<Button size="sm" onclick={() => transition('complete')} disabled={acting !== null}>
					{acting === 'complete' ? 'Completing…' : 'Complete'}
				</Button>
			{/if}
			{#if canCancel}
				<Button
					variant="outline"
					size="sm"
					onclick={() => transition('cancel')}
					disabled={acting !== null}
				>
					{acting === 'cancel' ? 'Cancelling…' : 'Cancel'}
				</Button>
			{/if}
		</div>
		{#if !canEdit && !canStart && !canComplete && !canCancel}
			<p class="text-xs text-muted-foreground">
				This booking is {booking.status.toLowerCase()} — no actions left.
			</p>
		{/if}
	</div>
</aside>

<style>
	.scrim {
		position: fixed;
		inset: 0;
		z-index: 40;
		background: rgba(0, 0, 0, 0.34);
		animation: scrim-fade 0.2s ease;
	}
	.panel {
		position: fixed;
		inset-block: 0;
		right: 0;
		z-index: 50;
		width: 100%;
		max-width: 392px;
		padding: 24px;
		overflow-y: auto;
		background: var(--background);
		border-left: 1px solid var(--border);
		box-shadow: var(--shadow-2xl);
		animation: panel-in 0.22s cubic-bezier(0.2, 0.7, 0.3, 1);
	}
	@keyframes scrim-fade {
		from {
			opacity: 0;
		}
	}
	@keyframes panel-in {
		from {
			transform: translateX(20px);
			opacity: 0.4;
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.scrim,
		.panel {
			animation: none;
		}
	}
</style>
