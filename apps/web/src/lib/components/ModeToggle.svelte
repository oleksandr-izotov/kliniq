<script lang="ts">
	import { Button } from '$lib/components/ui/button';
	import MoonIcon from '@lucide/svelte/icons/moon';
	import SunIcon from '@lucide/svelte/icons/sun';
	import { mode, toggleMode } from 'mode-watcher';

	// `mode.current` is `'light' | 'dark'` once mode-watcher has resolved the
	// system pref / persisted choice. Before then it can be undefined; in
	// that case render the moon (dark icon) since we'll be revealing a
	// "switch to dark" action by default on a light surface.
	const dark = $derived(mode.current === 'dark');
</script>

<Button variant="outline" size="sm" onclick={toggleMode} aria-label="Toggle dark mode">
	{#if dark}
		<SunIcon class="size-4" />
	{:else}
		<MoonIcon class="size-4" />
	{/if}
</Button>
