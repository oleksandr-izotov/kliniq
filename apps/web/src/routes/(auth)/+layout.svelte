<script lang="ts">
	import { onMount } from 'svelte';
	import { authApi } from '$lib/auth/api';

	let { children } = $props();

	// Seed the XSRF cookie on first paint so the form's POST has a valid
	// header. Failing silently is fine: the user's first POST without the
	// header gets a 403 toast and they retry.
	onMount(() => {
		void authApi.primeCsrf();
	});
</script>

<div class="grid min-h-screen bg-background lg:grid-cols-2">
	<!-- Brand panel — hidden on mobile to save space for the form -->
	<aside class="hidden flex-col justify-between bg-muted p-12 lg:flex">
		<header class="flex items-center gap-2">
			<img src="/icon.svg" alt="Kliniq logo" class="h-9 w-9 rounded-lg" />
			<span class="text-xl font-bold tracking-tight text-foreground">kliniq</span>
		</header>

		<div class="flex flex-col items-center gap-6">
			<img src="/illustrations/login-side.png" alt="" class="w-full max-w-md" loading="eager" />
			<p class="text-center text-sm text-muted-foreground">Scheduling for modern clinics.</p>
		</div>

		<footer class="text-xs text-muted-foreground">© 2026 Kliniq · all rights reserved</footer>
	</aside>

	<!-- Form panel -->
	<main class="flex items-center justify-center p-6 lg:p-12">
		<div class="w-full max-w-sm">
			<!-- Mobile-only logo (the brand panel is hidden < lg) -->
			<div class="mb-8 flex items-center gap-2 lg:hidden">
				<img src="/icon.svg" alt="Kliniq" class="h-8 w-8 rounded-md" />
				<span class="text-lg font-bold tracking-tight text-foreground">kliniq</span>
			</div>
			{@render children()}
		</div>
	</main>
</div>
