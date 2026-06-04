<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const isAdmin = $derived(data.user.role === 'ADMIN');

	let loading = $state(true);
	let loadError = $state<string | null>(null);
	let saving = $state(false);
	let formError = $state<string | null>(null);

	let name = $state('');
	let timezone = $state('');
	// HTML <input type="time"> works in HH:mm; the API speaks HH:mm:ss.
	// We normalise on read and write so the form never has to think about it.
	let workingHoursStart = $state('');
	let workingHoursEnd = $state('');
	let defaultBookingMinutes = $state(60);

	onMount(refresh);

	async function refresh() {
		loading = true;
		loadError = null;
		try {
			hydrate(await clinicApi.get());
		} catch (e) {
			loadError = describe(e);
		} finally {
			loading = false;
		}
	}

	function hydrate(s: ClinicSettingsDto) {
		name = s.name;
		timezone = s.timezone;
		workingHoursStart = trimSeconds(s.workingHoursStart);
		workingHoursEnd = trimSeconds(s.workingHoursEnd);
		defaultBookingMinutes = s.defaultBookingMinutes;
	}

	function trimSeconds(t: string): string {
		// "08:00:00" -> "08:00" so <input type="time"> accepts it.
		return t.length >= 5 ? t.slice(0, 5) : t;
	}

	function withSeconds(t: string): string {
		// "08:00" -> "08:00:00" — backend's LocalTime parser wants seconds.
		return t.length === 5 ? `${t}:00` : t;
	}

	async function save(event: SubmitEvent) {
		event.preventDefault();
		formError = null;

		const trimmedName = name.trim();
		const trimmedTz = timezone.trim();

		if (!trimmedName) {
			formError = 'Clinic name is required.';
			return;
		}
		if (!trimmedTz) {
			formError = 'Timezone is required (e.g. Europe/Berlin).';
			return;
		}
		if (workingHoursStart >= workingHoursEnd) {
			formError = 'Working hours end must be after the start.';
			return;
		}
		if (!Number.isFinite(defaultBookingMinutes) || defaultBookingMinutes < 5) {
			formError = 'Default booking length must be at least 5 minutes.';
			return;
		}

		saving = true;
		try {
			const updated = await clinicApi.update({
				name: trimmedName,
				timezone: trimmedTz,
				workingHoursStart: withSeconds(workingHoursStart),
				workingHoursEnd: withSeconds(workingHoursEnd),
				defaultBookingMinutes
			});
			hydrate(updated);
			toast.success('Clinic settings saved.');
		} catch (e) {
			formError = describe(e);
		} finally {
			saving = false;
		}
	}

	function describe(e: unknown): string {
		if (e instanceof ApiError_) {
			switch (e.payload.code) {
				case 'INVALID_TIMEZONE':
					return 'Timezone is not recognised. Use an IANA name like "Europe/Berlin".';
				case 'INVALID_WORKING_HOURS':
					return 'Working hours end must be after the start.';
				case 'VALIDATION_ERROR':
					return e.payload.fieldErrors?.length
						? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
						: e.payload.message;
				case 'FORBIDDEN':
					return 'Only admins can change clinic settings.';
				default:
					return e.payload.message || 'Server error. Please try again.';
			}
		}
		return 'Network error. Please try again.';
	}
</script>

<svelte:head>
	<title>Clinic settings · Kliniq</title>
</svelte:head>

<div class="contents">
	<div class="mx-auto w-full max-w-2xl space-y-6">
		<header class="space-y-1">
			<h1 class="t-h1">Clinic settings</h1>
			<p class="text-sm text-muted-foreground">
				{#if isAdmin}
					These values shape every booking: timezone is used to validate working hours, and the
					default length pre-fills the new-booking form.
				{:else}
					Read-only — only admins can change clinic settings.
				{/if}
			</p>
		</header>

		{#if loading}
			<p class="text-sm text-muted-foreground">Loading…</p>
		{:else if loadError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{loadError}
			</p>
		{:else}
			<Card.Root class="rounded-2xl">
				<Card.Header class="px-6 pt-6">
					<Card.Title>General</Card.Title>
					<Card.Description>Identity, timezone, and default scheduling parameters.</Card.Description
					>
				</Card.Header>
				<Card.Content class="px-6 pb-6">
					<form onsubmit={save} class="space-y-4" novalidate>
						<div class="space-y-2">
							<Label for="name">Clinic name</Label>
							<Input
								id="name"
								type="text"
								bind:value={name}
								disabled={!isAdmin || saving}
								maxlength={200}
								required
							/>
						</div>

						<div class="space-y-2">
							<Label for="timezone">Timezone</Label>
							<Input
								id="timezone"
								type="text"
								bind:value={timezone}
								disabled={!isAdmin || saving}
								maxlength={64}
								placeholder="Europe/Berlin"
								required
							/>
							<p class="text-xs text-muted-foreground">
								IANA timezone name. Working hours are interpreted in this zone.
							</p>
						</div>

						<div class="grid gap-4 sm:grid-cols-2">
							<div class="space-y-2">
								<Label for="start">Working hours — start</Label>
								<Input
									id="start"
									type="time"
									bind:value={workingHoursStart}
									disabled={!isAdmin || saving}
									required
								/>
							</div>
							<div class="space-y-2">
								<Label for="end">Working hours — end</Label>
								<Input
									id="end"
									type="time"
									bind:value={workingHoursEnd}
									disabled={!isAdmin || saving}
									required
								/>
							</div>
						</div>

						<div class="space-y-2">
							<Label for="defaultMinutes">Default booking length (minutes)</Label>
							<Input
								id="defaultMinutes"
								type="number"
								bind:value={defaultBookingMinutes}
								disabled={!isAdmin || saving}
								min={5}
								max={1440}
								step={5}
								required
							/>
						</div>

						{#if formError}
							<p
								class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
								role="alert"
							>
								{formError}
							</p>
						{/if}

						{#if isAdmin}
							<Button type="submit" disabled={saving}>
								{saving ? 'Saving…' : 'Save changes'}
							</Button>
						{/if}
					</form>
				</Card.Content>
			</Card.Root>
		{/if}
	</div>
</div>
