<script lang="ts">
	import { onMount } from 'svelte';
	import { goto, invalidateAll } from '$app/navigation';
	import { page } from '$app/state';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi, type InvitationPreview } from '$lib/auth/api';

	type Preview = InvitationPreview;

	const MIN_PASSWORD = 12;

	let token = $state('');
	let preview = $state<Preview | null>(null);
	let loadError = $state<string | null>(null);
	let loading = $state(true);

	let displayName = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let submitting = $state(false);
	let formError = $state<string | null>(null);

	onMount(async () => {
		const queryToken = page.url.searchParams.get('token') ?? '';
		token = queryToken;
		if (!token) {
			loadError = 'No invitation token in the URL. Open the link from the email again.';
			loading = false;
			return;
		}
		// Prime CSRF cookie before the first POST. Same trick the (auth)
		// layout uses, but /invite is its own route so we have to do it
		// here too.
		void authApi.primeCsrf();
		try {
			preview = await authApi.previewInvitation(token);
		} catch (e) {
			loadError = describePreviewError(e);
		} finally {
			loading = false;
		}
	});

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		if (password.length < MIN_PASSWORD) {
			formError = `Password must be at least ${MIN_PASSWORD} characters.`;
			return;
		}
		if (password !== confirmPassword) {
			formError = "Password and confirmation don't match.";
			return;
		}
		const trimmedDisplayName = displayName.trim();
		if (!trimmedDisplayName) {
			formError = 'Display name is required.';
			return;
		}
		submitting = true;
		try {
			const user = await authApi.acceptInvitation({
				token,
				password,
				displayName: trimmedDisplayName
			});
			toast.success(`Welcome to Kliniq, ${user.displayName}.`);
			await invalidateAll();
			await goto('/');
		} catch (e) {
			formError = describeAcceptError(e);
		} finally {
			submitting = false;
		}
	}

	function describePreviewError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_TOKEN':
				return 'This invitation link is invalid. Ask the admin to send a new one.';
			case 'INVITATION_EXPIRED':
				return 'This invitation has expired. Ask the admin to send a new one.';
			case 'INVITATION_REVOKED':
				return 'This invitation has been revoked. Ask the admin to send a new one.';
			case 'INVITATION_ALREADY_ACCEPTED':
				return 'This invitation has already been used. Sign in normally instead.';
			default:
				return e.payload.message || 'Server error. Please try again.';
		}
	}

	function describeAcceptError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_TOKEN':
			case 'INVITATION_EXPIRED':
			case 'INVITATION_REVOKED':
			case 'INVITATION_ALREADY_ACCEPTED':
				return describePreviewError(e);
			case 'USER_ALREADY_EXISTS':
				return 'A user with that email already exists. Sign in normally instead.';
			case 'PASSWORD_BREACHED':
				return 'This password has appeared in a known data breach. Choose a different one.';
			case 'VALIDATION_ERROR':
				return e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
			default:
				return e.payload.message || 'Server error. Please try again.';
		}
	}
</script>

<svelte:head>
	<title>Accept invitation · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-[24px] border-border/40 p-2 shadow-2xl">
	<Card.Header class="space-y-2 px-6 pt-6">
		<Card.Title class="text-2xl font-bold tracking-tight">Accept invitation</Card.Title>
		{#if preview}
			<Card.Description>
				You've been invited to <strong>{preview.email}</strong> as a
				<strong>{preview.role.toLowerCase()}</strong>{#if preview.isSurgeon}, flagged as a surgeon ({preview.specialty?.toLowerCase()}){/if}.
				Pick a password and a display name to finish.
			</Card.Description>
		{:else if loading}
			<Card.Description>Checking invitation…</Card.Description>
		{:else}
			<Card.Description class="text-destructive">
				{loadError ?? 'Unable to load this invitation.'}
			</Card.Description>
		{/if}
	</Card.Header>

	{#if preview}
		<Card.Content class="px-6 pb-6">
			<form onsubmit={submit} class="space-y-4" novalidate>
				<div class="space-y-2">
					<Label for="display-name">Display name</Label>
					<Input
						id="display-name"
						type="text"
						bind:value={displayName}
						disabled={submitting}
						maxlength={100}
						required
					/>
				</div>
				<div class="space-y-2">
					<Label for="password">Password</Label>
					<Input
						id="password"
						type="password"
						autocomplete="new-password"
						bind:value={password}
						disabled={submitting}
						minlength={MIN_PASSWORD}
						maxlength={128}
						placeholder="at least {MIN_PASSWORD} characters"
						required
					/>
				</div>
				<div class="space-y-2">
					<Label for="confirm-password">Confirm password</Label>
					<Input
						id="confirm-password"
						type="password"
						autocomplete="new-password"
						bind:value={confirmPassword}
						disabled={submitting}
						minlength={MIN_PASSWORD}
						maxlength={128}
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

				<Button type="submit" class="cta-gradient" disabled={submitting}>
					{submitting ? 'Creating account…' : 'Create account & sign in'}
				</Button>
			</form>
		</Card.Content>
	{:else if !loading}
		<Card.Content class="px-6 pb-6">
			<p class="text-sm text-muted-foreground">
				<a href="/login" class="text-primary hover:underline">Sign in instead</a> if you already have
				an account.
			</p>
		</Card.Content>
	{/if}
</Card.Root>
