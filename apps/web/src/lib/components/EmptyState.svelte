<script lang="ts">
	import type { Snippet } from 'svelte';

	// Branded empty state: a line-art illustration over a one-line "what to do
	// next" hint. Used wherever a list / table / grid has zero rows so the user
	// sees guidance instead of a blank panel. Illustrations live in
	// /static/illustrations as resized webp (see Sprint 6 Day 65) and are
	// purely decorative — alt="" keeps them out of the accessibility tree.
	type Props = {
		/** Path under /static, e.g. "/illustrations/empty-bookings.webp". */
		image: string;
		title: string;
		/** One-line hint. Omit when `children` renders richer guidance (e.g. a link). */
		description?: string;
		/** Tailwind height for the illustration; smaller for dense lists like the audit log. */
		size?: 'sm' | 'md';
		/** Optional action area (CTA button, link) rendered below the copy. */
		children?: Snippet;
	};

	let { image, title, description, size = 'md', children }: Props = $props();
</script>

<div class="flex flex-col items-center justify-center px-6 py-10 text-center">
	<img
		src={image}
		alt=""
		aria-hidden="true"
		loading="lazy"
		decoding="async"
		class={['mb-5 w-auto opacity-90 select-none', size === 'sm' ? 'h-24 sm:h-28' : 'h-32 sm:h-40']}
	/>
	<h3 class="text-base font-semibold text-foreground">{title}</h3>
	{#if description}
		<p class="mt-1 max-w-sm text-sm leading-relaxed text-muted-foreground">{description}</p>
	{/if}
	{#if children}
		<div class="mt-4">
			{@render children()}
		</div>
	{/if}
</div>
