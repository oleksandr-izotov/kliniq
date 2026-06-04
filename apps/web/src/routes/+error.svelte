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
	const isServerError = $derived(status >= 500);
</script>

<svelte:head>
	<title>{status} · {title} · Kliniq</title>
</svelte:head>

<!--
	Pure-CSS error scene (no raster): a giant glowing status code with floating
	glass "schedule blocks" that gently bob over a radial emerald glow. Fully
	theme-aware via the design tokens; motion respects prefers-reduced-motion.
-->
<main
	class="relative grid min-h-screen place-items-center overflow-hidden bg-background px-6 text-foreground"
>
	<div class="aurora" aria-hidden="true"></div>

	<!-- Logo top-left -->
	<header class="absolute top-6 left-6 z-10 flex items-center gap-2.5">
		<img src="/brand/kliniq-icon.svg" alt="Kliniq logo" class="brand-glow size-9 rounded-[9px]" />
		<span class="text-xl font-bold tracking-[-0.03em]">Kliniq</span>
	</header>

	<!-- Floating glass schedule-blocks (decorative) -->
	<div class="pointer-events-none absolute inset-0 hidden sm:block" aria-hidden="true">
		<div class="float-block glass top-[24%] left-[14%] [animation-delay:0s]">
			<span class="block h-2 w-10 rounded-full bg-primary/40"></span>
			<span class="mt-1.5 block h-1.5 w-16 rounded-full bg-foreground/15"></span>
		</div>
		<div class="float-block glass top-[20%] right-[16%] [animation-delay:-1.6s]">
			<span class="block h-2 w-8 rounded-full bg-amber-500/50"></span>
			<span class="mt-1.5 block h-1.5 w-14 rounded-full bg-foreground/15"></span>
		</div>
		<div class="float-block glass bottom-[18%] left-[20%] [animation-delay:-3.1s]">
			<span class="block h-2 w-12 rounded-full bg-primary/40"></span>
			<span class="mt-1.5 block h-1.5 w-12 rounded-full bg-foreground/15"></span>
		</div>
		<div class="float-block glass right-[20%] bottom-[22%] [animation-delay:-2.2s]">
			<span class="block h-2 w-9 rounded-full bg-foreground/25"></span>
			<span class="mt-1.5 block h-1.5 w-16 rounded-full bg-foreground/15"></span>
		</div>
	</div>

	<!-- Centerpiece -->
	<div class="relative z-10 flex max-w-md flex-col items-center text-center">
		<p class="t-mono mb-2 text-xs font-semibold tracking-[0.2em] text-primary uppercase">
			Error {status}
		</p>
		<div class="code-number select-none">{status}</div>
		<h1 class="t-h1 mt-2">{title}</h1>
		<p class="mt-3 text-base leading-relaxed text-muted-foreground">{blurb}</p>
		<div class="mt-6 flex flex-wrap items-center justify-center gap-3">
			<Button href={cta.href} size="lg" class="cta-gradient">{cta.label} →</Button>
			{#if isServerError}
				<Button variant="outline" size="lg" onclick={() => location.reload()}>Try again</Button>
			{/if}
		</div>
	</div>
</main>

<style>
	.code-number {
		font-weight: 800;
		font-size: clamp(6rem, 22vw, 11rem);
		line-height: 0.9;
		letter-spacing: -0.04em;
		background: linear-gradient(
			135deg,
			var(--primary),
			color-mix(in oklch, var(--primary) 55%, var(--foreground))
		);
		-webkit-background-clip: text;
		background-clip: text;
		-webkit-text-fill-color: transparent;
		filter: drop-shadow(0 8px 30px color-mix(in oklch, var(--primary) 35%, transparent));
	}
	:global(.dark) .code-number {
		filter: drop-shadow(0 0 40px color-mix(in oklch, var(--primary) 55%, transparent));
	}
	.float-block {
		position: absolute;
		padding: 10px 12px;
		border-radius: 12px;
		box-shadow: var(--shadow-lg);
		animation: bob 6s ease-in-out infinite;
	}
	@keyframes bob {
		0%,
		100% {
			transform: translateY(0) rotate(-2deg);
		}
		50% {
			transform: translateY(-14px) rotate(2deg);
		}
	}
	@media (prefers-reduced-motion: reduce) {
		.float-block {
			animation: none;
		}
	}
</style>
