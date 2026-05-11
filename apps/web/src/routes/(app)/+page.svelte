<script lang="ts">
	import { onMount } from 'svelte';
	import { goto, invalidateAll } from '$app/navigation';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { ApiError_, authApi } from '$lib/auth/api';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import ModeToggle from '$lib/components/ModeToggle.svelte';
	import OnboardingWizard from '$lib/components/onboarding/OnboardingWizard.svelte';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	let signingOut = $state(false);

	const isManager = $derived(data.user.role === 'MANAGER' || data.user.role === 'ADMIN');
	const isAdmin = $derived(data.user.role === 'ADMIN');

	// Onboarding wizard: shown once per clinic when the first admin signs in
	// before `clinic_settings.onboarded_at` has been stamped. After "Done"
	// it's stamped server-side and the wizard never re-appears.
	let clinicSettings = $state<ClinicSettingsDto | null>(null);
	let wizardOpen = $state(false);

	onMount(async () => {
		if (!isAdmin) return;
		try {
			const s = await clinicApi.get();
			clinicSettings = s;
			if (!s.onboardedAt) wizardOpen = true;
		} catch {
			// Wizard is opportunistic — failing to load settings shouldn't break
			// the home page. Admin can still navigate manually to Clinic settings.
		}
	});

	function onWizardDone() {
		wizardOpen = false;
		// Refresh settings so a follow-up reload doesn't re-trigger the wizard.
		clinicApi.get().then((s) => (clinicSettings = s));
	}

	async function logout() {
		signingOut = true;
		try {
			await authApi.logout();
			toast.success('Signed out');
			await invalidateAll();
			await goto('/login');
		} catch (e) {
			signingOut = false;
			const msg =
				e instanceof ApiError_ ? e.payload.message || 'Sign-out failed.' : 'Sign-out failed.';
			toast.error(msg);
		}
	}

	function roleBadge(role: string): string {
		switch (role) {
			case 'ADMIN':
				return 'bg-primary/15 text-primary border-primary/30';
			case 'MANAGER':
				return 'bg-amber-500/20 text-amber-700 dark:text-amber-300 border-amber-500/40';
			default:
				return 'bg-muted text-muted-foreground border-border';
		}
	}
</script>

<svelte:head>
	<title>Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-6">
	<div class="mx-auto w-full max-w-4xl space-y-6">
		<header class="flex flex-wrap items-end justify-between gap-3">
			<div class="space-y-1">
				<h1 class="text-3xl font-bold tracking-tight">
					Welcome back, {data.user.displayName}
				</h1>
				<p class="text-sm text-muted-foreground">
					{data.user.email}
					<span
						class="ml-2 inline-flex items-center rounded-full border px-2 py-0.5 text-xs tracking-wide uppercase {roleBadge(
							data.user.role
						)}"
					>
						{data.user.role.toLowerCase()}
					</span>
				</p>
			</div>
			<div class="flex items-center gap-2">
				<ModeToggle />
				<Button variant="outline" onclick={logout} disabled={signingOut}>
					{signingOut ? 'Signing out…' : 'Sign out'}
				</Button>
			</div>
		</header>

		<section class="grid gap-4 sm:grid-cols-2">
			<a
				href="/schedule"
				class="group rounded-2xl border p-5 transition hover:border-primary/40"
				data-testid="tile-schedule"
			>
				<h2 class="text-lg font-semibold">Schedule</h2>
				<p class="mt-1 text-sm text-muted-foreground">
					Day-view of every operating room. Click an empty slot to book.
				</p>
				<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
					Open →
				</span>
			</a>

			{#if isManager}
				<a
					href="/operating-rooms"
					class="group rounded-2xl border p-5 transition hover:border-primary/40"
					data-testid="tile-operating-rooms"
				>
					<h2 class="text-lg font-semibold">Operating rooms</h2>
					<p class="mt-1 text-sm text-muted-foreground">
						Add, rename, mark for maintenance, or archive operating rooms.
					</p>
					<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
						Manage →
					</span>
				</a>
			{/if}

			{#if isAdmin}
				<a
					href="/settings/clinic"
					class="group rounded-2xl border p-5 transition hover:border-primary/40"
					data-testid="tile-clinic-settings"
				>
					<h2 class="text-lg font-semibold">Clinic settings</h2>
					<p class="mt-1 text-sm text-muted-foreground">
						Name, timezone, working hours, and the default booking length.
					</p>
					<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
						Configure →
					</span>
				</a>

				<a
					href="/admin/users"
					class="group rounded-2xl border p-5 transition hover:border-primary/40"
					data-testid="tile-admin"
				>
					<h2 class="text-lg font-semibold">Admin</h2>
					<p class="mt-1 text-sm text-muted-foreground">
						Manage users, send invitations, browse the audit log.
					</p>
					<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
						Open →
					</span>
				</a>
			{/if}

			<a
				href="/settings/profile"
				class="group rounded-2xl border p-5 transition hover:border-primary/40"
				data-testid="tile-profile"
			>
				<h2 class="text-lg font-semibold">Profile</h2>
				<p class="mt-1 text-sm text-muted-foreground">
					Update the display name shown on bookings and the schedule.
				</p>
				<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
					Open →
				</span>
			</a>

			<a
				href="/settings/security"
				class="group rounded-2xl border p-5 transition hover:border-primary/40"
				data-testid="tile-security"
			>
				<h2 class="text-lg font-semibold">Security settings</h2>
				<p class="mt-1 text-sm text-muted-foreground">
					Change your password and manage passkeys for this account.
				</p>
				<span class="mt-3 inline-block text-sm font-medium text-primary group-hover:underline">
					Open →
				</span>
			</a>
		</section>

		<Card.Root class="rounded-2xl">
			<Card.Content class="px-6 py-4 text-sm text-muted-foreground">
				Session is alive — closing the tab won't sign you out, but choosing <em>Sign out</em>
				above will end the session everywhere.
			</Card.Content>
		</Card.Root>
	</div>
</main>

{#if clinicSettings && isAdmin}
	<OnboardingWizard open={wizardOpen} initialSettings={clinicSettings} onDone={onWizardDone} />
{/if}
