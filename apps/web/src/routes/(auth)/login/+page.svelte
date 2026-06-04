<script lang="ts">
	import { onMount } from 'svelte';
	import { goto, invalidateAll } from '$app/navigation';
	import { page } from '$app/state';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';
	import { passkeyApi, PasskeyCeremonyError, passkeysSupported } from '$lib/auth/passkeys';

	let email = $state('');
	let password = $state('');
	let submitting = $state(false);
	let passkeySubmitting = $state(false);
	let canUsePasskeys = $state(false);
	let formError = $state<string | null>(null);

	onMount(() => {
		canUsePasskeys = passkeysSupported();
	});

	/** Only honour same-origin paths from `?next=` — never an external URL. */
	function safeNext(raw: string | null): string {
		if (!raw) return '/';
		if (!raw.startsWith('/') || raw.startsWith('//')) return '/';
		return raw;
	}

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		submitting = true;
		try {
			const user = await authApi.login({ email: email.trim(), password });
			toast.success(`Welcome back, ${user.displayName}`);
			await afterSignIn();
		} catch (e) {
			formError = handleApiError(e);
		} finally {
			submitting = false;
		}
	}

	async function onPasskey() {
		formError = null;
		passkeySubmitting = true;
		try {
			// `email` is optional. If the user typed one, narrow the prompt to
			// their credentials; otherwise the browser shows discoverable keys.
			const trimmed = email.trim();
			const user = await passkeyApi.signIn(trimmed.length > 0 ? trimmed : undefined);
			toast.success(`Welcome back, ${user.displayName}`);
			await afterSignIn();
		} catch (e) {
			if (e instanceof PasskeyCeremonyError) {
				formError = e.message;
			} else {
				formError = handleApiError(e);
			}
		} finally {
			passkeySubmitting = false;
		}
	}

	async function afterSignIn() {
		// Refresh server load functions so locals.user picks up the new session.
		await invalidateAll();
		await goto(safeNext(page.url.searchParams.get('next')));
	}

	function handleApiError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Something went wrong. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_CREDENTIALS':
				return 'Email or password is incorrect.';
			case 'EMAIL_NOT_VERIFIED':
				return 'Please verify your email before signing in. Check your inbox for the verification link.';
			case 'ACCOUNT_DISABLED':
				return 'This account has been disabled. Contact your administrator.';
			case 'RATE_LIMITED':
				return 'Too many attempts. Please wait a minute and try again.';
			case 'PASSKEY_INVALID':
				return 'Could not verify that passkey. Try again, or sign in with your password.';
			case 'PASSKEY_CHALLENGE_EXPIRED':
				return 'The passkey prompt timed out. Try again.';
			default:
				return e.payload.message || 'Something went wrong. Please try again.';
		}
	}
</script>

<svelte:head>
	<title>Sign in · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-[24px] border-border/40 p-2 shadow-2xl">
	<Card.Header class="space-y-2 px-6 pt-6">
		<Card.Title class="text-3xl font-bold tracking-tight">Sign in</Card.Title>
		<Card.Description class="text-base">
			New here?
			<a href="/register" class="font-medium text-primary hover:underline"> Create an account </a>.
		</Card.Description>
	</Card.Header>
	<Card.Content class="px-6 pb-6">
		<form onsubmit={onSubmit} novalidate class="space-y-4">
			<div class="space-y-2">
				<Label for="email">Email</Label>
				<Input
					id="email"
					type="email"
					autocomplete="email webauthn"
					bind:value={email}
					required
					disabled={submitting || passkeySubmitting}
					placeholder="you@clinic.example"
				/>
			</div>

			<div class="space-y-2">
				<div class="flex items-center justify-between">
					<Label for="password">Password</Label>
					<a href="/reset" class="text-xs text-muted-foreground hover:text-foreground"> Forgot? </a>
				</div>
				<Input
					id="password"
					type="password"
					autocomplete="current-password"
					bind:value={password}
					required
					disabled={submitting || passkeySubmitting}
					minlength={12}
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

			<Button type="submit" class="cta-gradient w-full" disabled={submitting || passkeySubmitting}>
				{submitting ? 'Signing in…' : 'Sign in'}
			</Button>

			{#if canUsePasskeys}
				<div class="relative my-2">
					<div class="absolute inset-0 flex items-center border-border/60">
						<span class="w-full border-t"></span>
					</div>
					<div class="relative flex justify-center text-xs uppercase">
						<span class="bg-card px-2 text-muted-foreground">or</span>
					</div>
				</div>

				<Button
					type="button"
					variant="outline"
					class="w-full"
					onclick={onPasskey}
					disabled={submitting || passkeySubmitting}
				>
					{passkeySubmitting ? 'Waiting for prompt…' : 'Sign in with a passkey'}
				</Button>
			{/if}
		</form>
	</Card.Content>
</Card.Root>
