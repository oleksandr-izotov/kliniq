<script lang="ts">
	import { onMount } from 'svelte';
	import { Button } from '$lib/components/ui/button';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import { adminAuditApi, type AuditEventDto, type AuditEventPageDto } from '$lib/api/admin/audit';
	import EmptyState from '$lib/components/EmptyState.svelte';

	const PAGE_SIZE = 50;

	let page = $state<AuditEventPageDto | null>(null);
	let loading = $state(true);
	let listError = $state<string | null>(null);

	// Filters
	let entityType = $state('');
	let action = $state('');
	let actorUserId = $state('');
	let fromDate = $state(''); // YYYY-MM-DD
	let toDate = $state(''); // YYYY-MM-DD
	let pageIndex = $state(0);

	// Side panel
	let selected = $state<AuditEventDto | null>(null);

	onMount(refresh);

	async function refresh() {
		loading = true;
		listError = null;
		try {
			page = await adminAuditApi.list({
				entityType: entityType.trim() || undefined,
				action: action.trim() || undefined,
				actorUserId: actorUserId.trim() || undefined,
				from: fromDate ? `${fromDate}T00:00:00Z` : undefined,
				to: toDate ? `${toDate}T00:00:00Z` : undefined,
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

	function describe(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		return e.payload.message || 'Server error. Please try again.';
	}

	function formatDateTime(iso: string): string {
		return new Date(iso).toLocaleString(undefined, {
			dateStyle: 'medium',
			timeStyle: 'medium'
		});
	}

	function pretty(value: unknown): string {
		if (value == null) return '—';
		try {
			return JSON.stringify(value, null, 2);
		} catch {
			return String(value);
		}
	}

	const totalPages = $derived(page ? Math.max(1, Math.ceil(page.total / page.pageSize)) : 1);
	const isFirstPage = $derived(pageIndex === 0);
	const isLastPage = $derived(page ? pageIndex >= totalPages - 1 : true);
</script>

<svelte:head>
	<title>Audit log · Admin · Kliniq</title>
</svelte:head>

<div class="space-y-4">
	<header class="space-y-1">
		<h1 class="t-h1">Audit log</h1>
		<p class="text-sm text-muted-foreground">
			Append-only history of every state change. Click a row to see the before/after JSON.
		</p>
	</header>

	<!-- Filters -->
	<form
		class="grid gap-3 sm:grid-cols-[1fr_1fr_1fr_auto_auto_auto]"
		onsubmit={(e) => {
			e.preventDefault();
			applyFilters();
		}}
	>
		<div class="space-y-1">
			<Label for="filter-entity">Entity type</Label>
			<Input
				id="filter-entity"
				type="text"
				bind:value={entityType}
				placeholder="booking, user, …"
				maxlength={50}
			/>
		</div>
		<div class="space-y-1">
			<Label for="filter-action">Action</Label>
			<Input
				id="filter-action"
				type="text"
				bind:value={action}
				placeholder="booking.created, …"
				maxlength={100}
			/>
		</div>
		<div class="space-y-1">
			<Label for="filter-actor">Actor (UUID)</Label>
			<Input
				id="filter-actor"
				type="text"
				bind:value={actorUserId}
				placeholder="user id"
				maxlength={36}
			/>
		</div>
		<div class="space-y-1">
			<Label for="filter-from">From</Label>
			<Input id="filter-from" type="date" bind:value={fromDate} />
		</div>
		<div class="space-y-1">
			<Label for="filter-to">To</Label>
			<Input id="filter-to" type="date" bind:value={toDate} />
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
	{:else if page && page.items.length === 0}
		<div class="rounded-2xl border">
			<EmptyState
				image="/illustrations/empty-bookings.webp"
				title="No audit events match these filters"
				description="Adjust or clear the filters above to see more activity."
				size="sm"
			/>
		</div>
	{:else if page}
		<!-- Desktop table -->
		<div class="hidden overflow-x-auto rounded-2xl border md:block">
			<table class="w-full text-sm">
				<thead class="bg-muted/40 text-left text-xs text-muted-foreground uppercase">
					<tr>
						<th class="px-3 py-2">When</th>
						<th class="px-3 py-2">Action</th>
						<th class="px-3 py-2">Entity</th>
						<th class="px-3 py-2">Actor</th>
						<th class="px-3 py-2"></th>
					</tr>
				</thead>
				<tbody>
					{#each page.items as e (e.id)}
						<tr
							class="cursor-pointer border-t hover:bg-muted/40 {selected?.id === e.id
								? 'bg-muted/50'
								: ''}"
							onclick={() => (selected = e)}
						>
							<td class="px-3 py-2 font-mono text-xs whitespace-nowrap text-muted-foreground">
								{formatDateTime(e.createdAt)}
							</td>
							<td class="px-3 py-2 font-mono text-xs">{e.action}</td>
							<td class="px-3 py-2">
								<span class="text-xs text-muted-foreground">{e.entityType}</span>
								{#if e.entityId}
									<br />
									<span class="font-mono text-[10px] text-muted-foreground/70">{e.entityId}</span>
								{/if}
							</td>
							<td class="px-3 py-2">
								{#if e.actorUserId}
									<span class="font-mono text-[10px] text-muted-foreground/70">{e.actorUserId}</span
									>
								{:else}
									<span class="text-xs text-muted-foreground italic">system</span>
								{/if}
							</td>
							<td class="px-3 py-2 text-right">
								<span class="text-xs text-muted-foreground">View →</span>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
		</div>

		<!-- Mobile cards: one tappable card per event opens the same side panel -->
		<ul class="space-y-2 md:hidden">
			{#each page.items as e (e.id)}
				<li>
					<button
						type="button"
						class="w-full space-y-1.5 rounded-2xl border p-3 text-left hover:bg-muted/40 {selected?.id ===
						e.id
							? 'bg-muted/50'
							: ''}"
						onclick={() => (selected = e)}
					>
						<div class="flex items-start justify-between gap-2">
							<span class="font-mono text-sm break-all">{e.action}</span>
							<span class="shrink-0 font-mono text-[10px] whitespace-nowrap text-muted-foreground">
								{formatDateTime(e.createdAt)}
							</span>
						</div>
						<div class="text-xs text-muted-foreground">
							{e.entityType}
							{#if e.actorUserId}
								<span class="text-muted-foreground/70"> · by {e.actorUserId.slice(0, 8)}…</span>
							{:else}
								<span class="italic"> · system</span>
							{/if}
						</div>
					</button>
				</li>
			{/each}
		</ul>

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

{#if selected}
	<div
		class="fixed inset-0 z-20 animate-in bg-black/35 duration-200 fade-in"
		onclick={() => (selected = null)}
		aria-hidden="true"
	></div>
	<aside
		class="fixed inset-y-0 right-0 z-30 w-full max-w-xl animate-in overflow-y-auto border-l bg-background p-6 shadow-2xl duration-200 slide-in-from-right-4"
		aria-label="Audit event details"
	>
		<header class="mb-4 flex items-start justify-between gap-3">
			<div class="space-y-1">
				<h2 class="text-lg font-semibold">Audit event</h2>
				<p class="font-mono text-xs text-muted-foreground">{selected.action}</p>
			</div>
			<Button variant="outline" size="sm" onclick={() => (selected = null)}>Close</Button>
		</header>

		<dl class="grid grid-cols-[max-content_1fr] gap-x-3 gap-y-2 text-sm">
			<dt class="text-muted-foreground">When</dt>
			<dd class="font-medium">{formatDateTime(selected.createdAt)}</dd>

			<dt class="text-muted-foreground">Entity</dt>
			<dd class="font-medium">
				{selected.entityType}
				{#if selected.entityId}
					<br />
					<span class="font-mono text-xs text-muted-foreground/70">{selected.entityId}</span>
				{/if}
			</dd>

			<dt class="text-muted-foreground">Actor</dt>
			<dd class="font-medium">
				{#if selected.actorUserId}
					<span class="font-mono text-xs">{selected.actorUserId}</span>
				{:else}
					<span class="text-muted-foreground italic">system</span>
				{/if}
			</dd>
		</dl>

		<section class="mt-4 space-y-3">
			{#if selected.before !== undefined && selected.before !== null}
				<div>
					<h3 class="mb-1 text-xs font-medium text-muted-foreground uppercase">Before</h3>
					<pre
						class="max-h-64 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-xs">{pretty(
							selected.before
						)}</pre>
				</div>
			{/if}
			{#if selected.after !== undefined && selected.after !== null}
				<div>
					<h3 class="mb-1 text-xs font-medium text-muted-foreground uppercase">After</h3>
					<pre
						class="max-h-64 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-xs">{pretty(
							selected.after
						)}</pre>
				</div>
			{/if}
			{#if selected.metadata !== undefined && selected.metadata !== null}
				<div>
					<h3 class="mb-1 text-xs font-medium text-muted-foreground uppercase">Metadata</h3>
					<pre
						class="max-h-64 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-xs">{pretty(
							selected.metadata
						)}</pre>
				</div>
			{/if}
			{#if !selected.before && !selected.after && !selected.metadata}
				<p class="text-xs text-muted-foreground italic">No payload — this is a marker event.</p>
			{/if}
		</section>
	</aside>
{/if}
