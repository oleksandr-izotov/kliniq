<script lang="ts">
	import { tick } from 'svelte';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import {
		bookingsApi,
		type BookingDto,
		type CreateBookingRequest,
		type UpdateBookingRequest
	} from '$lib/api/bookings';
	import type { OperatingRoomDto } from '$lib/api/operatingRooms';
	import type { SurgeonSummaryDto } from '$lib/api/users';
	import { isoToLocalParts, localToIso } from '$lib/util/datetime';

	interface Props {
		open: boolean;
		mode: 'create' | 'edit';
		timezone: string;
		rooms: readonly OperatingRoomDto[];
		surgeons: readonly SurgeonSummaryDto[];
		/** Edit mode only — the booking being edited. */
		existing?: BookingDto;
		/** Create-mode prefill: an empty slot a user clicked. */
		prefill?: { date: string; time: string; operatingRoomId?: string };
		/** Default booking length in minutes (from clinic settings). */
		defaultMinutes: number;
		onClose: () => void;
		onSaved: (b: BookingDto) => void;
	}

	let {
		open,
		mode,
		timezone,
		rooms,
		surgeons,
		existing,
		prefill,
		defaultMinutes,
		onClose,
		onSaved
	}: Props = $props();

	let dlg: HTMLDialogElement | undefined = $state();

	// ---- Form state ------------------------------------------------------
	let operatingRoomId = $state('');
	let surgeonId = $state('');
	let date = $state('');
	let startTime = $state('');
	let endTime = $state('');
	let opType = $state('');
	let patientRef = $state('');
	let notes = $state('');

	let submitting = $state(false);
	let formError = $state<string | null>(null);
	let conflict = $state<{ message: string; occludingId: string | null } | null>(null);

	// ---- Lifecycle: sync <dialog> with `open` prop -----------------------
	$effect(() => {
		if (!dlg) return;
		if (open && !dlg.open) {
			hydrate();
			dlg.showModal();
		} else if (!open && dlg.open) {
			dlg.close();
		}
	});

	function hydrate() {
		formError = null;
		conflict = null;
		if (mode === 'edit' && existing) {
			operatingRoomId = existing.operatingRoomId;
			surgeonId = existing.surgeonId;
			const start = isoToLocalParts(existing.startsAt, timezone);
			const end = isoToLocalParts(existing.endsAt, timezone);
			date = start.date;
			startTime = start.time;
			endTime = end.time;
			opType = existing.opType;
			patientRef = existing.patientRef;
			notes = existing.notes ?? '';
		} else {
			operatingRoomId = prefill?.operatingRoomId ?? rooms[0]?.id ?? '';
			surgeonId = surgeons[0]?.id ?? '';
			date = prefill?.date ?? '';
			startTime = prefill?.time ?? '';
			endTime = startTime ? addMinutes(startTime, defaultMinutes) : '';
			opType = '';
			patientRef = '';
			notes = '';
		}
	}

	function addMinutes(time: string, minutes: number): string {
		const [h, m] = time.split(':').map(Number);
		const total = (h * 60 + m + minutes) % (24 * 60);
		return `${Math.floor(total / 60)
			.toString()
			.padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}`;
	}

	// When startTime changes in create mode, snap endTime to start + default.
	$effect(() => {
		if (mode === 'create' && startTime && !endTime) {
			endTime = addMinutes(startTime, defaultMinutes);
		}
	});

	function close() {
		onClose();
	}

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		conflict = null;

		if (!operatingRoomId) return (formError = 'Pick an operating room.');
		if (!surgeonId) return (formError = 'Pick a surgeon.');
		if (!date || !startTime || !endTime) return (formError = 'Fill in date and times.');
		if (startTime >= endTime) return (formError = 'End time must be after start time.');
		const trimmedOpType = opType.trim();
		if (!trimmedOpType) return (formError = 'Operation type is required.');
		const trimmedPatientRef = patientRef.trim();
		if (!/^P-\d{4}-\d{3,}$/.test(trimmedPatientRef)) {
			return (formError = 'Patient reference must look like P-YYYY-NNN.');
		}

		const startsAt = localToIso(date, startTime, timezone);
		const endsAt = localToIso(date, endTime, timezone);

		submitting = true;
		try {
			let saved: BookingDto;
			if (mode === 'edit' && existing) {
				const payload: UpdateBookingRequest = {
					operatingRoomId,
					surgeonId,
					startsAt,
					endsAt,
					opType: trimmedOpType,
					notes: notes.trim()
				};
				saved = await bookingsApi.update(existing.id, payload);
			} else {
				const payload: CreateBookingRequest = {
					operatingRoomId,
					surgeonId,
					startsAt,
					endsAt,
					opType: trimmedOpType,
					patientRef: trimmedPatientRef,
					notes: notes.trim() || undefined
				};
				saved = await bookingsApi.create(payload);
			}
			onSaved(saved);
			await tick();
			close();
		} catch (e) {
			handleError(e);
		} finally {
			submitting = false;
		}
	}

	function handleError(e: unknown) {
		if (!(e instanceof ApiError_)) {
			formError = 'Network error. Please try again.';
			return;
		}
		switch (e.payload.code) {
			case 'BOOKING_CONFLICT': {
				// The occludingBookingId field is on BookingConflictDetail, which
				// our generic ApiError_ doesn't know about — read it off payload
				// as a typed escape hatch.
				const detail = e.payload as unknown as { occludingBookingId?: string | null };
				conflict = {
					message: e.payload.message,
					occludingId: detail.occludingBookingId ?? null
				};
				return;
			}
			case 'OUTSIDE_WORKING_HOURS':
				formError =
					'This slot falls outside the clinic’s working hours. Adjust the times or update clinic settings.';
				return;
			case 'OR_INACTIVE':
				formError = 'That operating room is currently in maintenance or retired.';
				return;
			case 'OR_NOT_FOUND':
				formError = 'Operating room not found. Refresh the page and try again.';
				return;
			case 'SURGEON_NOT_FOUND':
				formError = 'Surgeon not found.';
				return;
			case 'NOT_A_SURGEON':
				formError = 'Selected user is not flagged as a surgeon.';
				return;
			case 'SURGEON_INACTIVE':
				formError = "Surgeon's account is disabled.";
				return;
			case 'BOOKING_TERMINAL':
				formError = e.payload.message;
				return;
			case 'VALIDATION_ERROR':
				formError = e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
				return;
			default:
				formError = e.payload.message || 'Server error. Please try again.';
		}
	}

	// Pressing Esc fires <dialog>'s native close event — propagate to parent.
	function onCancel() {
		close();
	}
</script>

<dialog
	bind:this={dlg}
	oncancel={onCancel}
	onclose={onCancel}
	class="h-full max-h-screen w-full rounded-none bg-background p-0 text-foreground shadow-xl backdrop:bg-black/40 sm:h-auto sm:max-h-[90vh] sm:max-w-xl sm:rounded-2xl"
>
	<form onsubmit={submit} class="space-y-4 p-6" novalidate>
		<header class="space-y-1">
			<h2 class="text-xl font-semibold">
				{mode === 'edit' ? 'Edit booking' : 'New booking'}
			</h2>
			<p class="text-sm text-muted-foreground">
				All times are in the clinic's local zone ({timezone}).
			</p>
		</header>

		<div class="grid gap-4 sm:grid-cols-2">
			<div class="space-y-2">
				<Label for="bf-or">Operating room</Label>
				<select
					id="bf-or"
					bind:value={operatingRoomId}
					disabled={submitting}
					class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
				>
					{#each rooms as r (r.id)}
						<option value={r.id}>{r.code} · {r.name}</option>
					{/each}
				</select>
			</div>
			<div class="space-y-2">
				<Label for="bf-surgeon">Surgeon</Label>
				<select
					id="bf-surgeon"
					bind:value={surgeonId}
					disabled={submitting}
					class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
				>
					{#each surgeons as s (s.id)}
						<option value={s.id}>{s.displayName} · {s.specialty}</option>
					{/each}
				</select>
			</div>
		</div>

		<div class="grid gap-4 sm:grid-cols-3">
			<div class="space-y-2">
				<Label for="bf-date">Date</Label>
				<Input id="bf-date" type="date" bind:value={date} disabled={submitting} required />
			</div>
			<div class="space-y-2">
				<Label for="bf-start">Start</Label>
				<Input id="bf-start" type="time" bind:value={startTime} disabled={submitting} required />
			</div>
			<div class="space-y-2">
				<Label for="bf-end">End</Label>
				<Input id="bf-end" type="time" bind:value={endTime} disabled={submitting} required />
			</div>
		</div>

		<div class="grid gap-4 sm:grid-cols-2">
			<div class="space-y-2">
				<Label for="bf-optype">Operation type</Label>
				<Input
					id="bf-optype"
					type="text"
					bind:value={opType}
					disabled={submitting}
					maxlength={200}
					placeholder="Knee arthroscopy"
					required
				/>
			</div>
			<div class="space-y-2">
				<Label for="bf-patient">Patient reference</Label>
				<Input
					id="bf-patient"
					type="text"
					bind:value={patientRef}
					disabled={submitting || mode === 'edit'}
					maxlength={50}
					placeholder="P-2026-001"
					required
				/>
				{#if mode === 'edit'}
					<p class="text-xs text-muted-foreground">
						Patient reference can't change after creation.
					</p>
				{/if}
			</div>
		</div>

		<div class="space-y-2">
			<Label for="bf-notes">Notes (optional)</Label>
			<Input
				id="bf-notes"
				type="text"
				bind:value={notes}
				disabled={submitting}
				maxlength={2000}
				placeholder="Pre-op notes, equipment, etc."
			/>
		</div>

		{#if conflict}
			<div
				class="space-y-1 rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				<p class="font-medium">Time conflict</p>
				<p>{conflict.message}</p>
				{#if conflict.occludingId}
					<p class="text-xs">
						Conflicting booking: <code>{conflict.occludingId}</code>
					</p>
				{/if}
			</div>
		{:else if formError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{formError}
			</p>
		{/if}

		<footer class="flex justify-end gap-2">
			<Button type="button" variant="outline" onclick={close} disabled={submitting}>Cancel</Button>
			<Button type="submit" disabled={submitting}>
				{#if submitting}
					Saving…
				{:else}
					{mode === 'edit' ? 'Save changes' : 'Create booking'}
				{/if}
			</Button>
		</footer>
	</form>
</dialog>
