<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import {
		adminUsersApi,
		type AdminUserDto,
		type AdminUserPageDto,
		type AdminUserRole,
		type AdminUserSpecialty,
		type AdminUserStatus
	} from '$lib/api/admin/users';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const SPECIALTIES: ReadonlyArray<AdminUserSpecialty> = [
		'CARDIOLOGY',
		'GENERAL',
		'NEUROSURGERY',
		'OPHTHALMOLOGY',
		'ORTHOPEDICS'
	];

	let page = $state<AdminUserPageDto | null>(null);
	let loading = $state(true);
	let listError = $state<string | null>(null);

	// Filters
	let q = $state('');
	let roleFilter = $state<AdminUserRole | ''>('');
	let statusFilter = $state<AdminUserStatus | ''>('');
	let pageIndex = $state(0);
	const PAGE_SIZE = 50;

	// Per-row pending state — keyed by user id, so two simultaneous edits
	// on different rows don't disable the whole table.
	let pendingId = $state<string | null>(null);

	onMount(refresh);

	async function refresh() {
		loading = true;
		listError = null;
		try {
			page = await adminUsersApi.list({
				q: q.trim() || undefined,
				role: roleFilter || undefined,
				status: statusFilter || undefined,
				page: pageIndex,
				pageSize: PAGE_SIZE
			});
		} catch (e) {
			listError = describe(e);
		} finally {
			loading = false;
		}
	}

	function applyFilters() {
		pageIndex = 0;
		void refresh();
	}

	async function changeRole(user: AdminUserDto, newRole: AdminUserRole) {
		if (newRole === user.role) return;
		await mutate(user, { role: newRole }, 'Role updated.');
	}

	async function toggleSurgeon(user: AdminUserDto, isSurgeon: boolean) {
		if (isSurgeon && !user.specialty) {
			// Need a specialty before turning the flag on. Default to GENERAL —
			// admin can change after.
			await mutate(user, { isSurgeon: true, specialty: 'GENERAL' }, 'Marked as surgeon.');
		} else if (!isSurgeon) {
			await mutate(user, { isSurgeon: false }, 'Surgeon flag removed.');
		} else {
			// Already a surgeon — no-op.
		}
	}

	async function changeSpecialty(user: AdminUserDto, specialty: AdminUserSpecialty) {
		if (specialty === user.specialty) return;
		await mutate(user, { isSurgeon: true, specialty }, 'Specialty updated.');
	}

	async function toggleStatus(user: AdminUserDto) {
		if (user.status === 'ACTIVE') {
			const ok = confirm(
				`Disable ${user.displayName} (${user.email})? ` +
					`This logs them out everywhere and stops them signing in. ` +
					`You can re-enable them later.`
			);
			if (!ok) return;
			await mutate(user, { status: 'DISABLED' }, `${user.displayName} disabled.`);
		} else {
			await mutate(user, { status: 'ACTIVE' }, `${user.displayName} re-enabled.`);
		}
	}

	async function mutate(
		user: AdminUserDto,
		patch: Parameters<typeof adminUsersApi.update>[1],
		successMessage: string
	) {
		pendingId = user.id;
		try {
			const updated = await adminUsersApi.update(user.id, patch);
			// Splice the updated row in place so the table doesn't flicker
			// to a fresh sort order on every change.
			if (page) {
				page = {
					...page,
					items: page.items.map((u) => (u.id === updated.id ? updated : u))
				};
			}
			toast.success(successMessage);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			pendingId = null;
		}
	}

	function describe(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'SELF_LOCKOUT':
				return "You can't demote or disable yourself. Ask another admin.";
			case 'LAST_ADMIN':
				return 'That change would leave no active admins. Promote another user first.';
			case 'INVALID_SPECIALTY':
				return e.payload.message;
			case 'NOT_FOUND':
				return 'User not found — refresh the list.';
			case 'VALIDATION_ERROR':
				return e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
			default:
				return e.payload.message || 'Server error. Please try again.';
		}
	}

	function statusBadge(status: AdminUserStatus): string {
		return status === 'ACTIVE'
			? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30'
			: 'bg-muted text-muted-foreground border-border';
	}

	function roleBadge(role: AdminUserRole): string {
		switch (role) {
			case 'ADMIN':
				return 'bg-primary/15 text-primary border-primary/30';
			case 'MANAGER':
				return 'bg-amber-500/20 text-amber-700 dark:text-amber-300 border-amber-500/40';
			default:
				return 'bg-muted text-muted-foreground border-border';
		}
	}

	const totalPages = $derived(page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1);
	const isFirstPage = $derived(pageIndex === 0);
	const isLastPage = $derived(page ? pageIndex >= totalPages - 1 : true);
</script>

<svelte:head>
	<title>Users · Admin · Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-6">
	<div class="mx-auto w-full max-w-6xl space-y-4">
		<header class="space-y-1">
			<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
			<h1 class="text-3xl font-bold tracking-tight">Users</h1>
			<p class="text-sm text-muted-foreground">
				Change roles, flag surgeons, disable accounts. Disabling logs the user out everywhere. You
				can't demote or disable yourself — ask another admin.
			</p>
		</header>

		<!-- Filters -->
		<form
			class="grid gap-3 sm:grid-cols-[1fr_auto_auto_auto]"
			onsubmit={(e) => {
				e.preventDefault();
				applyFilters();
			}}
		>
			<div class="space-y-1">
				<Label for="filter-q">Search</Label>
				<Input
					id="filter-q"
					type="text"
					bind:value={q}
					placeholder="name or email"
					maxlength={100}
				/>
			</div>
			<div class="space-y-1">
				<Label for="filter-role">Role</Label>
				<select
					id="filter-role"
					bind:value={roleFilter}
					class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
				>
					<option value="">Any</option>
					<option value="ADMIN">Admin</option>
					<option value="MANAGER">Manager</option>
					<option value="STAFF">Staff</option>
				</select>
			</div>
			<div class="space-y-1">
				<Label for="filter-status">Status</Label>
				<select
					id="filter-status"
					bind:value={statusFilter}
					class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
				>
					<option value="">Any</option>
					<option value="ACTIVE">Active</option>
					<option value="DISABLED">Disabled</option>
				</select>
			</div>
			<div class="flex items-end">
				<Button type="submit" variant="outline" size="sm" disabled={loading}>Filter</Button>
			</div>
		</form>

		{#if loading && !page}
			<p class="text-sm text-muted-foreground">Loading…</p>
		{:else if listError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{listError}
			</p>
		{:else if page}
			<div class="overflow-x-auto rounded-2xl border">
				<table class="w-full text-sm">
					<thead class="bg-muted/40 text-left text-xs text-muted-foreground uppercase">
						<tr>
							<th class="px-3 py-2">User</th>
							<th class="px-3 py-2">Role</th>
							<th class="px-3 py-2">Surgeon</th>
							<th class="px-3 py-2">Status</th>
							<th class="px-3 py-2 text-right">Actions</th>
						</tr>
					</thead>
					<tbody>
						{#each page.items as u (u.id)}
							{@const isSelf = u.id === data.user.id}
							{@const isPending = pendingId === u.id}
							<tr class="border-t" class:opacity-60={isPending}>
								<td class="px-3 py-2">
									<div class="font-medium">
										{u.displayName}
										{#if isSelf}
											<span class="text-xs text-muted-foreground">(you)</span>
										{/if}
									</div>
									<div class="text-xs text-muted-foreground">{u.email}</div>
								</td>
								<td class="px-3 py-2">
									<select
										value={u.role}
										onchange={(e) =>
											changeRole(u, (e.currentTarget as HTMLSelectElement).value as AdminUserRole)}
										disabled={isPending}
										class="h-8 rounded-md border border-input bg-background px-2 text-xs"
									>
										<option value="ADMIN">Admin</option>
										<option value="MANAGER">Manager</option>
										<option value="STAFF">Staff</option>
									</select>
									<span
										class="ml-2 inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] tracking-wide uppercase {roleBadge(
											u.role
										)}"
									>
										{u.role.toLowerCase()}
									</span>
								</td>
								<td class="px-3 py-2">
									<label class="inline-flex items-center gap-2">
										<input
											type="checkbox"
											checked={u.isSurgeon}
											disabled={isPending}
											onchange={(e) =>
												toggleSurgeon(u, (e.currentTarget as HTMLInputElement).checked)}
											class="h-4 w-4"
										/>
										{#if u.isSurgeon}
											<select
												value={u.specialty ?? 'GENERAL'}
												onchange={(e) =>
													changeSpecialty(
														u,
														(e.currentTarget as HTMLSelectElement).value as AdminUserSpecialty
													)}
												disabled={isPending}
												class="h-8 rounded-md border border-input bg-background px-2 text-xs"
											>
												{#each SPECIALTIES as s (s)}
													<option value={s}>{s.toLowerCase()}</option>
												{/each}
											</select>
										{/if}
									</label>
								</td>
								<td class="px-3 py-2">
									<span
										class="inline-flex items-center rounded-full border px-2 py-0.5 text-[10px] tracking-wide uppercase {statusBadge(
											u.status
										)}"
									>
										{u.status.toLowerCase()}
									</span>
								</td>
								<td class="px-3 py-2 text-right">
									<Button
										variant="outline"
										size="sm"
										onclick={() => toggleStatus(u)}
										disabled={isPending}
									>
										{u.status === 'ACTIVE' ? 'Disable' : 'Re-enable'}
									</Button>
								</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</div>

			<footer class="flex items-center justify-between text-sm">
				<p class="text-muted-foreground">
					Showing {page.items.length} of {page.total}
				</p>
				<div class="flex items-center gap-2">
					<Button
						variant="outline"
						size="sm"
						onclick={() => {
							pageIndex = Math.max(0, pageIndex - 1);
							void refresh();
						}}
						disabled={isFirstPage || loading}
					>
						← Prev
					</Button>
					<span class="text-xs text-muted-foreground">
						Page {pageIndex + 1} of {totalPages}
					</span>
					<Button
						variant="outline"
						size="sm"
						onclick={() => {
							pageIndex += 1;
							void refresh();
						}}
						disabled={isLastPage || loading}
					>
						Next →
					</Button>
				</div>
			</footer>
		{/if}
	</div>
</main>
