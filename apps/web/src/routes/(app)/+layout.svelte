<script lang="ts">
	import { page } from '$app/state';
	import { goto, invalidateAll } from '$app/navigation';
	import { toast } from 'svelte-sonner';
	import { ApiError_, authApi } from '$lib/auth/api';
	import ModeToggle from '$lib/components/ModeToggle.svelte';
	import LayoutDashboardIcon from '@lucide/svelte/icons/layout-dashboard';
	import CalendarDaysIcon from '@lucide/svelte/icons/calendar-days';
	import DoorOpenIcon from '@lucide/svelte/icons/door-open';
	import ScrollTextIcon from '@lucide/svelte/icons/scroll-text';
	import SettingsIcon from '@lucide/svelte/icons/settings';
	import PanelLeftCloseIcon from '@lucide/svelte/icons/panel-left-close';
	import PanelLeftOpenIcon from '@lucide/svelte/icons/panel-left-open';
	import LogOutIcon from '@lucide/svelte/icons/log-out';
	import type { Snippet } from 'svelte';
	import type { LayoutData } from './$types';

	let { data, children }: { data: LayoutData; children: Snippet } = $props();

	const isManager = $derived(data.user.role === 'MANAGER' || data.user.role === 'ADMIN');
	const isAdmin = $derived(data.user.role === 'ADMIN');

	type NavItem = { href: string; label: string; icon: typeof LayoutDashboardIcon; show: boolean };
	const nav = $derived<NavItem[]>(
		[
			{ href: '/', label: 'Dashboard', icon: LayoutDashboardIcon, show: true },
			{ href: '/schedule', label: 'Schedule', icon: CalendarDaysIcon, show: true },
			{ href: '/operating-rooms', label: 'Operating rooms', icon: DoorOpenIcon, show: isManager },
			{ href: '/admin/audit', label: 'Audit log', icon: ScrollTextIcon, show: isAdmin },
			{ href: '/settings/profile', label: 'Settings', icon: SettingsIcon, show: true }
		].filter((i) => i.show)
	);

	function isActive(href: string): boolean {
		if (href === '/') return page.url.pathname === '/';
		// Settings groups all of /settings/* and (for admins) /admin/* under their item.
		if (href === '/settings/profile') return page.url.pathname.startsWith('/settings');
		if (href === '/admin/audit') return page.url.pathname === '/admin/audit';
		return page.url.pathname === href || page.url.pathname.startsWith(`${href}/`);
	}

	const initials = $derived(
		data.user.displayName
			.split(/\s+/)
			.filter(Boolean)
			.slice(0, 2)
			.map((p) => p[0]?.toUpperCase() ?? '')
			.join('') || data.user.email[0]?.toUpperCase()
	);

	let collapsed = $state(false);
	let signingOut = $state(false);

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
</script>

<div class="relative flex min-h-screen overflow-hidden bg-background text-foreground">
	<!-- Ambient emerald aurora behind everything -->
	<div class="aurora" aria-hidden="true"></div>

	<!-- Collapsible glass sidebar -->
	<aside
		class="glass relative z-20 flex shrink-0 flex-col overflow-hidden border-y-0 border-l-0 transition-[width,padding] duration-300 ease-[cubic-bezier(0.2,0.7,0.3,1)] {collapsed
			? 'w-0 border-r-0 px-0'
			: 'w-[236px] px-4 py-[22px]'}"
		style="border-color: var(--sidebar-glass-border, var(--glass-border));"
	>
		<div
			class="flex flex-col gap-1 transition-opacity duration-150 {collapsed
				? 'pointer-events-none opacity-0'
				: 'opacity-100'}"
		>
			<!-- Brand lockup + collapse -->
			<div class="flex items-center gap-2.5 px-1 pb-5">
				<img src="/brand/kliniq-icon.svg" alt="" class="brand-glow size-[34px] rounded-[9px]" />
				<b class="text-[19px] font-bold tracking-[-0.03em]">Kliniq</b>
				<button
					type="button"
					onclick={() => (collapsed = true)}
					aria-label="Collapse sidebar"
					class="ml-auto grid size-[30px] place-items-center rounded-lg border border-border text-muted-foreground transition-colors hover:bg-foreground/[0.07] hover:text-foreground"
				>
					<PanelLeftCloseIcon class="size-4" />
				</button>
			</div>

			<!-- Primary nav -->
			<nav class="flex flex-col gap-0.5" aria-label="Primary">
				{#each nav as item (item.href)}
					{@const Icon = item.icon}
					<a
						href={item.href}
						class="nav-item"
						aria-current={isActive(item.href) ? 'page' : undefined}
					>
						<Icon class="size-[17px]" />
						{item.label}
					</a>
				{/each}
			</nav>
		</div>

		<!-- Footer: theme toggle + user box -->
		<div
			class="mt-auto flex flex-col gap-3 transition-opacity duration-150 {collapsed
				? 'pointer-events-none opacity-0'
				: 'opacity-100'}"
		>
			<div class="flex items-center justify-between px-1">
				<span class="text-xs text-muted-foreground">Theme</span>
				<ModeToggle />
			</div>
			<div
				class="flex items-center gap-2.5 rounded-xl border border-border bg-foreground/[0.04] p-2.5"
			>
				<div
					class="grid size-[34px] shrink-0 place-items-center rounded-full bg-gradient-to-br from-primary/70 to-primary text-[13px] font-bold text-primary-foreground"
				>
					{initials}
				</div>
				<div class="min-w-0 flex-1">
					<div class="truncate text-[13px] leading-tight font-semibold">
						{data.user.displayName}
					</div>
					<div class="truncate text-[11px] leading-tight text-muted-foreground capitalize">
						{data.user.role.toLowerCase()}
					</div>
				</div>
				<button
					type="button"
					onclick={logout}
					disabled={signingOut}
					aria-label="Sign out"
					title="Sign out"
					class="grid size-[30px] shrink-0 place-items-center rounded-lg text-muted-foreground transition-colors hover:bg-foreground/[0.07] hover:text-foreground disabled:opacity-50"
				>
					<LogOutIcon class="size-4" />
				</button>
			</div>
		</div>
	</aside>

	<!-- Re-open button (only when collapsed) -->
	{#if collapsed}
		<button
			type="button"
			onclick={() => (collapsed = false)}
			aria-label="Open sidebar"
			class="glass fixed top-[22px] left-[18px] z-30 grid size-[38px] place-items-center rounded-[10px] text-muted-foreground transition-colors hover:text-foreground"
		>
			<PanelLeftOpenIcon class="size-[18px]" />
		</button>
	{/if}

	<!-- Main content -->
	<main
		class="relative z-10 min-w-0 flex-1 overflow-x-hidden p-4 transition-[padding] duration-300 sm:p-6 {collapsed
			? 'pl-[70px] sm:pl-[70px]'
			: ''}"
	>
		{@render children()}
	</main>
</div>
