<script lang="ts">
	import { invalidateAll } from '$app/navigation';
	import { untrack } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';
	import type { PageData } from './$types';

	const MAX_NAME = 100;

	let { data }: { data: PageData } = $props();

	// One-shot capture: the form's working copy initializes from the
	// loader's view, but mid-edit prop updates (e.g. another tab's SSE
	// invalidation) shouldn't clobber what the user is typing. After a
	// successful save we explicitly resync below.
	let displayName = $state(untrack(() => data.user.displayName));
	let saving = $state(false);
	let error = $state<string | null>(null);

	const trimmed = $derived(displayName.trim());
	const dirty = $derived(trimmed !== data.user.displayName && trimmed.length > 0);

	async function save(event: SubmitEvent) {
		event.preventDefault();
		error = null;
		if (trimmed.length === 0) {
			error = 'Display name must not be empty.';
			return;
		}
		if (trimmed.length > MAX_NAME) {
			error = `Display name must be at most ${MAX_NAME} characters.`;
			return;
		}
		saving = true;
		try {
			await authApi.updateProfile({ displayName: trimmed });
			// `data.user.displayName` is sourced from `locals.user` on the
			// server `+layout.server.ts` loader; invalidateAll re-runs that
			// loader so the new name surfaces in the home banner and side
			// panels without a hard reload.
			await invalidateAll();
			displayName = trimmed;
			toast.success('Profile updated.');
		} catch (e) {
			error = describeError(e);
		} finally {
			saving = false;
		}
	}

	function describeError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'VALIDATION_ERROR':
				return e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
			case 'RATE_LIMITED':
				return 'Too many attempts. Please wait a minute and try again.';
			default:
				return e.payload.message || 'Something went wrong. Please try again.';
		}
	}
</script>

<svelte:head>
	<title>Profile · Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-6">
	<div class="mx-auto w-full max-w-2xl space-y-6">
		<header class="space-y-1">
			<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
			<h1 class="text-3xl font-bold tracking-tight">Profile</h1>
			<p class="text-sm text-muted-foreground">
				Your display name is what colleagues see on bookings and the schedule. Email and role are
				managed by an admin.
			</p>
		</header>

		<Card.Root class="rounded-2xl">
			<Card.Header class="px-6 pt-6">
				<Card.Title>Display name</Card.Title>
				<Card.Description>
					Use the form colleagues will recognise — your name as it should appear on a booking
					attribution.
				</Card.Description>
			</Card.Header>
			<Card.Content class="px-6 pb-6">
				<form onsubmit={save} class="space-y-4" novalidate>
					<div class="space-y-2">
						<Label for="displayName">Display name</Label>
						<Input
							id="displayName"
							type="text"
							autocomplete="name"
							bind:value={displayName}
							required
							disabled={saving}
							maxlength={MAX_NAME}
						/>
					</div>

					<dl class="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
						<div>
							<dt class="text-muted-foreground">Email</dt>
							<dd class="font-medium">{data.user.email}</dd>
						</div>
						<div>
							<dt class="text-muted-foreground">Role</dt>
							<dd class="font-medium">{data.user.role.toLowerCase()}</dd>
						</div>
					</dl>

					{#if error}
						<p
							class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
							role="alert"
						>
							{error}
						</p>
					{/if}

					<Button type="submit" disabled={saving || !dirty}>
						{saving ? 'Saving…' : 'Save'}
					</Button>
				</form>
			</Card.Content>
		</Card.Root>
	</div>
</main>
