<script lang="ts">
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';

	let displayName = $state('');
	let email = $state('');
	let password = $state('');
	let submitting = $state(false);
	let success = $state(false);
	let formError = $state<string | null>(null);

	const MIN_PASSWORD = 12;

	async function onSubmit(event: SubmitEvent) {
		event.preventDefault();
		formError = null;
		if (password.length < MIN_PASSWORD) {
			formError = `Password must be at least ${MIN_PASSWORD} characters.`;
			return;
		}
		submitting = true;
		try {
			await authApi.register({
				email: email.trim(),
				password,
				displayName: displayName.trim()
			});
			success = true;
			toast.success('Check your email for a verification link.');
		} catch (e) {
			formError = handleApiError(e);
		} finally {
			submitting = false;
		}
	}

	function handleApiError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Something went wrong. Please try again.';
		if (e.payload.code === 'VALIDATION_ERROR' && e.payload.fieldErrors?.length) {
			return e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ');
		}
		if (e.payload.code === 'RATE_LIMITED') {
			return 'Too many attempts. Please wait a minute and try again.';
		}
		return e.payload.message || 'Something went wrong. Please try again.';
	}
</script>

<svelte:head>
	<title>Create account · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-2xl border-border/40 p-2 shadow-2xl">
	{#if success}
		<Card.Header class="space-y-2 px-6 pt-6">
			<Card.Title class="text-3xl font-bold tracking-tight">Check your inbox</Card.Title>
			<Card.Description class="text-base">
				If <span class="font-medium text-foreground">{email}</span> isn't already registered, we've sent
				a verification link to it. Click it to activate your account, then sign in.
			</Card.Description>
		</Card.Header>
		<Card.Content class="space-y-4 px-6 pb-6">
			<p class="text-sm text-muted-foreground">
				The link expires in 24 hours. Didn't get it?
				<button
					type="button"
					class="font-medium text-primary hover:underline"
					onclick={() => (success = false)}
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
			<Card.Title class="text-3xl font-bold tracking-tight">Create account</Card.Title>
			<Card.Description class="text-base">
				Already have one?
				<a href="/login" class="font-medium text-primary hover:underline"> Sign in </a>.
			</Card.Description>
		</Card.Header>
		<Card.Content class="px-6 pb-6">
			<form onsubmit={onSubmit} novalidate class="space-y-4">
				<div class="space-y-2">
					<Label for="displayName">Display name</Label>
					<Input
						id="displayName"
						type="text"
						autocomplete="name"
						bind:value={displayName}
						required
						disabled={submitting}
						maxlength={100}
						placeholder="Dr Alice Smith"
					/>
				</div>

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
					<Label for="password">Password</Label>
					<Input
						id="password"
						type="password"
						autocomplete="new-password"
						bind:value={password}
						required
						disabled={submitting}
						minlength={MIN_PASSWORD}
						maxlength={128}
						placeholder="at least {MIN_PASSWORD} characters"
					/>
					<p class="text-xs text-muted-foreground">
						A passphrase of three or four random words is great here.
					</p>
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
					{submitting ? 'Creating…' : 'Create account'}
				</Button>
			</form>
		</Card.Content>
	{/if}
</Card.Root>
