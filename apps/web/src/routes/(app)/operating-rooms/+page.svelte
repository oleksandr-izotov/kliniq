<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import { operatingRoomsApi, type OperatingRoomDto } from '$lib/api/operatingRooms';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const isManager = $derived(data.user.role === 'MANAGER' || data.user.role === 'ADMIN');

	let rooms = $state<readonly OperatingRoomDto[]>([]);
	let loading = $state(true);
	let loadError = $state<string | null>(null);

	// Add form state
	let newCode = $state('');
	let newName = $state('');
	let newNotes = $state('');
	let creating = $state(false);

	// Per-row edit
	let editingId = $state<string | null>(null);
	let editName = $state('');
	let editNotes = $state('');
	let editStatus = $state<OperatingRoomDto['status']>('ACTIVE');
	let editSubmitting = $state(false);

	// Per-row archive spinner
	let archivingId = $state<string | null>(null);

	onMount(refresh);

	async function refresh() {
		loading = true;
		loadError = null;
		try {
			rooms = await operatingRoomsApi.list();
		} catch (e) {
			loadError = describe(e);
		} finally {
			loading = false;
		}
	}

	async function add(event: SubmitEvent) {
		event.preventDefault();
		const code = newCode.trim();
		const name = newName.trim();
		if (!code || !name) {
			toast.error('Code and name are required.');
			return;
		}
		creating = true;
		try {
			const created = await operatingRoomsApi.create({
				code,
				name,
				notes: newNotes.trim() || undefined
			});
			rooms = [...rooms, created].toSorted((a, b) => a.code.localeCompare(b.code));
			newCode = '';
			newName = '';
			newNotes = '';
			toast.success(`Added "${created.code}".`);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			creating = false;
		}
	}

	function startEdit(room: OperatingRoomDto) {
		editingId = room.id;
		editName = room.name;
		editNotes = room.notes ?? '';
		editStatus = room.status;
	}

	function cancelEdit() {
		editingId = null;
	}

	async function submitEdit(room: OperatingRoomDto, event: SubmitEvent) {
		event.preventDefault();
		const name = editName.trim();
		if (!name) {
			toast.error('Name must not be empty.');
			return;
		}
		editSubmitting = true;
		try {
			const updated = await operatingRoomsApi.update(room.id, {
				name,
				// Empty string clears notes; the use case treats blank as null.
				notes: editNotes.trim().length === 0 ? '' : editNotes.trim(),
				status: editStatus
			});
			rooms = rooms.map((r) => (r.id === updated.id ? updated : r));
			cancelEdit();
			toast.success('Saved.');
		} catch (e) {
			toast.error(describe(e));
		} finally {
			editSubmitting = false;
		}
	}

	async function archive(room: OperatingRoomDto) {
		const ok = confirm(
			`Archive "${room.code}"? It'll be hidden from new bookings. ` +
				`Active bookings on this room must be cancelled first.`
		);
		if (!ok) return;
		archivingId = room.id;
		try {
			await operatingRoomsApi.archive(room.id);
			rooms = rooms.filter((r) => r.id !== room.id);
			toast.success(`Archived ${room.code}.`);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			archivingId = null;
		}
	}

	function describe(e: unknown): string {
		if (e instanceof ApiError_) {
			switch (e.payload.code) {
				case 'DUPLICATE_CODE':
					return 'An operating room with that code already exists.';
				case 'OR_HAS_ACTIVE_BOOKINGS':
					return e.payload.message;
				case 'ALREADY_ARCHIVED':
					return 'This room is already archived.';
				case 'NOT_FOUND':
					return 'Operating room not found.';
				case 'VALIDATION_ERROR':
					return e.payload.fieldErrors?.length
						? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
						: e.payload.message;
				default:
					return e.payload.message || 'Server error. Please try again.';
			}
		}
		return 'Network error. Please try again.';
	}

	function formatDate(iso: string | null | undefined): string {
		if (!iso) return '—';
		return new Date(iso).toLocaleDateString(undefined, {
			year: 'numeric',
			month: 'short',
			day: 'numeric'
		});
	}
</script>

<svelte:head>
	<title>Operating rooms · Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-6">
	<div class="mx-auto w-full max-w-3xl space-y-6">
		<header class="space-y-1">
			<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
			<h1 class="text-3xl font-bold tracking-tight">Operating rooms</h1>
			<p class="text-sm text-muted-foreground">
				{#if isManager}
					Add, rename, mark for maintenance, or archive operating rooms. Archived rooms are hidden
					from new bookings; active bookings must be cancelled first.
				{:else}
					Read-only — only managers and admins can change operating rooms.
				{/if}
			</p>
		</header>

		{#if isManager}
			<Card.Root class="rounded-2xl">
				<Card.Header class="px-6 pt-6">
					<Card.Title>Add an operating room</Card.Title>
					<Card.Description>
						Pick a short code (e.g. <code class="rounded bg-muted px-1">OR-1</code>) and a friendly
						name.
					</Card.Description>
				</Card.Header>
				<Card.Content class="px-6 pb-6">
					<form onsubmit={add} class="space-y-4">
						<div class="grid gap-4 sm:grid-cols-2">
							<div class="space-y-2">
								<Label for="code">Code</Label>
								<Input
									id="code"
									type="text"
									bind:value={newCode}
									disabled={creating}
									maxlength={20}
									placeholder="OR-1"
									required
								/>
							</div>
							<div class="space-y-2">
								<Label for="name">Name</Label>
								<Input
									id="name"
									type="text"
									bind:value={newName}
									disabled={creating}
									maxlength={100}
									placeholder="Surgical Suite 1"
									required
								/>
							</div>
						</div>
						<div class="space-y-2">
							<Label for="notes">Notes (optional)</Label>
							<Input
								id="notes"
								type="text"
								bind:value={newNotes}
								disabled={creating}
								maxlength={2000}
								placeholder="north wing, equipped for cardiac"
							/>
						</div>
						<Button type="submit" disabled={creating}>
							{creating ? 'Adding…' : 'Add room'}
						</Button>
					</form>
				</Card.Content>
			</Card.Root>
		{/if}

		<section class="space-y-3">
			<h2 class="text-xl font-semibold">All rooms</h2>
			{#if loading}
				<p class="text-sm text-muted-foreground">Loading…</p>
			{:else if loadError}
				<p
					class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
					role="alert"
				>
					{loadError}
				</p>
			{:else if rooms.length === 0}
				<Card.Root class="rounded-2xl">
					<Card.Content class="px-6 py-8 text-center text-sm text-muted-foreground">
						No operating rooms yet.
						{#if isManager}
							Add the first one above.
						{/if}
					</Card.Content>
				</Card.Root>
			{:else}
				<ul class="space-y-3">
					{#each rooms as room (room.id)}
						<li>
							<Card.Root class="rounded-2xl">
								<Card.Content class="space-y-3 px-6 py-5">
									{#if editingId === room.id}
										<form onsubmit={(e) => submitEdit(room, e)} class="space-y-3">
											<div class="grid gap-3 sm:grid-cols-2">
												<div class="space-y-1">
													<Label for="edit-name">Name</Label>
													<Input
														id="edit-name"
														type="text"
														bind:value={editName}
														disabled={editSubmitting}
														maxlength={100}
														required
													/>
												</div>
												<div class="space-y-1">
													<Label for="edit-status">Status</Label>
													<select
														id="edit-status"
														bind:value={editStatus}
														disabled={editSubmitting}
														class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
													>
														<option value="ACTIVE">Active</option>
														<option value="MAINTENANCE">Maintenance</option>
													</select>
												</div>
											</div>
											<div class="space-y-1">
												<Label for="edit-notes">Notes</Label>
												<Input
													id="edit-notes"
													type="text"
													bind:value={editNotes}
													disabled={editSubmitting}
													maxlength={2000}
												/>
											</div>
											<div class="flex gap-2">
												<Button type="submit" size="sm" disabled={editSubmitting}>
													{editSubmitting ? 'Saving…' : 'Save'}
												</Button>
												<Button
													type="button"
													size="sm"
													variant="outline"
													onclick={cancelEdit}
													disabled={editSubmitting}
												>
													Cancel
												</Button>
											</div>
										</form>
									{:else}
										<div class="flex items-start justify-between gap-3">
											<div class="space-y-1">
												<p class="font-medium">
													<span class="font-mono text-xs text-muted-foreground">{room.code}</span>
													·
													{room.name}
													{#if room.status !== 'ACTIVE'}
														<span
															class="ml-2 rounded bg-muted px-2 py-0.5 text-xs text-muted-foreground uppercase"
														>
															{room.status}
														</span>
													{/if}
												</p>
												{#if room.notes}
													<p class="text-sm text-muted-foreground">{room.notes}</p>
												{/if}
												<p class="text-xs text-muted-foreground">
													Added {formatDate(room.createdAt)}
												</p>
											</div>
											{#if isManager}
												<div class="flex shrink-0 gap-2">
													<Button
														variant="outline"
														size="sm"
														onclick={() => startEdit(room)}
														disabled={archivingId === room.id}
													>
														Edit
													</Button>
													<Button
														variant="outline"
														size="sm"
														onclick={() => archive(room)}
														disabled={archivingId === room.id}
													>
														{archivingId === room.id ? 'Archiving…' : 'Archive'}
													</Button>
												</div>
											{/if}
										</div>
									{/if}
								</Card.Content>
							</Card.Root>
						</li>
					{/each}
				</ul>
			{/if}
		</section>
	</div>
</main>
