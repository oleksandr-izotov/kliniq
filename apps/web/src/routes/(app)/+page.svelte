<script lang="ts">
	import { onMount } from 'svelte';
	import * as Card from '$lib/components/ui/card';
	import { Button } from '$lib/components/ui/button';
	import { clinicApi, type ClinicSettingsDto } from '$lib/api/clinic';
	import { operatingRoomsApi, type OperatingRoomDto } from '$lib/api/operatingRooms';
	import { usersApi, type SurgeonSummaryDto } from '$lib/api/users';
	import { scheduleApi, type ScheduleDto } from '$lib/api/schedule';
	import {
		formatLocalTime,
		localMinutesOfDay,
		timeStringToMinutes,
		todayInZone
	} from '$lib/util/datetime';
	import OnboardingWizard from '$lib/components/onboarding/OnboardingWizard.svelte';
	import DoorOpenIcon from '@lucide/svelte/icons/door-open';
	import CalendarCheckIcon from '@lucide/svelte/icons/calendar-check';
	import ActivityIcon from '@lucide/svelte/icons/activity';
	import CalendarDaysIcon from '@lucide/svelte/icons/calendar-days';
	import ScrollTextIcon from '@lucide/svelte/icons/scroll-text';
	import UserIcon from '@lucide/svelte/icons/user';
	import ShieldCheckIcon from '@lucide/svelte/icons/shield-check';
	import Building2Icon from '@lucide/svelte/icons/building-2';
	import UsersIcon from '@lucide/svelte/icons/users';
	import PlusIcon from '@lucide/svelte/icons/plus';
	import ArrowRightIcon from '@lucide/svelte/icons/arrow-right';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const isManager = $derived(data.user.role === 'MANAGER' || data.user.role === 'ADMIN');
	const isAdmin = $derived(data.user.role === 'ADMIN');

	// ---- Dashboard data (opportunistic — failures degrade to placeholders) --
	let clinicSettings = $state<ClinicSettingsDto | null>(null);
	let rooms = $state<readonly OperatingRoomDto[]>([]);
	let surgeons = $state<readonly SurgeonSummaryDto[]>([]);
	let today = $state<ScheduleDto | null>(null);
	let loaded = $state(false);

	// Onboarding wizard: shown once per clinic when the first admin signs in
	// before `clinic_settings.onboarded_at` has been stamped.
	let wizardOpen = $state(false);

	onMount(async () => {
		const [clinicR, roomsR, surgeonsR] = await Promise.allSettled([
			clinicApi.get(),
			operatingRoomsApi.list(),
			usersApi.listSurgeons()
		]);
		if (clinicR.status === 'fulfilled') {
			clinicSettings = clinicR.value;
			if (isAdmin && !clinicR.value.onboardedAt) wizardOpen = true;
			try {
				today = await scheduleApi.day(todayInZone(clinicR.value.timezone));
			} catch {
				// Schedule is a nice-to-have on the dashboard; ignore failures.
			}
		}
		if (roomsR.status === 'fulfilled') rooms = roomsR.value;
		if (surgeonsR.status === 'fulfilled') surgeons = surgeonsR.value;
		loaded = true;
	});

	function onWizardDone() {
		wizardOpen = false;
		clinicApi.get().then((s) => (clinicSettings = s));
	}

	// ---- Derived stats ---------------------------------------------------
	const activeRooms = $derived(rooms.filter((r) => r.status === 'ACTIVE').length);
	const maintenanceRooms = $derived(rooms.filter((r) => r.status === 'MAINTENANCE').length);
	const todayBookings = $derived(
		today
			? today.operatingRooms.flatMap((o) => o.bookings).filter((b) => b.status !== 'CANCELLED')
			: []
	);
	const inProgress = $derived(todayBookings.filter((b) => b.status === 'IN_PROGRESS').length);
	const utilisation = $derived.by(() => {
		if (!clinicSettings || activeRooms === 0) return 0;
		const win =
			timeStringToMinutes(clinicSettings.workingHoursEnd) -
			timeStringToMinutes(clinicSettings.workingHoursStart);
		if (win <= 0) return 0;
		const booked = todayBookings.reduce(
			(sum, b) =>
				sum +
				(localMinutesOfDay(b.endsAt, clinicSettings!.timezone) -
					localMinutesOfDay(b.startsAt, clinicSettings!.timezone)),
			0
		);
		return Math.min(100, Math.round((booked / (activeRooms * win)) * 100));
	});

	const upNext = $derived.by(() => {
		if (!today)
			return [] as { b: ScheduleDto['operatingRooms'][number]['bookings'][number]; code: string }[];
		return today.operatingRooms
			.flatMap((o) => o.bookings.map((b) => ({ b, code: o.code })))
			.filter(({ b }) => b.status === 'SCHEDULED' || b.status === 'IN_PROGRESS')
			.sort((a, z) => a.b.startsAt.localeCompare(z.b.startsAt))
			.slice(0, 5);
	});

	function surgeonName(id: string): string {
		return surgeons.find((s) => s.id === id)?.displayName ?? 'Unknown';
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

	// Quick-tiles — role-gated. testids preserved for e2e coverage.
	type Tile = {
		href: string;
		title: string;
		copy: string;
		cta: string;
		testid: string;
		icon: typeof DoorOpenIcon;
		show: boolean;
	};
	const tiles = $derived<Tile[]>(
		[
			{
				href: '/schedule',
				title: 'Schedule',
				copy: 'Day-view of every operating room. Click an empty slot to book.',
				cta: 'Open',
				testid: 'tile-schedule',
				icon: CalendarDaysIcon,
				show: true
			},
			{
				href: '/operating-rooms',
				title: 'Operating rooms',
				copy: 'Add, rename, mark for maintenance, or archive operating rooms.',
				cta: 'Manage',
				testid: 'tile-operating-rooms',
				icon: DoorOpenIcon,
				show: isManager
			},
			{
				href: '/settings/clinic',
				title: 'Clinic settings',
				copy: 'Name, timezone, working hours, and the default booking length.',
				cta: 'Configure',
				testid: 'tile-clinic-settings',
				icon: Building2Icon,
				show: isAdmin
			},
			{
				href: '/admin/users',
				title: 'Admin',
				copy: 'Manage users, send invitations, browse the audit log.',
				cta: 'Open',
				testid: 'tile-admin',
				icon: UsersIcon,
				show: isAdmin
			},
			{
				href: '/admin/audit',
				title: 'Audit log',
				copy: 'Every booking and account change, newest first.',
				cta: 'Review',
				testid: 'tile-audit',
				icon: ScrollTextIcon,
				show: isAdmin
			},
			{
				href: '/settings/profile',
				title: 'Profile',
				copy: 'Update the display name shown on bookings and the schedule.',
				cta: 'Open',
				testid: 'tile-profile',
				icon: UserIcon,
				show: true
			},
			{
				href: '/settings/security',
				title: 'Security',
				copy: 'Change your password and manage passkeys for this account.',
				cta: 'Open',
				testid: 'tile-security',
				icon: ShieldCheckIcon,
				show: true
			}
		].filter((t) => t.show)
	);
</script>

<svelte:head>
	<title>Kliniq</title>
</svelte:head>

<div class="contents">
	<div class="mx-auto w-full max-w-4xl space-y-6">
		<header class="flex flex-wrap items-end justify-between gap-3">
			<div class="space-y-1">
				<h1 class="t-h1">Welcome back, {data.user.displayName}</h1>
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
			<Button href="/schedule" class="cta-gradient gap-1.5">
				<PlusIcon class="size-4" /> New booking
			</Button>
		</header>

		<!-- Stat cards -->
		<section class="grid gap-4 sm:grid-cols-3">
			<Card.Root class="transition-colors hover:ring-primary/40">
				<Card.Content class="flex items-center justify-between gap-3 px-5">
					<div>
						<div class="text-xs font-medium text-muted-foreground">Operating rooms</div>
						<div class="mt-0.5 text-[28px] font-bold tracking-[-0.02em]">
							{#if loaded}{activeRooms}{:else}<span class="text-muted-foreground/40">—</span>{/if}
						</div>
						<div class="mt-0.5 text-[11.5px] text-muted-foreground">
							{activeRooms} active · {maintenanceRooms} maintenance
						</div>
					</div>
					<div
						class="grid size-[42px] shrink-0 place-items-center rounded-xl bg-primary/15 text-primary"
					>
						<DoorOpenIcon class="size-5" />
					</div>
				</Card.Content>
			</Card.Root>

			<Card.Root class="transition-colors hover:ring-primary/40">
				<Card.Content class="flex items-center justify-between gap-3 px-5">
					<div>
						<div class="text-xs font-medium text-muted-foreground">Bookings today</div>
						<div class="mt-0.5 text-[28px] font-bold tracking-[-0.02em]">
							{#if loaded}{todayBookings.length}{:else}<span class="text-muted-foreground/40"
									>—</span
								>{/if}
						</div>
						<div class="mt-0.5 text-[11.5px] text-muted-foreground">{inProgress} in progress</div>
					</div>
					<div
						class="grid size-[42px] shrink-0 place-items-center rounded-xl bg-primary/15 text-primary"
					>
						<CalendarCheckIcon class="size-5" />
					</div>
				</Card.Content>
			</Card.Root>

			<Card.Root class="transition-colors hover:ring-primary/40">
				<Card.Content class="flex items-center justify-between gap-3 px-5">
					<div>
						<div class="text-xs font-medium text-muted-foreground">Utilisation</div>
						<div class="mt-0.5 text-[28px] font-bold tracking-[-0.02em]">
							{#if loaded}{utilisation}%{:else}<span class="text-muted-foreground/40">—</span>{/if}
						</div>
						<div class="mt-0.5 text-[11.5px] text-muted-foreground">of active-room hours today</div>
					</div>
					<div
						class="grid size-[42px] shrink-0 place-items-center rounded-xl bg-primary/15 text-primary"
					>
						<ActivityIcon class="size-5" />
					</div>
				</Card.Content>
			</Card.Root>
		</section>

		<!-- Quick tiles -->
		<section class="grid gap-4 sm:grid-cols-2">
			{#each tiles as tile (tile.testid)}
				{@const Icon = tile.icon}
				<a
					href={tile.href}
					data-testid={tile.testid}
					class="group glass flex flex-col rounded-2xl p-5 transition-colors hover:border-primary/40"
				>
					<div class="mb-3 flex items-center gap-2.5">
						<div class="grid size-9 place-items-center rounded-xl bg-primary/15 text-primary">
							<Icon class="size-[18px]" />
						</div>
						<h2 class="font-semibold">{tile.title}</h2>
					</div>
					<p class="text-sm text-muted-foreground">{tile.copy}</p>
					<span class="mt-3 inline-flex items-center gap-1 text-sm font-medium text-primary">
						{tile.cta}
						<ArrowRightIcon class="size-3.5 transition-transform group-hover:translate-x-0.5" />
					</span>
				</a>
			{/each}
		</section>

		<!-- Up next agenda -->
		<Card.Root>
			<Card.Header class="px-5">
				<Card.Title class="text-base">Up next today</Card.Title>
			</Card.Header>
			<Card.Content class="px-5">
				{#if !loaded}
					<p class="text-sm text-muted-foreground">Loading…</p>
				{:else if upNext.length === 0}
					<p class="text-sm text-muted-foreground">
						Nothing scheduled for the rest of today.
						<a href="/schedule" class="font-medium text-primary hover:underline"
							>Open the schedule →</a
						>
					</p>
				{:else}
					<ul class="divide-y divide-border">
						{#each upNext as { b, code } (b.id)}
							<li class="flex items-center gap-3 py-2.5 first:pt-0 last:pb-0">
								<span class="t-mono w-28 shrink-0 text-xs text-muted-foreground">
									{#if today}{formatLocalTime(b.startsAt, today.timezone)}–{formatLocalTime(
											b.endsAt,
											today.timezone
										)}{/if}
								</span>
								<div class="min-w-0 flex-1">
									<div class="truncate text-sm font-medium">{b.opType}</div>
									<div class="truncate text-xs text-muted-foreground">
										{surgeonName(b.surgeonId)}
									</div>
								</div>
								<span
									class="t-mono shrink-0 rounded-md bg-muted px-2 py-0.5 text-xs text-muted-foreground"
								>
									{code}
								</span>
							</li>
						{/each}
					</ul>
				{/if}
			</Card.Content>
		</Card.Root>
	</div>
</div>

{#if clinicSettings && isAdmin}
	<OnboardingWizard open={wizardOpen} initialSettings={clinicSettings} onDone={onWizardDone} />
{/if}
