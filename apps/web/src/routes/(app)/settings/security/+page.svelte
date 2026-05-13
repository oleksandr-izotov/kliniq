<script lang="ts">
	import { onMount } from 'svelte';
	import { toast } from 'svelte-sonner';
	import { Button } from '$lib/components/ui/button';
	import * as Card from '$lib/components/ui/card';
	import { Input } from '$lib/components/ui/input';
	import { Label } from '$lib/components/ui/label';
	import { ApiError_, authApi } from '$lib/auth/api';
	import {
		passkeyApi,
		PasskeyCeremonyError,
		passkeysSupported,
		type PasskeySummary
	} from '$lib/auth/passkeys';

	const MIN_PASSWORD = 12;
	const MAX_NAME = 100;

	// ---- Change-password state -------------------------------------------
	let currentPassword = $state('');
	let newPassword = $state('');
	let confirmPassword = $state('');
	let changingPassword = $state(false);
	let passwordError = $state<string | null>(null);

	// ---- Passkey state ---------------------------------------------------
	let supported = $state(false);
	let loading = $state(true);
	let passkeys = $state<PasskeySummary[]>([]);
	let loadError = $state<string | null>(null);
	let newDeviceName = $state('');
	let registering = $state(false);
	let renamingId = $state<string | null>(null);
	let renameDraft = $state('');
	let renameSubmitting = $state(false);
	let revokingId = $state<string | null>(null);

	onMount(async () => {
		supported = passkeysSupported();
		if (supported) await refreshPasskeys();
		else loading = false;
	});

	// ---- Change password ------------------------------------------------

	async function changePassword(event: SubmitEvent) {
		event.preventDefault();
		passwordError = null;
		if (newPassword.length < MIN_PASSWORD) {
			passwordError = `New password must be at least ${MIN_PASSWORD} characters.`;
			return;
		}
		if (newPassword !== confirmPassword) {
			passwordError = 'New password and confirmation do not match.';
			return;
		}
		if (newPassword === currentPassword) {
			passwordError = 'The new password must differ from the current one.';
			return;
		}
		changingPassword = true;
		try {
			const r = await authApi.changePassword({ currentPassword, newPassword });
			toast.success(r.message ?? 'Password updated.');
			currentPassword = '';
			newPassword = '';
			confirmPassword = '';
		} catch (e) {
			passwordError = describePasswordError(e);
		} finally {
			changingPassword = false;
		}
	}

	function describePasswordError(e: unknown): string {
		if (!(e instanceof ApiError_)) return 'Network error. Please try again.';
		switch (e.payload.code) {
			case 'INVALID_CREDENTIALS':
				return 'The current password is incorrect.';
			case 'PASSWORD_BREACHED':
				return 'This password has appeared in a known data breach. Choose a different one.';
			case 'SAME_PASSWORD':
				return 'The new password must differ from the current one.';
			case 'NO_PASSWORD':
				return 'This account uses passkeys only. Add a password via the reset link.';
			case 'RATE_LIMITED':
				return 'Too many attempts. Please wait a minute and try again.';
			case 'VALIDATION_ERROR':
				return e.payload.fieldErrors?.length
					? e.payload.fieldErrors.map((f) => `${f.field}: ${f.message}`).join(' · ')
					: e.payload.message;
			default:
				return e.payload.message || 'Something went wrong. Please try again.';
		}
	}

	// ---- Passkey list / add / rename / revoke ----------------------------

	async function refreshPasskeys() {
		loading = true;
		loadError = null;
		try {
			passkeys = await passkeyApi.list();
		} catch (e) {
			loadError = describePasskeyError(e);
		} finally {
			loading = false;
		}
	}

	async function addPasskey(event: SubmitEvent) {
		event.preventDefault();
		const name = newDeviceName.trim();
		if (!name) {
			toast.error('Give the passkey a name first.');
			return;
		}
		registering = true;
		try {
			const summary = await passkeyApi.register(name);
			passkeys = [summary, ...passkeys];
			newDeviceName = '';
			toast.success(`Added "${summary.deviceName}".`);
		} catch (e) {
			if (e instanceof PasskeyCeremonyError) toast.error(e.message);
			else toast.error(describePasskeyError(e));
		} finally {
			registering = false;
		}
	}

	function startRename(p: PasskeySummary) {
		renamingId = p.id;
		renameDraft = p.deviceName;
	}

	function cancelRename() {
		renamingId = null;
		renameDraft = '';
	}

	async function submitRename(p: PasskeySummary, event: SubmitEvent) {
		event.preventDefault();
		const next = renameDraft.trim();
		if (!next) return toast.error('Name must not be empty.');
		if (next === p.deviceName) return cancelRename();
		renameSubmitting = true;
		try {
			await passkeyApi.rename(p.id, next);
			passkeys = passkeys.map((x) => (x.id === p.id ? { ...x, deviceName: next } : x));
			cancelRename();
			toast.success('Renamed.');
		} catch (e) {
			toast.error(describePasskeyError(e));
		} finally {
			renameSubmitting = false;
		}
	}

	async function revoke(p: PasskeySummary) {
		const proceed = confirm(
			`Revoke "${p.deviceName}"? You won't be able to sign in with it again.`
		);
		if (!proceed) return;
		revokingId = p.id;
		try {
			await passkeyApi.revoke(p.id);
			passkeys = passkeys.filter((x) => x.id !== p.id);
			toast.success('Revoked.');
		} catch (e) {
			toast.error(describePasskeyError(e));
		} finally {
			revokingId = null;
		}
	}

	function describePasskeyError(e: unknown): string {
		if (e instanceof ApiError_) {
			if (e.payload.code === 'RATE_LIMITED') return 'Too many attempts. Wait a minute and retry.';
			if (e.payload.code === 'PASSKEY_ALREADY_REGISTERED') {
				return 'This authenticator is already attached to an account.';
			}
			return e.payload.message || 'Server error. Please try again.';
		}
		return 'Network error. Please try again.';
	}

	function formatDate(iso: string | null): string {
		if (!iso) return '—';
		return new Date(iso).toLocaleString(undefined, {
			dateStyle: 'medium',
			timeStyle: 'short'
		});
	}
</script>

<svelte:head>
	<title>Security · Kliniq</title>
</svelte:head>

<main class="min-h-screen bg-background p-4 sm:p-6">
	<div class="mx-auto w-full max-w-2xl space-y-6">
		<header class="space-y-1">
			<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
			<h1 class="text-3xl font-bold tracking-tight">Security</h1>
			<p class="text-sm text-muted-foreground">
				Manage how you sign in. Changing your password signs out every other device automatically.
			</p>
		</header>

		<!-- Change password -->
		<Card.Root class="rounded-2xl">
			<Card.Header class="px-6 pt-6">
				<Card.Title>Change password</Card.Title>
				<Card.Description>
					You'll need to type your current password to confirm it's really you.
				</Card.Description>
			</Card.Header>
			<Card.Content class="px-6 pb-6">
				<form onsubmit={changePassword} class="space-y-4" novalidate>
					<div class="space-y-2">
						<Label for="currentPassword">Current password</Label>
						<Input
							id="currentPassword"
							type="password"
							autocomplete="current-password"
							bind:value={currentPassword}
							required
							disabled={changingPassword}
							maxlength={128}
						/>
					</div>

					<div class="space-y-2">
						<Label for="newPassword">New password</Label>
						<Input
							id="newPassword"
							type="password"
							autocomplete="new-password"
							bind:value={newPassword}
							required
							disabled={changingPassword}
							minlength={MIN_PASSWORD}
							maxlength={128}
							placeholder="at least {MIN_PASSWORD} characters"
						/>
					</div>

					<div class="space-y-2">
						<Label for="confirmPassword">Confirm new password</Label>
						<Input
							id="confirmPassword"
							type="password"
							autocomplete="new-password"
							bind:value={confirmPassword}
							required
							disabled={changingPassword}
							minlength={MIN_PASSWORD}
							maxlength={128}
						/>
					</div>

					{#if passwordError}
						<p
							class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
							role="alert"
						>
							{passwordError}
						</p>
					{/if}

					<Button type="submit" disabled={changingPassword}>
						{changingPassword ? 'Updating…' : 'Change password'}
					</Button>
				</form>
			</Card.Content>
		</Card.Root>

		<!-- Passkeys -->
		<section class="space-y-3">
			<h2 class="text-xl font-semibold">Passkeys</h2>

			{#if !supported}
				<Card.Root class="rounded-2xl border-destructive/30">
					<Card.Header class="px-6 pt-6">
						<Card.Title>Passkeys aren't supported in this browser</Card.Title>
						<Card.Description>
							Try a recent version of Safari, Chrome, Edge, or Firefox.
						</Card.Description>
					</Card.Header>
				</Card.Root>
			{:else}
				<Card.Root class="rounded-2xl">
					<Card.Header class="px-6 pt-6">
						<Card.Title>Add a passkey</Card.Title>
						<Card.Description>
							Give it a friendly name so you can recognise it later — your laptop, your phone, etc.
						</Card.Description>
					</Card.Header>
					<Card.Content class="px-6 pb-6">
						<form onsubmit={addPasskey} class="flex flex-col gap-3 sm:flex-row sm:items-end">
							<div class="flex-1 space-y-2">
								<Label for="deviceName">Device name</Label>
								<Input
									id="deviceName"
									type="text"
									bind:value={newDeviceName}
									placeholder="Work laptop"
									disabled={registering}
									maxlength={MAX_NAME}
									required
								/>
							</div>
							<Button type="submit" disabled={registering}>
								{registering ? 'Waiting for prompt…' : 'Add passkey'}
							</Button>
						</form>
					</Card.Content>
				</Card.Root>

				{#if loading}
					<p class="text-sm text-muted-foreground">Loading…</p>
				{:else if loadError}
					<p
						class="rounded-md border border-destructive/30 bg-destructive/10 px-3 py-2 text-sm text-destructive"
						role="alert"
					>
						{loadError}
					</p>
				{:else if passkeys.length === 0}
					<Card.Root class="rounded-2xl">
						<Card.Content class="px-6 py-8 text-center text-sm text-muted-foreground">
							No passkeys yet. Add one above to start signing in without a password.
						</Card.Content>
					</Card.Root>
				{:else}
					<ul class="space-y-3">
						{#each passkeys as p (p.id)}
							<li>
								<Card.Root class="rounded-2xl">
									<Card.Content class="space-y-3 px-6 py-5">
										{#if renamingId === p.id}
											<form onsubmit={(e) => submitRename(p, e)} class="flex gap-2">
												<Input
													type="text"
													bind:value={renameDraft}
													disabled={renameSubmitting}
													maxlength={MAX_NAME}
													required
													autofocus
												/>
												<Button type="submit" size="sm" disabled={renameSubmitting}>Save</Button>
												<Button
													type="button"
													size="sm"
													variant="outline"
													onclick={cancelRename}
													disabled={renameSubmitting}
												>
													Cancel
												</Button>
											</form>
										{:else}
											<div class="flex items-start justify-between gap-3">
												<div class="space-y-1">
													<p class="font-medium">{p.deviceName}</p>
													<p class="text-xs text-muted-foreground">
														Added {formatDate(p.createdAt)} · Last used {formatDate(p.lastUsedAt)}
													</p>
												</div>
												<div class="flex shrink-0 gap-2">
													<Button
														variant="outline"
														size="sm"
														onclick={() => startRename(p)}
														disabled={revokingId === p.id}
													>
														Rename
													</Button>
													<Button
														variant="outline"
														size="sm"
														onclick={() => revoke(p)}
														disabled={revokingId === p.id}
													>
														{revokingId === p.id ? 'Revoking…' : 'Revoke'}
													</Button>
												</div>
											</div>
										{/if}
									</Card.Content>
								</Card.Root>
							</li>
						{/each}
					</ul>
				{/if}
			{/if}
		</section>
	</div>
</main>
