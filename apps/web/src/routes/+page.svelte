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

<main class="bg-background text-foreground flex min-h-screen items-center justify-center p-6">
	<div class="w-full max-w-md space-y-6 text-center">
		<header class="space-y-2">
			<h1 class="text-primary text-5xl font-bold tracking-tight">kliniq</h1>
			<p class="text-muted-foreground text-sm">Sprint 0 · frontend ↔ api boot test</p>
		</header>

		<section class="bg-card border-border rounded-lg border p-6 text-left shadow-sm">
			<div class="text-muted-foreground mb-2 text-xs uppercase tracking-wide">api health</div>
			{#if loading}
				<p class="text-muted-foreground text-sm">checking…</p>
			{:else if error}
				<p class="text-destructive text-sm">error: {error}</p>
			{:else}
				<pre class="bg-muted text-foreground overflow-x-auto rounded p-3 text-sm">{JSON.stringify(
						health,
						null,
						2
					)}</pre>
			{/if}
		</section>

		<button
			onclick={checkHealth}
			class="bg-primary text-primary-foreground hover:bg-primary/90 ring-ring focus-visible:ring-ring inline-flex h-10 items-center justify-center rounded-md px-4 text-sm font-medium transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:opacity-50"
			disabled={loading}
		>
			recheck
		</button>
	</div>
</main>
