<script lang="ts">
	import { page } from '$app/state';
	import { Button } from '$lib/components/ui/button';

	// `page.status` is whatever SvelteKit set when routing failed (404 for an
	// unknown path) or whatever a load function threw. `page.error.message`
	// carries the human-readable text either of those produced.
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
	// One illustration per status family: 404 gets its bespoke art, anything
	// 5xx (or unhandled) gets the matching "something broke" art. 401/403
	// reuse 404 because the visual mood (gentle, "you took a wrong turn")
	// fits better than the heavier 500 art.
	const illustration = $derived(
		status >= 500 ? '/illustrations/error-500.png' : '/illustrations/error-404.png'
	);
</script>

<svelte:head>
	<title>{status} · {title} · Kliniq</title>
</svelte:head>

<main class="grid min-h-screen place-items-center bg-background p-6">
	<div class="w-full max-w-md space-y-6 text-center">
		<img
			src={illustration}
			alt=""
			class="mx-auto h-48 w-auto"
			width="512"
			height="512"
			loading="eager"
		/>

		<div class="space-y-2">
			<p class="text-sm font-medium tracking-widest text-muted-foreground uppercase">
				Error {status}
			</p>
			<h1 class="text-3xl font-bold tracking-tight">{title}</h1>
			<p class="text-sm text-muted-foreground">{blurb}</p>
		</div>

		{#if page.error?.message && page.error.message !== title}
			<p class="rounded-md border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
				{page.error.message}
			</p>
		{/if}

		<Button href={cta.href}>{cta.label} →</Button>
	</div>
</main>
