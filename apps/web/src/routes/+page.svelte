<script lang="ts">
	type ApiHealth = { status: string; [key: string]: unknown };

	let health = $state<ApiHealth | null>(null);
	let error = $state<string | null>(null);
	let loading = $state(true);

	async function checkHealth() {
		loading = true;
		error = null;
		try {
			const res = await fetch('/actuator/health');
			if (!res.ok) {
				throw new Error(`HTTP ${res.status}`);
			}
			health = await res.json();
		} catch (e) {
			error = e instanceof Error ? e.message : String(e);
		} finally {
			loading = false;
		}
	}

	$effect(() => {
		void checkHealth();
	});
</script>

<svelte:head>
	<title>Kliniq · Sprint 0</title>
</svelte:head>

<main class="flex min-h-screen items-center justify-center bg-background p-6 text-foreground">
	<div class="w-full max-w-md space-y-6 text-center">
		<header class="space-y-2">
			<h1 class="text-5xl font-bold tracking-tight text-primary">kliniq</h1>
			<p class="text-sm text-muted-foreground">Sprint 0 · frontend ↔ api boot test</p>
		</header>

		<section class="rounded-lg border border-border bg-card p-6 text-left shadow-sm">
			<div class="mb-2 text-xs tracking-wide text-muted-foreground uppercase">api health</div>
			{#if loading}
				<p class="text-sm text-muted-foreground">checking…</p>
			{:else if error}
				<p class="text-sm text-destructive">error: {error}</p>
			{:else}
				<pre class="overflow-x-auto rounded bg-muted p-3 text-sm text-foreground">{JSON.stringify(
						health,
						null,
						2
					)}</pre>
			{/if}
		</section>

		<button
			onclick={checkHealth}
			class="inline-flex h-10 items-center justify-center rounded-md bg-primary px-4 text-sm font-medium text-primary-foreground ring-ring transition-colors hover:bg-primary/90 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none disabled:opacity-50"
			disabled={loading}
		>
			recheck
		</button>
	</div>
</main>
