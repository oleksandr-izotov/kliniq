<script lang="ts">
	import { goto, invalidateAll } from '$app/navigation';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { ApiError_, authApi } from '$lib/auth/api';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	let signingOut = $state(false);

	async function logout() {
		signingOut = true;
		try {
			await authApi.logout();
			toast.success('Signed out');
			// Invalidate so server load reruns and the layout guard kicks us
			// out cleanly — but we explicitly redirect anyway.
			await invalidateAll();
			await goto('/login');
		} catch (e) {
			signingOut = false;
			const msg =
				e instanceof ApiError_ ? e.payload.message || 'Sign-out failed.' : 'Sign-out failed.';
			toast.error(msg);
		}
	}
</script>

<svelte:head>
	<title>Kliniq</title>
</svelte:head>

<main class="flex min-h-screen items-center justify-center bg-background p-6">
	<Card.Root class="w-full max-w-md rounded-2xl border-border/40 shadow-2xl">
		<Card.Header class="space-y-2 px-6 pt-6">
			<Card.Title class="text-2xl font-bold tracking-tight">
				Welcome back, {data.user.displayName}
			</Card.Title>
			<Card.Description>{data.user.email}</Card.Description>
		</Card.Header>
		<Card.Content class="space-y-4 px-6 pb-6">
			<p class="text-sm text-muted-foreground">
				The booking dashboard lands here in the next sprint. For now, this screen confirms your
				session is alive — you can sign in and out, and a logged-out visitor will be redirected back
				to the login page automatically.
			</p>
			<div class="flex items-center justify-between">
				<a href="/settings/security" class="text-sm font-medium text-primary hover:underline">
					Security settings →
				</a>
				<Button variant="outline" onclick={logout} disabled={signingOut}>
					{signingOut ? 'Signing out…' : 'Sign out'}
				</Button>
			</div>
		</Card.Content>
	</Card.Root>
</main>
