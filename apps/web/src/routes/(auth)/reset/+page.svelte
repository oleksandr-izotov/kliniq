<script lang="ts">
	import { page } from '$app/state';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';

	// /reset has two modes, switched by the presence of `?token=` in the URL:
	//   • no token  → ask for the email and send a reset link.
	//   • has token → ask for the new password and complete the reset.
	// Backend embeds the link as `/reset?token=...` so this page handles both.
	const token = $derived(page.url.searchParams.get('token'));

	const MIN_PASSWORD = 12;

	// --- request-link state -----------------------------------------------
	let email = $state('');
	let requestSubmitting = $state(false);
	let requestSent = $state(false);

	// --- complete-reset state ---------------------------------------------
	let newPassword = $state('');
	let confirmPassword = $state('');
	let resetSubmitting = $state(false);
	let resetSuccess = $state(false);

	// --- shared error -----------------------------------------------------
	let formError = $state<string | null>(null);

	async function onRequestSubmit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		requestSubmitting = true;
		try {
			await authApi.forgotPassword({ email: email.trim() });
			requestSent = true;
		} catch (e) {
			formError = handleApiError(e);
		} finally {
			requestSubmitting = false;
		}
	}

	async function onResetSubmit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		if (newPassword.length < MIN_PASSWORD) {
			formError = `Password must be at least ${MIN_PASSWORD} characters.`;
			return;
		}
		if (newPassword !== confirmPassword) {
			formError = 'Passwords do not match.';
			return;
		}
		resetSubmitting = true;
		try {
			await authApi.primeCsrf();
			await authApi.resetPassword({ token: token!, newPassword });
			resetSuccess = true;
		} catch (e) {
			formError = handleApiError(e);
		} finally {
			resetSubmitting = false;
		}
	}

	function handleApiError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Something went wrong. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_TOKEN':
				return 'This reset link is invalid or has expired. Request a new one.';
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
	<title>Reset password · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-[24px] border-border/40 p-2 shadow-2xl">
	{#if token}
		<!-- COMPLETE-RESET MODE: email link delivered the user here. -->
		{#if resetSuccess}
			<Card.Header class="space-y-2 px-6 pt-6">
				<Card.Title class="text-3xl font-bold tracking-tight">Password updated</Card.Title>
				<Card.Description class="text-base">
					You can now sign in with your new password.
				</Card.Description>
			</Card.Header>
			<Card.Content class="px-6 pb-6">
				<Button class="w-full" href="/login">Go to sign in</Button>
			</Card.Content>
		{:else}
			<Card.Header class="space-y-2 px-6 pt-6">
				<Card.Title class="text-3xl font-bold tracking-tight">Set new password</Card.Title>
				<Card.Description class="text-base">
					Pick something you haven't used here before.
				</Card.Description>
			</Card.Header>
			<Card.Content class="px-6 pb-6">
				<form onsubmit={onResetSubmit} novalidate class="space-y-4">
					<div class="space-y-2">
						<Label for="newPassword">New password</Label>
						<Input
							id="newPassword"
							type="password"
							autocomplete="new-password"
							bind:value={newPassword}
							required
							disabled={resetSubmitting}
							minlength={MIN_PASSWORD}
							maxlength={128}
							placeholder="at least {MIN_PASSWORD} characters"
						/>
						<p class="text-xs text-muted-foreground">
							A passphrase of three or four random words is great here.
						</p>
					</div>

					<div class="space-y-2">
						<Label for="confirmPassword">Confirm password</Label>
						<Input
							id="confirmPassword"
							type="password"
							autocomplete="new-password"
							bind:value={confirmPassword}
							required
							disabled={resetSubmitting}
							minlength={MIN_PASSWORD}
							maxlength={128}
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

					<Button type="submit" class="cta-gradient w-full" disabled={resetSubmitting}>
						{resetSubmitting ? 'Updating…' : 'Update password'}
					</Button>

					<p class="text-center text-sm text-muted-foreground">
						Wrong link?
						<a href="/reset" class="font-medium text-primary hover:underline">
							Request a new one
						</a>.
					</p>
				</form>
			</Card.Content>
		{/if}
	{:else}
		<!-- REQUEST-LINK MODE. -->
		{#if requestSent}
			<Card.Header class="space-y-2 px-6 pt-6">
				<Card.Title class="text-3xl font-bold tracking-tight">Check your inbox</Card.Title>
				<Card.Description class="text-base">
					If <span class="font-medium text-foreground">{email}</span> is registered and verified, we've
					sent a reset link. Click it to set a new password.
				</Card.Description>
			</Card.Header>
			<Card.Content class="space-y-4 px-6 pb-6">
				<p class="text-sm text-muted-foreground">
					The link expires in 1 hour. Didn't get it?
					<button
						type="button"
						class="font-medium text-primary hover:underline"
						onclick={() => (requestSent = false)}
					>
						Try a different email
					</button>.
				</p>
				<p class="text-sm">
					<a href="/login" class="font-medium text-primary hover:underline">Back to sign in</a>
				</p>
			</Card.Content>
		{:else}
			<Card.Header class="space-y-2 px-6 pt-6">
				<Card.Title class="text-3xl font-bold tracking-tight">Reset password</Card.Title>
				<Card.Description class="text-base">
					Enter the email on your account and we'll send a reset link.
				</Card.Description>
			</Card.Header>
			<Card.Content class="px-6 pb-6">
				<form onsubmit={onRequestSubmit} novalidate class="space-y-4">
					<div class="space-y-2">
						<Label for="email">Email</Label>
						<Input
							id="email"
							type="email"
							autocomplete="email"
							bind:value={email}
							required
							disabled={requestSubmitting}
							placeholder="you@clinic.example"
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

					<Button type="submit" class="cta-gradient w-full" disabled={requestSubmitting}>
						{requestSubmitting ? 'Sending…' : 'Send reset link'}
					</Button>

					<p class="text-center text-sm text-muted-foreground">
						Remembered it?
						<a href="/login" class="font-medium text-primary hover:underline">Sign in</a>.
					</p>
				</form>
			</Card.Content>
		{/if}
	{/if}
</Card.Root>
