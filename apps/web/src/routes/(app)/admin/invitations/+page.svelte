<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_ } from '$lib/auth/api';
	import {
		adminInvitationsApi,
		type CreateInvitationRequest,
		type InvitationDto
	} from '$lib/api/admin/invitations';
	import type { AdminUserRole, AdminUserSpecialty } from '$lib/api/admin/users';

	const SPECIALTIES: ReadonlyArray<AdminUserSpecialty> = [
		'CARDIOLOGY',
		'GENERAL',
		'NEUROSURGERY',
		'OPHTHALMOLOGY',
		'ORTHOPEDICS'
	];

	let invitations = $state<readonly InvitationDto[]>([]);
	let includeHistory = $state(false);
	let loading = $state(true);
	let listError = $state<string | null>(null);

	// Create form
	let newEmail = $state('');
	let newRole = $state<AdminUserRole>('STAFF');
	let newIsSurgeon = $state(false);
	let newSpecialty = $state<AdminUserSpecialty>('GENERAL');
	let creating = $state(false);

	let revokingId = $state<string | null>(null);

	onMount(refresh);

	async function refresh() {
		loading = true;
		listError = null;
		try {
			invitations = await adminInvitationsApi.list(includeHistory);
		} catch (e) {
			listError = describe(e);
		} finally {
			loading = false;
		}
	}

	async function create(event: SubmitEvent) {
		event.preventDefault();
		const email = newEmail.trim();
		if (!email) {
			toast.error('Email is required.');
			return;
		}
		const payload: CreateInvitationRequest = {
			email,
			role: newRole,
			isSurgeon: newIsSurgeon,
			...(newIsSurgeon ? { specialty: newSpecialty } : {})
		};
		creating = true;
		try {
			const created = await adminInvitationsApi.create(payload);
			invitations = [created, ...invitations];
			newEmail = '';
			newIsSurgeon = false;
			newRole = 'STAFF';
			newSpecialty = 'GENERAL';
			toast.success(`Invitation sent to ${created.email}.`);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			creating = false;
		}
	}

	async function revoke(invite: InvitationDto) {
		const ok = confirm(
			`Revoke the invitation for ${invite.email}? ` +
				`The recipient's link will stop working immediately.`
		);
		if (!ok) return;
		revokingId = invite.id;
		try {
			await adminInvitationsApi.revoke(invite.id);
			invitations = invitations.map((i) =>
				i.id === invite.id ? { ...i, revokedAt: new Date().toISOString() } : i
			);
			toast.success(`Revoked invitation to ${invite.email}.`);
		} catch (e) {
			toast.error(describe(e));
		} finally {
			revokingId = null;
		}
	}

	function describe(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'INVITATION_PENDING':
				return 'A pending invitation already exists for that email. Revoke it first.';
			case 'USER_ALREADY_EXISTS':
				return 'A user with that email already exists. Use the Users page to change their role.';
			case 'ALREADY_TERMINAL':
				return 'Invitation has already been accepted or revoked.';
			case 'NOT_FOUND':
				return 'Invitation not found — refresh the list.';
			case 'VALIDATION_ERROR':
				return e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
			default:
				return e.payload.message || 'Server error. Please try again.';
		}
	}

	function formatDateTime(iso: string | null | undefined): string {
		if (!iso) return '—';
		return new Date(iso).toLocaleString(undefined, {
			dateStyle: 'medium',
			timeStyle: 'short'
		});
	}

	function statusOf(invite: InvitationDto): string {
		if (invite.revokedAt) return 'revoked';
		if (invite.acceptedAt) return 'accepted';
		if (new Date(invite.expiresAt) < new Date()) return 'expired';
		return 'pending';
	}

	function statusBadge(status: string): string {
		switch (status) {
			case 'pending':
				return 'bg-primary/15 text-primary border-primary/30';
			case 'accepted':
				return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30';
			case 'expired':
			case 'revoked':
				return 'bg-muted text-muted-foreground border-border';
			default:
				return 'bg-muted text-muted-foreground border-border';
		}
	}
</script>

<svelte:head>
	<title>Invitations · Admin · Kliniq</title>
</svelte:head>

<div class="mx-auto w-full max-w-4xl space-y-6">
	<header class="space-y-1">
		<h1 class="text-3xl font-bold tracking-tight">Invitations</h1>
		<p class="text-sm text-muted-foreground">
			Issue email-based invitations with a pre-set role and surgeon flag. The recipient picks their
			own password on the accept page; the new account lands verified, no separate email round-trip.
		</p>
	</header>

	<!-- Create -->
	<Card.Root class="rounded-2xl">
		<Card.Header class="px-6 pt-6">
			<Card.Title>Invite a new user</Card.Title>
			<Card.Description>
				An email goes out with a one-shot accept link valid for 7 days.
			</Card.Description>
		</Card.Header>
		<Card.Content class="px-6 pb-6">
			<form onsubmit={create} class="space-y-4">
				<div class="grid gap-4 sm:grid-cols-2">
					<div class="space-y-2">
						<Label for="inv-email">Email</Label>
						<Input
							id="inv-email"
							type="email"
							bind:value={newEmail}
							disabled={creating}
							maxlength={254}
							placeholder="newdoc@example.com"
							required
						/>
					</div>
					<div class="space-y-2">
						<Label for="inv-role">Role</Label>
						<select
							id="inv-role"
							bind:value={newRole}
							disabled={creating}
							class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
						>
							<option value="STAFF">Staff</option>
							<option value="MANAGER">Manager</option>
							<option value="ADMIN">Admin</option>
						</select>
					</div>
				</div>
				<div class="grid gap-4 sm:grid-cols-2">
					<label class="flex items-center gap-2 text-sm">
						<input
							type="checkbox"
							bind:checked={newIsSurgeon}
							disabled={creating}
							class="h-4 w-4"
						/>
						Flag as surgeon
					</label>
					{#if newIsSurgeon}
						<div class="space-y-2">
							<Label for="inv-specialty">Specialty</Label>
							<select
								id="inv-specialty"
								bind:value={newSpecialty}
								disabled={creating}
								class="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-sm ring-offset-background focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:outline-none"
							>
								{#each SPECIALTIES as s (s)}
									<option value={s}>{s.toLowerCase()}</option>
								{/each}
							</select>
						</div>
					{/if}
				</div>
				<Button type="submit" disabled={creating}>
					{creating ? 'Sending…' : 'Send invitation'}
				</Button>
			</form>
		</Card.Content>
	</Card.Root>

	<!-- List -->
	<section class="space-y-3">
		<div class="flex items-center justify-between">
			<h2 class="text-xl font-semibold">All invitations</h2>
			<label class="flex items-center gap-2 text-sm">
				<input type="checkbox" bind:checked={includeHistory} onchange={refresh} class="h-4 w-4" />
				Include accepted &amp; revoked
			</label>
		</div>

		{#if loading}
			<p class="text-sm text-muted-foreground">Loading…</p>
		{:else if listError}
			<p
				class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				role="alert"
			>
				{listError}
			</p>
		{:else if invitations.length === 0}
			<Card.Root class="rounded-2xl">
				<Card.Content class="px-6 py-8 text-center text-sm text-muted-foreground">
					No invitations to show.
				</Card.Content>
			</Card.Root>
		{:else}
			<ul class="space-y-3">
				{#each invitations as invite (invite.id)}
					{@const status = statusOf(invite)}
					<li>
						<Card.Root class="rounded-2xl">
							<Card.Content class="space-y-3 px-6 py-5">
								<div class="flex items-start justify-between gap-3">
									<div class="space-y-1">
										<p class="font-medium">
											{invite.email}
											<span
												class="ml-2 inline-flex items-center rounded-full border px-2 py-0.5 text-xs tracking-wide uppercase {statusBadge(
													status
												)}"
											>
												{status}
											</span>
										</p>
										<p class="text-sm text-muted-foreground">
											{invite.role.toLowerCase()}
											{#if invite.isSurgeon}
												· surgeon ({invite.specialty?.toLowerCase()})
											{/if}
										</p>
										<p class="text-xs text-muted-foreground">
											Issued {formatDateTime(invite.issuedAt)} · Expires {formatDateTime(
												invite.expiresAt
											)}
											{#if invite.acceptedAt}
												· Accepted {formatDateTime(invite.acceptedAt)}
											{/if}
											{#if invite.revokedAt}
												· Revoked {formatDateTime(invite.revokedAt)}
											{/if}
										</p>
									</div>
									{#if status === 'pending'}
										<Button
											variant="outline"
											size="sm"
											onclick={() => revoke(invite)}
											disabled={revokingId === invite.id}
										>
											{revokingId === invite.id ? 'Revoking…' : 'Revoke'}
										</Button>
									{/if}
								</div>
							</Card.Content>
						</Card.Root>
					</li>
				{/each}
			</ul>
		{/if}
	</section>
</div>
