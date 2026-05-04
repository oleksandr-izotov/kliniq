<script lang="ts">
	import { goto, invalidateAll } from '$app/navigation';
	import { page } from '$app/state';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';

	let email = $state('');
	let password = $state('');
	let submitting = $state(false);
	let formError = $state<string | null>(null);

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
			// Refresh server load functions so locals.user picks up the new session.
			await invalidateAll();
			await goto(safeNext(page.url.searchParams.get('next')));
		} catch (e) {
			const handled = handleApiError(e);
			formError = handled;
		} finally {
			submitting = false;
		}
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
			default:
				return e.payload.message || 'Something went wrong. Please try again.';
		}
	}
</script>

<svelte:head>
	<title>Sign in · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-2xl border-border/40 p-2 shadow-2xl">
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
					autocomplete="email"
					bind:value={email}
					required
					disabled={submitting}
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
					disabled={submitting}
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

			<Button type="submit" class="w-full" disabled={submitting}>
				{submitting ? 'Signing in…' : 'Sign in'}
			</Button>
		</form>
	</Card.Content>
</Card.Root>
