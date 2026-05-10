<script lang="ts">
	import { page } from '$app/state';
	import { cn } from '$lib/utils';
	import type { Snippet } from 'svelte';

	let { children }: { children?: Snippet } = $props();

	const tabs = [
		{ href: '/admin/users', label: 'Users' },
		{ href: '/admin/invitations', label: 'Invitations' },
		{ href: '/admin/audit', label: 'Audit log' }
	] as const;

	function isActive(href: string): boolean {
		return page.url.pathname === href || page.url.pathname.startsWith(`${href}/`);
	}
</script>

<main class="min-h-screen bg-background p-6">
	<div class="mx-auto w-full max-w-7xl space-y-6">
		<header class="space-y-3">
			<a href="/" class="text-sm text-muted-foreground hover:text-foreground">← Back to home</a>
			<nav class="border-b" aria-label="Admin sections">
				<ul class="-mb-px flex gap-1">
					{#each tabs as tab (tab.href)}
						<li>
							<a
								href={tab.href}
								class={cn(
									'inline-flex items-center border-b-2 px-4 py-2 text-sm font-medium transition-colors',
									isActive(tab.href)
										? 'border-foreground text-foreground'
										: 'border-transparent text-muted-foreground hover:border-muted-foreground hover:text-foreground'
								)}
								aria-current={isActive(tab.href) ? 'page' : undefined}
							>
								{tab.label}
							</a>
						</li>
					{/each}
				</ul>
			</nav>
		</header>

		{@render children?.()}
	</div>
</main>
