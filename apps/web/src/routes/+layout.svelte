<script lang="ts">
	import '../app.css';
	import { ModeWatcher } from 'mode-watcher';
	import { Toaster } from '$lib/components/ui/sonner';

	let { children } = $props();
</script>

<svelte:head>
	<link rel="icon" href="/icon.svg" />
	<meta
		name="description"
		content="Kliniq — operating-room scheduling for modern clinics. Bookings, schedules, and audit trails in one place."
	/>
</svelte:head>

<!-- Sets `class="dark"` on <html> based on stored choice + system pref. -->
<ModeWatcher />

{@render children()}

<Toaster richColors closeButton position="top-right" />
