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
	Full-bleed illustration as hero background; text + CTA float over it in
	a frosted card so the line-art (especially the green door at the
	composition's centre) reads through behind the type. The illustration
	is light-only — colours are pinned to slate / emerald rather than the
	theme-aware tokens so the layout stays legible regardless of the
	user's dark/light preference.
-->
<main class="relative grid min-h-screen place-items-center overflow-hidden bg-stone-50">
	<img
		src={illustration}
		alt=""
		class="absolute inset-0 h-full w-full object-cover"
		decoding="async"
		aria-hidden="true"
	/>

	<!-- Logo top-left, on top of the illustration -->
	<header class="absolute top-6 left-6 z-10 flex items-center gap-2">
		<img src="/icon.svg" alt="Kliniq logo" class="h-9 w-9 rounded-lg shadow-sm" />
		<span class="text-xl font-bold tracking-tight text-slate-900">kliniq</span>
	</header>

	<!-- Frosted-glass text card, vertically centred -->
	<div
		class="relative z-10 mx-6 w-full max-w-md space-y-5 rounded-3xl border border-white/40 bg-white/80 p-8 text-center shadow-2xl backdrop-blur-md"
	>
		<p class="text-xs font-semibold tracking-widest text-emerald-600 uppercase">
			Error {status}
		</p>
		<h1 class="text-4xl font-bold tracking-tight text-slate-900">{title}</h1>
		<p class="text-base leading-relaxed text-slate-600">{blurb}</p>
		<div class="pt-2">
			<Button href={cta.href} size="lg">{cta.label} →</Button>
		</div>
	</div>
</main>
