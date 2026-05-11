<script lang="ts">
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { toast } from 'svelte-sonner';
	import { ApiError_ } from '$lib/auth/api';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import { operatingRoomsApi } from '$lib/api/operatingRooms';
	import { adminInvitationsApi } from '$lib/api/admin/invitations';

	interface Props {
		open: boolean;
		initialSettings: ClinicSettingsDto;
		onDone: () => void;
	}

	let { open, initialSettings, onDone }: Props = $props();

	let dlg: HTMLDialogElement | undefined = $state();
	let step = $state<1 | 2 | 3>(1);
	let submitting = $state(false);
	let formError = $state<string | null>(null);

	// Step 1 — clinic config, prefilled from current settings so the admin
	// only types what they actually want to change. The `$state` here
	// intentionally captures the initial values and then lets the user
	// edit them; further changes to `initialSettings` from the parent
	// would be irrelevant (the wizard owns the edited copy).
	// svelte-ignore state_referenced_locally
	let clinicName = $state(initialSettings.name);
	// svelte-ignore state_referenced_locally
	let timezone = $state(initialSettings.timezone);
	// svelte-ignore state_referenced_locally
	let workingHoursStart = $state(initialSettings.workingHoursStart);
	// svelte-ignore state_referenced_locally
	let workingHoursEnd = $state(initialSettings.workingHoursEnd);

	// Step 2 — first operating room (optional).
	let orCode = $state('');
	let orName = $state('');

	// Step 3 — first invitation (optional).
	let inviteEmail = $state('');
	let inviteRole = $state<'ADMIN' | 'MANAGER' | 'STAFF'>('STAFF');
	let inviteIsSurgeon = $state(false);
	let inviteSpecialty = $state<
		'CARDIOLOGY' | 'ORTHOPEDICS' | 'GENERAL' | 'NEUROSURGERY' | 'OPHTHALMOLOGY'
	>('GENERAL');

	// Intl.supportedValuesOf is the canonical timezone source; fall back to a
	// shortlist if the runtime is too old (target modern browsers, but
	// defensive coding costs nothing here).
	const timezones: readonly string[] =
		typeof Intl.supportedValuesOf === 'function'
			? Intl.supportedValuesOf('timeZone')
			: [
					'UTC',
					'Europe/Berlin',
					'Europe/London',
					'Europe/Kyiv',
					'America/New_York',
					'America/Los_Angeles',
					'Asia/Tokyo'
				];

	$effect(() => {
		if (!dlg) return;
		if (open && !dlg.open) {
			dlg.showModal();
		} else if (!open && dlg.open) {
			dlg.close();
		}
	});

	async function step1Next() {
		submitting = true;
		formError = null;
		try {
			await clinicApi.update({
				name: clinicName.trim(),
				timezone,
				workingHoursStart,
				workingHoursEnd,
				// Pass through the unchanged value: the generated TypeScript
				// type marks this required even though the Kotlin DTO keeps
				// it nullable. Cheaper than fixing the springdoc emit.
				defaultBookingMinutes: initialSettings.defaultBookingMinutes
			});
			step = 2;
		} catch (e) {
			formError = describe(e);
		} finally {
			submitting = false;
		}
	}

	async function step2Submit(skip: boolean) {
		submitting = true;
		formError = null;
		try {
			if (!skip && orCode.trim() && orName.trim()) {
				await operatingRoomsApi.create({ code: orCode.trim(), name: orName.trim() });
			}
			step = 3;
		} catch (e) {
			formError = describe(e);
		} finally {
			submitting = false;
		}
	}

	async function step3Submit(skip: boolean) {
		submitting = true;
		formError = null;
		try {
			if (!skip && inviteEmail.trim()) {
				await adminInvitationsApi.create({
					email: inviteEmail.trim(),
					role: inviteRole,
					isSurgeon: inviteIsSurgeon,
					specialty: inviteIsSurgeon ? inviteSpecialty : undefined
				});
			}
			// One-shot stamp — the wizard's contract is "completion = stamped".
			// Idempotent on the server side: re-running this won't double-stamp.
			await clinicApi.onboard();
			toast.success('Clinic is ready to go.');
			onDone();
		} catch (e) {
			formError = describe(e);
		} finally {
			submitting = false;
		}
	}

	function describe(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		return e.payload.message || 'Server error. Please try again.';
	}
</script>

<dialog
	bind:this={dlg}
	class="w-full max-w-lg rounded-2xl bg-background text-foreground shadow-2xl backdrop:bg-black/50 backdrop:backdrop-blur-sm"
>
	<form class="space-y-5 p-6" onsubmit={(e) => e.preventDefault()}>
		<header class="space-y-1">
			<p class="text-xs font-medium tracking-wide text-muted-foreground uppercase">
				Step {step} of 3
			</p>
			<h2 class="text-xl font-semibold">
				{#if step === 1}Tell us about your clinic{:else if step === 2}Add your first operating room{:else}Invite
					a team member{/if}
			</h2>
			<p class="text-sm text-muted-foreground">
				{#if step === 1}
					Name, timezone, and working hours. You can change all of these later in Clinic settings.
				{:else if step === 2}
					Set up at least one OR so the schedule has something to show. Skip if you'd rather add
					them yourself in the admin area.
				{:else}
					Invite a manager or staff member. They'll get an email link to set their own password.
				{/if}
			</p>
		</header>

		{#if formError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{formError}
			</p>
		{/if}

		{#if step === 1}
			<div class="space-y-3">
				<div class="space-y-1">
					<Label for="wiz-name">Clinic name</Label>
					<Input
						id="wiz-name"
						type="text"
						bind:value={clinicName}
						maxlength={100}
						required
						autocomplete="off"
					/>
				</div>
				<div class="space-y-1">
					<Label for="wiz-tz">Timezone</Label>
					<select
						id="wiz-tz"
						bind:value={timezone}
						class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
					>
						{#each timezones as tz (tz)}
							<option value={tz}>{tz}</option>
						{/each}
					</select>
				</div>
				<div class="grid grid-cols-2 gap-3">
					<div class="space-y-1">
						<Label for="wiz-hour-start">Working hours start</Label>
						<Input id="wiz-hour-start" type="time" bind:value={workingHoursStart} required />
					</div>
					<div class="space-y-1">
						<Label for="wiz-hour-end">Working hours end</Label>
						<Input id="wiz-hour-end" type="time" bind:value={workingHoursEnd} required />
					</div>
				</div>
			</div>
		{:else if step === 2}
			<div class="space-y-3">
				<div class="space-y-1">
					<Label for="wiz-or-code">Code</Label>
					<Input
						id="wiz-or-code"
						type="text"
						bind:value={orCode}
						maxlength={20}
						placeholder="OR-1"
						autocomplete="off"
					/>
				</div>
				<div class="space-y-1">
					<Label for="wiz-or-name">Name</Label>
					<Input
						id="wiz-or-name"
						type="text"
						bind:value={orName}
						maxlength={100}
						placeholder="Operating Room 1"
						autocomplete="off"
					/>
				</div>
			</div>
		{:else}
			<div class="space-y-3">
				<div class="space-y-1">
					<Label for="wiz-inv-email">Email</Label>
					<Input
						id="wiz-inv-email"
						type="email"
						bind:value={inviteEmail}
						maxlength={254}
						placeholder="colleague@clinic.example"
						autocomplete="off"
					/>
				</div>
				<div class="space-y-1">
					<Label for="wiz-inv-role">Role</Label>
					<select
						id="wiz-inv-role"
						bind:value={inviteRole}
						class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
					>
						<option value="STAFF">Staff</option>
						<option value="MANAGER">Manager</option>
						<option value="ADMIN">Admin</option>
					</select>
				</div>
				<label class="flex items-center gap-2 text-sm">
					<input type="checkbox" bind:checked={inviteIsSurgeon} />
					<span>This person performs surgeries</span>
				</label>
				{#if inviteIsSurgeon}
					<div class="space-y-1">
						<Label for="wiz-inv-spec">Specialty</Label>
						<select
							id="wiz-inv-spec"
							bind:value={inviteSpecialty}
							class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
						>
							<option value="GENERAL">General</option>
							<option value="CARDIOLOGY">Cardiology</option>
							<option value="ORTHOPEDICS">Orthopedics</option>
							<option value="NEUROSURGERY">Neurosurgery</option>
							<option value="OPHTHALMOLOGY">Ophthalmology</option>
						</select>
					</div>
				{/if}
			</div>
		{/if}

		<footer class="flex items-center justify-between gap-2 pt-2">
			{#if step === 1}
				<span class="text-xs text-muted-foreground">Required step — no skip available</span>
				<Button onclick={step1Next} disabled={submitting || !clinicName.trim()}>
					{submitting ? 'Saving…' : 'Next →'}
				</Button>
			{:else if step === 2}
				<Button variant="ghost" onclick={() => step2Submit(true)} disabled={submitting}>
					Skip
				</Button>
				<Button
					onclick={() => step2Submit(false)}
					disabled={submitting || !orCode.trim() || !orName.trim()}
				>
					{submitting ? 'Saving…' : 'Save & Next →'}
				</Button>
			{:else}
				<Button variant="ghost" onclick={() => step3Submit(true)} disabled={submitting}>
					Skip & finish
				</Button>
				<Button
					onclick={() => step3Submit(false)}
					disabled={submitting || inviteEmail.trim().length === 0}
				>
					{submitting ? 'Finishing…' : 'Invite & finish'}
				</Button>
			{/if}
		</footer>
	</form>
</dialog>
