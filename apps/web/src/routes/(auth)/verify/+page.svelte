<script lang="ts">
	import { onMount } from 'svelte';
	import { page } from '$app/state';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { ApiError_, authApi } from '$lib/auth/api';

	type Status = 'verifying' | 'success' | 'error';

	let status = $state<Status>('verifying');
	let errorMessage = $state('');

	onMount(async () => {
		const token = page.url.searchParams.get('token');
		if (!token) {
			status = 'error';
			errorMessage =
				'This page expects a token in the URL. Open the verification link from your email exactly as it was sent.';
			return;
		}

		try {
			// Seed the XSRF cookie so the verify POST has a valid header.
			await authApi.primeCsrf();
			await authApi.verify({ token });
			status = 'success';
		} catch (e) {
			status = 'error';
			errorMessage = handleApiError(e);
		}
	});

	function handleApiError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_TOKEN':
				return 'This verification link is invalid or has expired. Register again to get a fresh one.';
			case 'RATE_LIMITED':
				return 'Too many attempts. Please wait a minute and try again.';
			default:
				return e.payload.message || 'We could not verify your email. Please try again.';
		}
	}
</script>

<svelte:head>
	<title>Verify email · Kliniq</title>
</svelte:head>

<Card.Root class="rounded-2xl border-border/40 p-2 shadow-2xl">
	<Card.Header class="space-y-2 px-6 pt-6">
		{#if status === 'verifying'}
			<Card.Title class="text-3xl font-bold tracking-tight">Verifying…</Card.Title>
			<Card.Description class="text-base">Hold on while we activate your account.</Card.Description>
		{:else if status === 'success'}
			<Card.Title class="text-3xl font-bold tracking-tight">Email verified</Card.Title>
			<Card.Description class="text-base">
				Your account is active. Sign in to continue.
			</Card.Description>
		{:else}
			<Card.Title class="text-3xl font-bold tracking-tight">Couldn't verify</Card.Title>
			<Card.Description class="text-base">{errorMessage}</Card.Description>
		{/if}
	</Card.Header>
	<Card.Content class="space-y-4 px-6 pb-6">
		{#if status === 'success'}
			<Button class="w-full" href="/login">Go to sign in</Button>
		{:else if status === 'error'}
			<p class="text-sm">
				<a href="/register" class="font-medium text-primary hover:underline">Register again</a>
				<span class="text-muted-foreground"> · </span>
				<a href="/login" class="font-medium text-primary hover:underline">Back to sign in</a>
			</p>
		{/if}
	</Card.Content>
</Card.Root>
