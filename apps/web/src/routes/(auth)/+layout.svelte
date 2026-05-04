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

<!--
	Mobile (<lg): single column, scrollable, mobile logo at the top of the form.
	Desktop (>=lg): full-viewport split. Left = full-bleed illustration with
	the logo overlaid top-left and the tagline overlaid bottom. Right = form
	panel, vertically centered, scrolls only if the form content overflows.
-->
<div class="grid bg-background lg:h-screen lg:grid-cols-2 lg:overflow-hidden">
	<!-- Brand panel — desktop only -->
	<aside class="relative hidden bg-muted lg:block">
		<img
			src="/illustrations/login-side.png"
			alt=""
			class="absolute inset-0 h-full w-full object-cover"
			loading="eager"
		/>

		<!-- Logo, top-left corner overlay -->
		<header class="absolute top-8 left-8 flex items-center gap-2">
			<img src="/icon.svg" alt="Kliniq logo" class="h-9 w-9 rounded-lg shadow-sm" />
			<span class="text-xl font-bold tracking-tight text-foreground">kliniq</span>
		</header>

		<!-- Tagline, bottom overlay with a gentle gradient so it stays readable -->
		<div
			class="absolute inset-x-0 bottom-0 bg-gradient-to-t from-background/85 to-transparent px-12 pt-32 pb-10"
		>
			<p class="text-lg leading-snug font-medium text-foreground">Scheduling for modern clinics.</p>
			<p class="mt-1 text-sm text-muted-foreground">
				Bookings, schedules, and audit trails in one place.
			</p>
		</div>
	</aside>

	<!-- Form panel -->
	<main class="flex items-center justify-center overflow-y-auto p-6 lg:p-12">
		<div class="w-full max-w-lg">
			<!-- Mobile-only logo (the brand panel is hidden < lg) -->
			<div class="mb-8 flex items-center gap-2 lg:hidden">
				<img src="/icon.svg" alt="Kliniq" class="h-8 w-8 rounded-md" />
				<span class="text-lg font-bold tracking-tight text-foreground">kliniq</span>
			</div>
			{@render children()}
		</div>
	</main>
</div>
