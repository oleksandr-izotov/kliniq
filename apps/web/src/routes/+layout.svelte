<script lang="ts">
	import '../app.css';
	import { Toaster } from '$lib/components/ui/sonner';

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href="/icon.svg" />
</svelte:head>

{@render children()}

<Toaster richColors closeButton position="top-right" />
