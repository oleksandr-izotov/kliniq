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
	<!-- Brand panel — desktop only -->
	<aside class="relative hidden bg-muted lg:block">
		<!--
			`<picture>` with `media="(min-width: 1024px)"` gates the network
			fetch behind the same breakpoint that toggles `lg:block`. On
			mobile the source doesn't match, browsers fall through to the
			tiny inline-SVG `<img>` (≈100 bytes), and we save 1 MB on the
			critical-path. The WebP source then knocks the desktop fetch
			down 30× (1.06 MB → 35 KB) without touching visual fidelity.
		-->
		<picture>
			<source
				media="(min-width: 1024px)"
				type="image/webp"
				srcset="/illustrations/login-side.webp"
			/>
			<source media="(min-width: 1024px)" type="image/png" srcset="/illustrations/login-side.png" />
			<img
				src="data:image/svg+xml;utf8,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E"
				alt=""
				class="absolute inset-0 h-full w-full object-cover"
				width="1200"
				height="1200"
				decoding="async"
			/>
		</picture>

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
	<main class="relative flex items-center justify-center overflow-y-auto p-6 lg:p-12">
		<div class="absolute top-4 right-4">
			<ModeToggle />
		</div>
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
