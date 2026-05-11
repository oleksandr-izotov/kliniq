<script lang="ts">
	import { page } from '$app/state';
	import { Button } from '$lib/components/ui/button';

	// `page.status` is whatever SvelteKit set when routing failed (404 for an
	// unknown path) or whatever a load function threw. `page.error.message`
	// is the human-readable text either of those produced — duplicates the
	// title for plain 404s ("Not Found") so we don't render it separately.
	const status = $derived(page.status);
	const title = $derived(
		status === 404
			? 'Page not found'
			: status === 403
				? 'Not authorised'
				: status === 401
					? 'Sign-in required'
					: 'Something went wrong'
	);
	const blurb = $derived(
		status === 404
			? "We couldn't find that page. It might have moved, or never existed in the first place."
			: status === 403
				? "You're signed in, but this clinic area is locked to admins."
				: status === 401
					? 'Your session ran out. Sign in again to pick up where you left off.'
					: 'An unexpected error stopped the page from loading. The team has been notified — try again, or head back home.'
	);
	const cta = $derived(
		status === 401 ? { href: '/login', label: 'Sign in' } : { href: '/', label: 'Back to home' }
	);
	const illustration = $derived(
		status >= 500 ? '/illustrations/error-500.png' : '/illustrations/error-404.png'
	);
</script>

<svelte:head>
	<title>{status} · {title} · Kliniq</title>
</svelte:head>

<!--
	Mobile (<lg): single column, illustration top, text below, scrollable.
	Desktop (>=lg): full-viewport split, mirroring (auth)/+layout.svelte —
	illustration's natural light background becomes the left panel, text
	sits centered on the right against the page background. The illustration
	is `object-cover` so it fills the panel regardless of aspect ratio.
-->
<div class="grid min-h-screen bg-background lg:h-screen lg:grid-cols-2 lg:overflow-hidden">
	<!-- Illustration panel — visible on every breakpoint; full-bleed on lg+ -->
	<aside class="relative h-64 bg-muted lg:h-auto">
		<img
			src={illustration}
			alt=""
			class="absolute inset-0 h-full w-full object-cover"
			width="1200"
			height="1200"
			decoding="async"
		/>

		<!-- Logo, top-left corner -->
		<header class="absolute top-6 left-6 flex items-center gap-2 lg:top-8 lg:left-8">
			<img src="/icon.svg" alt="Kliniq logo" class="h-9 w-9 rounded-lg shadow-sm" />
			<span class="text-xl font-bold tracking-tight text-foreground">kliniq</span>
		</header>
	</aside>

	<!-- Text panel -->
	<main class="flex items-center justify-center p-6 lg:p-12">
		<div class="w-full max-w-md space-y-6">
			<div class="space-y-2">
				<p class="text-xs font-semibold tracking-widest text-primary uppercase">
					Error {status}
				</p>
				<h1 class="text-4xl font-bold tracking-tight">{title}</h1>
				<p class="text-base text-muted-foreground">{blurb}</p>
			</div>

			<Button href={cta.href} size="lg">{cta.label} →</Button>
		</div>
	</main>
</div>
