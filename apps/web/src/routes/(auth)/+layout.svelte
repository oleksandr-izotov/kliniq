<script lang="ts">
	import ModeToggle from '$lib/components/ModeToggle.svelte';

	let { children } = $props();

	// CSRF cookie seeding is handled lazily inside `apiRequest` — the first
	// state-changing call fetches /api/v1/auth/me to pick up the cookie if
	// it's missing. Doing it here on `onMount` would just add one console
	// error from the 401 the anonymous probe earns, with no functional gain.
</script>

<!--
	Mobile (<lg): single column, scrollable, mobile logo at the top of the form.
	Desktop (>=lg): full-viewport split. Left = full-bleed illustration with
	the logo overlaid top-left and the tagline overlaid bottom. Right = form
	panel, vertically centered, scrolls only if the form content overflows.
-->
<div class="grid bg-background lg:h-screen lg:grid-cols-2 lg:overflow-hidden">
	<!-- Brand panel — desktop only. Theme-aware schedule illustration with a
		logo top-left and a tagline bottom over a protection gradient. -->
	<aside class="relative hidden overflow-hidden bg-muted lg:block">
		<img
			src="/illustrations/auth-light.webp"
			alt=""
			class="absolute inset-0 h-full w-full object-cover dark:hidden"
			decoding="async"
			aria-hidden="true"
		/>
		<img
			src="/illustrations/auth-dark.webp"
			alt=""
			class="absolute inset-0 hidden h-full w-full object-cover dark:block"
			decoding="async"
			aria-hidden="true"
		/>
		<!-- Emerald wash to tie the art to the brand -->
		<div class="absolute inset-0 bg-primary/10 mix-blend-multiply dark:mix-blend-screen"></div>

		<!-- Logo, top-left corner overlay -->
		<header class="absolute top-8 left-8 z-10 flex items-center gap-2.5">
			<img src="/brand/kliniq-icon.svg" alt="Kliniq logo" class="brand-glow size-9 rounded-[9px]" />
			<span class="text-xl font-bold tracking-[-0.03em] text-foreground">Kliniq</span>
		</header>

		<!-- Tagline, bottom overlay with a protection gradient so it stays readable -->
		<div
			class="absolute inset-x-0 bottom-0 z-10 bg-gradient-to-t from-background via-background/80 to-transparent px-12 pt-32 pb-10"
		>
			<p class="t-h2 font-semibold text-foreground">Scheduling for modern clinics.</p>
			<p class="mt-1 text-sm text-muted-foreground">
				Bookings, schedules, and audit trails in one place.
			</p>
		</div>
	</aside>

	<!-- Form panel -->
	<main class="relative flex items-center justify-center overflow-y-auto p-6 lg:p-12">
		<!-- Ambient aurora behind the form (subtle) -->
		<div class="aurora" aria-hidden="true"></div>
		<div class="absolute top-4 right-4 z-10">
			<ModeToggle />
		</div>
		<div class="relative z-10 w-full max-w-sm">
			<!-- Mobile-only logo (the brand panel is hidden < lg) -->
			<div class="mb-8 flex items-center gap-2.5 lg:hidden">
				<img src="/brand/kliniq-icon.svg" alt="Kliniq" class="brand-glow size-8 rounded-lg" />
				<span class="text-lg font-bold tracking-[-0.03em] text-foreground">Kliniq</span>
			</div>
			{@render children()}
		</div>
	</main>
</div>
