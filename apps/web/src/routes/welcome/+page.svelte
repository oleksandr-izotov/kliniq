<script lang="ts">
	import { onMount } from 'svelte';
	import ModeToggle from '$lib/components/ModeToggle.svelte';
	import SparklesIcon from '@lucide/svelte/icons/sparkles';
	import ArrowRightIcon from '@lucide/svelte/icons/arrow-right';
	import PlayIcon from '@lucide/svelte/icons/play';
	import CheckIcon from '@lucide/svelte/icons/check';
	import CrossIcon from '@lucide/svelte/icons/cross';
	import ActivityIcon from '@lucide/svelte/icons/activity';
	import StethoscopeIcon from '@lucide/svelte/icons/stethoscope';
	import HeartPulseIcon from '@lucide/svelte/icons/heart-pulse';
	import PlusCircleIcon from '@lucide/svelte/icons/plus-circle';
	import CalendarRangeIcon from '@lucide/svelte/icons/calendar-range';
	import ShieldCheckIcon from '@lucide/svelte/icons/shield-check';
	import RadioIcon from '@lucide/svelte/icons/radio';
	import ScrollTextIcon from '@lucide/svelte/icons/scroll-text';
	import DoorOpenIcon from '@lucide/svelte/icons/door-open';
	import MoonIcon from '@lucide/svelte/icons/moon';
	import MousePointerClickIcon from '@lucide/svelte/icons/mouse-pointer-click';
	import MoveIcon from '@lucide/svelte/icons/move';
	import CircleDotIcon from '@lucide/svelte/icons/circle-dot';
	import UsersIcon from '@lucide/svelte/icons/users';
	import BellIcon from '@lucide/svelte/icons/bell';
	import ClockIcon from '@lucide/svelte/icons/clock';
	import FingerprintIcon from '@lucide/svelte/icons/fingerprint';
	import ServerIcon from '@lucide/svelte/icons/server';
	import LockIcon from '@lucide/svelte/icons/lock';
	import ChevronDownIcon from '@lucide/svelte/icons/chevron-down';

	// ---- Nav shadow on scroll -------------------------------------------
	let scrolled = $state(false);
	onMount(() => {
		const onScroll = () => (scrolled = window.scrollY > 8);
		window.addEventListener('scroll', onScroll, { passive: true });
		onScroll();
		return () => window.removeEventListener('scroll', onScroll);
	});

	// ---- Reveal-on-scroll action (with 1.4s safety net) -----------------
	function reveal(node: HTMLElement) {
		const io = new IntersectionObserver(
			(entries) => {
				for (const e of entries) {
					if (e.isIntersecting) {
						e.target.classList.add('in');
						io.unobserve(e.target);
					}
				}
			},
			{ threshold: 0.12 }
		);
		io.observe(node);
		const t = setTimeout(() => node.classList.add('in'), 1400);
		return {
			destroy() {
				io.disconnect();
				clearTimeout(t);
			}
		};
	}

	// ---- Mini schedule mock (built declaratively) -----------------------
	const S = 8 * 60;
	const E = 17 * 60;
	const PPM = 0.34;
	const NOW = 11 * 60 + 30;
	const H = (E - S) * PPM;
	const pad = (n: number) => String(n).padStart(2, '0');
	const fm = (m: number) => `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;

	const msRooms = [
		{ c: 'OR-1', n: 'Saal 1' },
		{ c: 'OR-2', n: 'Saal 2' },
		{ c: 'OR-3', n: 'Saal 3' }
	];
	const msTicks = (() => {
		const out: { top: number; label: string }[] = [];
		for (let m = S; m <= E; m += 120) out.push({ top: (m - S) * PPM, label: fm(m) });
		return out;
	})();
	const msLines = (() => {
		const out: number[] = [];
		for (let m = S; m <= E; m += 60) out.push((m - S) * PPM);
		return out;
	})();
	type MB = { s: number; e: number; o: string; c: string };
	const rawBookings: MB[][] = [
		[
			{ s: 510, e: 600, o: 'Bypass', c: 'ms-c' },
			{ s: 630, e: 720, o: 'Klappe', c: 'ms-a' },
			{ s: 840, e: 960, o: 'Kraniotomie', c: 'ms-s' }
		],
		[
			{ s: 540, e: 630, o: 'Arthroskopie', c: 'ms-s' },
			{ s: 690, e: 780, o: 'Hernie', c: 'ms-s' }
		],
		[
			{ s: 510, e: 570, o: 'Konsil', c: 'ms-c' },
			{ s: 600, e: 690, o: 'Katarakt', c: 'ms-s' },
			{ s: 870, e: 960, o: 'Endoskopie', c: 'ms-s' }
		]
	];
	const msCols = rawBookings.map((list, i) => ({
		nowTop: i === 1 ? (NOW - S) * PPM : null,
		bookings: list.map((b) => ({
			top: (b.s - S) * PPM,
			height: (b.e - b.s) * PPM - 3,
			cls: b.c,
			time: fm(b.s),
			op: b.o
		}))
	}));

	const features = [
		{
			icon: CalendarRangeIcon,
			title: 'Tages- & Wochenplan',
			copy: 'Jeder Operationssaal auf einen Blick. Per Klick einen freien Slot buchen, per Drag & Drop verschieben — die Stunden-Raster halten alles im Blick.'
		},
		{
			icon: ShieldCheckIcon,
			title: 'Keine Doppelbuchungen',
			copy: 'Überschneidungen im selben Saal werden direkt auf Datenbankebene abgelehnt. Garantiert konfliktfrei — nicht erst durch nachträgliche Prüfung.'
		},
		{
			icon: RadioIcon,
			title: 'Echtzeit-Synchronisation',
			copy: 'Jede Buchung, Verschiebung oder Stornierung erscheint sofort auf allen Geräten und in allen geöffneten Tabs. Kein Aktualisieren nötig.'
		},
		{
			icon: ScrollTextIcon,
			title: 'Lückenloses Audit-Log',
			copy: 'Wer hat was wann geändert? Jede Aktion wird nachvollziehbar protokolliert — von der Buchung bis zur Wartungsmeldung.'
		},
		{
			icon: DoorOpenIcon,
			title: 'OP-Säle verwalten',
			copy: 'Säle anlegen, umbenennen, für Wartung sperren oder archivieren. Rollen für Admin, Manager und Chirurg:innen inklusive.'
		},
		{
			icon: MoonIcon,
			title: 'Hell & Dunkel',
			copy: 'Ein durchdachtes helles und dunkles Design für lange Schichten — schonend für die Augen, jederzeit umschaltbar.'
		}
	];

	const security = [
		{
			icon: FingerprintIcon,
			title: 'Passkey-Anmeldung',
			copy: 'Anmeldung per Face ID, Touch ID oder Sicherheitsschlüssel — ganz ohne Passwort und phishing-resistent.'
		},
		{
			icon: ServerIcon,
			title: 'In der EU gehostet',
			copy: 'Server und Daten verbleiben in der Europäischen Union. Schriften werden selbst gehostet — kein Tracking durch Dritte.'
		},
		{
			icon: ScrollTextIcon,
			title: 'Vollständiges Audit-Log',
			copy: 'Jede Aktion ist protokolliert und nachvollziehbar — für interne Revisionen und Compliance.'
		},
		{
			icon: LockIcon,
			title: 'Verschlüsselt',
			copy: 'Übertragung und Speicherung durchgehend verschlüsselt. Zugriff streng über Rollen geregelt.'
		}
	];

	const faqs = [
		{
			q: 'Wie verhindert Kliniq Doppelbuchungen?',
			a: 'Überschneidungen im selben Operationssaal werden direkt auf Datenbankebene abgelehnt — nicht erst durch eine nachträgliche Prüfung. Eine konfliktbehaftete Buchung kommt gar nicht erst zustande.',
			open: true
		},
		{
			q: 'Wo werden unsere Daten gespeichert?',
			a: 'Ausschließlich auf Servern innerhalb der Europäischen Union. Kliniq ist DSGVO-konform, Schriften werden selbst gehostet und es findet kein Tracking durch Dritte statt.',
			open: false
		},
		{
			q: 'Brauchen wir eine Installation oder spezielle Hardware?',
			a: 'Nein. Kliniq läuft im Browser — auf Desktop, Tablet und am Wandbildschirm. In der Regel sind Sie in unter fünf Minuten startklar.',
			open: false
		},
		{
			q: 'Können verschiedene Rollen unterschiedliche Rechte haben?',
			a: 'Ja. Admins und Manager verwalten Säle und Einstellungen, Chirurg:innen buchen und sehen den Plan. Jede Änderung landet nachvollziehbar im Audit-Log.',
			open: false
		},
		{
			q: 'Wie funktioniert die Echtzeit-Synchronisation?',
			a: 'Jede Buchung, Verschiebung oder Stornierung wird sofort an alle geöffneten Geräte und Tabs übertragen — ganz ohne manuelles Aktualisieren.',
			open: false
		},
		{
			q: 'Was kostet Kliniq nach der Testphase?',
			a: 'Die Testphase läuft 30 Tage kostenlos und ohne Kreditkarte. Danach rechnen wir transparent pro Operationssaal ab — sprechen Sie uns für ein Angebot an.',
			open: false
		}
	];
</script>

<svelte:head>
	<title>Kliniq — OP-Planung für moderne Kliniken</title>
	<meta
		name="description"
		content="Kliniq bündelt Operationssäle, Chirurg:innen und Buchungen in einem geteilten Echtzeit-Plan. Konfliktfrei, nachvollziehbar, DSGVO-konform."
	/>
</svelte:head>

<div class="landing" id="top">
	<!-- NAV -->
	<header class="nav" class:scrolled>
		<div class="wrap nav-in">
			<a class="brand" href="#top">
				<img src="/brand/kliniq-icon.svg" alt="" class="mark" />
				<b>Kliniq</b>
			</a>
			<nav class="nav-links">
				<a href="#funktionen">Funktionen</a>
				<a href="#ablauf">So funktioniert's</a>
				<a href="#sicherheit">Sicherheit</a>
				<a href="#faq">FAQ</a>
				<a href="#kontakt">Kontakt</a>
			</nav>
			<div class="nav-cta">
				<ModeToggle />
				<a class="btn btn-ghost btn-sm" href="/login">Anmelden</a>
				<a class="btn btn-pri btn-sm" href="/register">Kostenlos testen</a>
			</div>
		</div>
	</header>

	<main>
		<!-- HERO -->
		<section class="hero">
			<div class="wrap hero-grid">
				<div class="hero-text rv" use:reveal>
					<span class="eyebrow"><SparklesIcon /> Neu: Passkey-Anmeldung & Dunkelmodus</span>
					<h1>Ein OP-Plan, der sich <span class="g">nie überbucht.</span></h1>
					<p class="lead">
						Kliniq bündelt Operationssäle, Chirurg:innen und Buchungen in einem einzigen, geteilten
						Plan — in Echtzeit. Doppelbuchungen werden direkt in der Datenbank verhindert, jede
						Änderung erscheint sofort in jedem Browser-Tab.
					</p>
					<div class="hero-cta">
						<a class="btn btn-pri btn-lg" href="/register">Kostenlos testen <ArrowRightIcon /></a>
						<a class="btn btn-ghost btn-lg" href="#ablauf"><PlayIcon /> Demo ansehen</a>
					</div>
					<div class="hero-note">
						<CheckIcon /> 30 Tage kostenlos · keine Kreditkarte · in 5 Minuten startklar
					</div>
				</div>

				<div class="hero-visual rv" use:reveal>
					<div class="hero-bg" aria-hidden="true"></div>
					<div class="mock">
						<div class="mock-bar">
							<span class="dot" style="background:#f87171"></span>
							<span class="dot" style="background:#fbbf24"></span>
							<span class="dot" style="background:#34d399"></span>
							<span class="mb-title">kliniq.app/schedule · Mo, 12. Mai</span>
						</div>
						<div class="msched">
							<div class="ms-axh"></div>
							{#each msRooms as r (r.c)}
								<div class="ms-colh">
									<div class="c">{r.c}</div>
									<div class="n">{r.n}</div>
								</div>
							{/each}
							<div class="ms-axis" style:height="{H}px">
								{#each msTicks as t (t.label)}
									<span class="tk" style:top="{t.top}px">{t.label}</span>
								{/each}
							</div>
							{#each msCols as col, i (i)}
								<div class="ms-col" style:height="{H}px">
									{#each msLines as top (top)}
										<div class="ms-line" style:top="{top}px"></div>
									{/each}
									{#if col.nowTop !== null}
										<div class="ms-now" style:top="{col.nowTop}px"></div>
									{/if}
									{#each col.bookings as b (b.top)}
										<div class="ms-bk {b.cls}" style:top="{b.top}px" style:height="{b.height}px">
											<div class="t">{b.time}</div>
											<div class="o">{b.op}</div>
										</div>
									{/each}
								</div>
							{/each}
						</div>
					</div>
				</div>
			</div>
		</section>

		<!-- TRUST -->
		<section class="trust">
			<div class="wrap">
				<p>Vertraut von Kliniken und Tageschirurgie-Zentren im DACH-Raum</p>
				<div class="trust-row">
					<span><CrossIcon /> Riverside Klinik</span>
					<span><ActivityIcon /> NordMed</span>
					<span><StethoscopeIcon /> Praxis Hoffmann</span>
					<span><HeartPulseIcon /> Vitalis Zentrum</span>
					<span><PlusCircleIcon /> OrthoCare</span>
				</div>
			</div>
		</section>

		<!-- FEATURES -->
		<section class="sec" id="funktionen">
			<div class="wrap">
				<div class="sec-head rv" use:reveal>
					<span class="sec-tag">Funktionen</span>
					<h2>Alles für den OP-Tag — an einem Ort.</h2>
					<p>
						Vom ersten Klick bis zur lückenlosen Dokumentation. Kliniq ist für Klinikpersonal
						gemacht, das den ganzen Tag damit arbeitet — Klarheit vor Verspieltheit.
					</p>
				</div>
				<div class="feat-grid">
					{#each features as f (f.title)}
						{@const Icon = f.icon}
						<div class="feat rv" use:reveal>
							<div class="feat-ic"><Icon /></div>
							<h3>{f.title}</h3>
							<p>{f.copy}</p>
						</div>
					{/each}
				</div>
			</div>
		</section>

		<!-- HOW IT WORKS / SPLIT 1 -->
		<section class="sec" id="ablauf" style="padding-top:0">
			<div class="wrap split">
				<div class="split-text rv" use:reveal>
					<span class="sec-tag">So funktioniert's</span>
					<h2 style="margin-top:12px">Buchen in Sekunden — verschieben per Ziehen.</h2>
					<p>
						Der Plan zeigt jeden Operationssaal als eigene Spalte. Ein Klick auf einen freien Slot
						öffnet die Buchung, ein Ziehen verschiebt sie auf eine andere Zeit oder in einen anderen
						Saal.
					</p>
					<ul class="split-list">
						<li>
							<span class="ck"><MousePointerClickIcon /></span>
							<div>
								<b>Klicken & buchen.</b> Saal, Chirurg:in, Eingriff und Patient:innen-Referenz in einem
								schlanken Dialog.
							</div>
						</li>
						<li>
							<span class="ck"><MoveIcon /></span>
							<div>
								<b>Ziehen & verschieben.</b> Buchungen am Raster ausrichten — Überschneidungen werden
								sofort verhindert.
							</div>
						</li>
						<li>
							<span class="ck"><CircleDotIcon /></span>
							<div>
								<b>Status verfolgen.</b> Geplant, läuft, abgeschlossen oder storniert — farblich klar
								unterschieden.
							</div>
						</li>
					</ul>
				</div>
				<div class="split-visual rv" use:reveal>
					<img
						class="split-photo"
						src="/landing/photo-booking.webp"
						alt="Pflegekraft plant am Touchscreen"
					/>
				</div>
			</div>
		</section>

		<!-- SPLIT 2 (reversed) -->
		<section class="sec" style="padding-top:0">
			<div class="wrap split rev">
				<div class="split-visual rv" use:reveal>
					<img
						class="split-photo"
						src="/landing/photo-team.webp"
						alt="OP-Team koordiniert den Tag am Tablet"
					/>
				</div>
				<div class="split-text rv" use:reveal>
					<span class="sec-tag">Für das ganze Team</span>
					<h2 style="margin-top:12px">Ein gemeinsamer Plan, immer auf dem aktuellen Stand.</h2>
					<p>
						Schluss mit Tabellen, Zetteln und Telefonaten. Alle sehen denselben Plan in Echtzeit —
						egal ob am Empfang, im Vorbereitungsraum oder im Büro der OP-Leitung.
					</p>
					<ul class="split-list">
						<li>
							<span class="ck"><UsersIcon /></span>
							<div>
								<b>Rollen & Rechte.</b> Nur Manager und Admins ändern Säle und Einstellungen.
							</div>
						</li>
						<li>
							<span class="ck"><BellIcon /></span>
							<div>
								<b>Immer informiert.</b> Änderungen erscheinen sofort — ohne Nachfragen, ohne Verwechslungen.
							</div>
						</li>
						<li>
							<span class="ck"><ClockIcon /></span>
							<div>
								<b>Zeitzonen-sicher.</b> Arbeitszeiten, Zeitzone und Standard-Dauer pro Klinik konfigurierbar.
							</div>
						</li>
					</ul>
				</div>
			</div>
		</section>

		<!-- SECURITY BAND -->
		<section class="band sec" id="sicherheit">
			<div class="wrap">
				<div class="sec-head rv" use:reveal>
					<span class="sec-tag">Sicherheit & Datenschutz</span>
					<h2>Für sensible Daten gebaut.</h2>
					<p>
						Gesundheitsdaten verdienen den höchsten Schutz. Kliniq wird in der EU gehostet und
						erfüllt die Anforderungen der DSGVO.
					</p>
				</div>
				<div class="band-grid">
					{#each security as s (s.title)}
						{@const Icon = s.icon}
						<div class="bcard rv" use:reveal>
							<div class="bcard-ic"><Icon /></div>
							<h3>{s.title}</h3>
							<p>{s.copy}</p>
						</div>
					{/each}
				</div>
			</div>
		</section>

		<!-- STATS -->
		<section class="sec">
			<div class="wrap stats-row rv" use:reveal>
				<div>
					<div class="stat-v">0</div>
					<div class="stat-l">Doppelbuchungen — garantiert</div>
				</div>
				<div>
					<div class="stat-v">&lt;1s</div>
					<div class="stat-l">bis Änderungen synchron sind</div>
				</div>
				<div>
					<div class="stat-v">100%</div>
					<div class="stat-l">in der EU gehostet</div>
				</div>
				<div>
					<div class="stat-v">24/7</div>
					<div class="stat-l">verfügbar, auf jedem Gerät</div>
				</div>
			</div>
		</section>

		<!-- FAQ -->
		<section class="sec" id="faq" style="padding-top:0">
			<div class="wrap">
				<div class="sec-head rv" use:reveal>
					<span class="sec-tag">FAQ</span>
					<h2>Häufige Fragen.</h2>
					<p>Noch etwas offen? Schreiben Sie uns — wir antworten in der Regel am selben Tag.</p>
				</div>
				<div class="faq rv" use:reveal>
					{#each faqs as item (item.q)}
						<details class="faq-item" open={item.open}>
							<summary>{item.q}<ChevronDownIcon /></summary>
							<div class="faq-a">{item.a}</div>
						</details>
					{/each}
				</div>
			</div>
		</section>

		<!-- CTA -->
		<section class="sec" id="kontakt" style="padding-top:0">
			<div class="wrap">
				<div class="cta-band rv" use:reveal>
					<h2>Bereit für einen entspannteren OP-Tag?</h2>
					<p>
						Testen Sie Kliniq 30 Tage kostenlos. Keine Kreditkarte, kein Risiko — in fünf Minuten
						eingerichtet.
					</p>
					<div class="row">
						<a class="btn btn-light btn-lg" href="/register">Kostenlos testen <ArrowRightIcon /></a>
						<a class="btn btn-outline btn-lg" href="/login">Demo vereinbaren</a>
					</div>
				</div>
			</div>
		</section>
	</main>

	<!-- FOOTER -->
	<footer class="foot">
		<div class="wrap">
			<div class="foot-grid">
				<div class="foot-brand">
					<a class="brand" href="#top">
						<img src="/brand/kliniq-icon.svg" alt="" class="mark" />
						<b>Kliniq</b>
					</a>
					<p>
						OP-Planung für moderne Kliniken. Bündelt Säle, Chirurg:innen und Buchungen in einem
						geteilten Echtzeit-Plan.
					</p>
				</div>
				<div class="foot-col">
					<h4>Produkt</h4>
					<a href="#funktionen">Funktionen</a>
					<a href="#sicherheit">Sicherheit</a>
					<a href="#ablauf">So funktioniert's</a>
					<a href="#top">Änderungen</a>
				</div>
				<div class="foot-col">
					<h4>Unternehmen</h4>
					<a href="#top">Über uns</a>
					<a href="#kontakt">Kontakt</a>
					<a href="#top">Karriere</a>
					<a href="#top">Blog</a>
				</div>
				<div class="foot-col">
					<h4>Rechtliches</h4>
					<a href="#top">Impressum</a>
					<a href="#top">Datenschutz</a>
					<a href="#top">AGB</a>
					<a href="#top">DSGVO</a>
				</div>
			</div>
			<div class="foot-bottom">
				<span>© 2026 Kliniq. Alle Rechte vorbehalten.</span>
				<div class="foot-badges">
					<span><ShieldCheckIcon /> DSGVO-konform</span>
					<span><ServerIcon /> In der EU gehostet</span>
				</div>
			</div>
		</div>
	</footer>
</div>

<style>
	/* Kliniq marketing landing (DE). Ported from the redesign handoff onto the
	 * app's own tokens so theme follows mode-watcher (.dark on <html>). */
	.landing {
		--acc: var(--primary);
		--acc-dk: color-mix(in oklch, var(--primary) 78%, #000);
		--acc-lt: color-mix(in oklch, var(--primary) 55%, #fff);
		--ink: var(--foreground);
		--t2: var(--muted-foreground);
		--t3: color-mix(in oklch, var(--muted-foreground) 72%, transparent);
		--bd: var(--border);
		--line: color-mix(in oklch, var(--border) 55%, transparent);
		--surface: var(--card);
		--surface-2: color-mix(in oklch, var(--card) 92%, var(--background));
		--bg: var(--background);
		--ez: cubic-bezier(0.2, 0.7, 0.3, 1);
		background: var(--bg);
		color: var(--ink);
		font-family: var(--font-sans);
		overflow-x: hidden;
	}
	:global(.dark) .landing {
		--acc-dk: color-mix(in oklch, var(--primary) 60%, #fff);
		--acc-lt: color-mix(in oklch, var(--primary) 40%, #fff);
	}
	.landing :global(svg) {
		width: 1em;
		height: 1em;
	}
	.wrap {
		width: 100%;
		max-width: 1180px;
		margin: 0 auto;
		padding: 0 28px;
	}
	.btn :global(svg) {
		width: 17px;
		height: 17px;
	}

	/* reveal-on-scroll */
	.rv {
		opacity: 0;
		transform: translateY(20px);
		transition:
			opacity 0.7s var(--ez),
			transform 0.7s var(--ez);
	}
	.rv.in {
		opacity: 1;
		transform: none;
	}
	@media (prefers-reduced-motion: reduce) {
		.rv {
			opacity: 1;
			transform: none;
			transition: none;
		}
	}

	/* nav */
	.nav {
		position: sticky;
		top: 0;
		z-index: 50;
		backdrop-filter: blur(14px);
		background: color-mix(in oklch, var(--bg) 78%, transparent);
		border-bottom: 1px solid transparent;
		transition:
			border-color 0.2s,
			background 0.2s;
	}
	.nav.scrolled {
		border-bottom-color: var(--bd);
	}
	.nav-in {
		display: flex;
		align-items: center;
		gap: 28px;
		height: 68px;
	}
	.brand {
		display: flex;
		align-items: center;
		gap: 10px;
	}
	.brand .mark {
		width: 32px;
		height: 32px;
		border-radius: 9px;
		box-shadow: 0 2px 8px -1px color-mix(in oklch, var(--acc) 50%, transparent);
	}
	.brand b {
		font-size: 19px;
		font-weight: 700;
		letter-spacing: -0.03em;
	}
	.nav-links {
		display: flex;
		gap: 4px;
		margin-left: 8px;
	}
	.nav-links a {
		font-size: 14px;
		font-weight: 500;
		color: var(--t2);
		padding: 8px 13px;
		border-radius: 9px;
		transition:
			background 0.15s,
			color 0.15s;
	}
	.nav-links a:hover {
		background: var(--line);
		color: var(--ink);
	}
	.nav-cta {
		margin-left: auto;
		display: flex;
		align-items: center;
		gap: 10px;
	}
	@media (max-width: 860px) {
		.nav-links {
			display: none;
		}
	}

	/* buttons */
	.btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 8px;
		font: inherit;
		font-weight: 600;
		font-size: 14px;
		height: 44px;
		padding: 0 20px;
		border-radius: 11px;
		border: 1px solid transparent;
		cursor: pointer;
		white-space: nowrap;
		transition:
			transform 0.15s var(--ez),
			box-shadow 0.15s var(--ez),
			background 0.15s,
			border-color 0.15s;
	}
	.btn-pri {
		background: linear-gradient(135deg, var(--acc), var(--acc-dk));
		color: #fff;
		box-shadow: 0 10px 24px -8px color-mix(in oklch, var(--acc) 55%, transparent);
	}
	:global(.dark) .landing .btn-pri {
		color: #04130d;
	}
	.btn-pri:hover {
		transform: translateY(-2px);
		box-shadow: 0 16px 32px -8px color-mix(in oklch, var(--acc) 65%, transparent);
	}
	.btn-pri:active {
		transform: translateY(0);
	}
	.btn-ghost {
		background: var(--surface);
		border-color: var(--bd);
		color: var(--ink);
		backdrop-filter: blur(8px);
	}
	.btn-ghost:hover {
		background: var(--line);
		border-color: var(--t3);
	}
	.btn-sm {
		height: 38px;
		padding: 0 15px;
		font-size: 13.5px;
	}
	.btn-lg {
		height: 50px;
		padding: 0 26px;
		font-size: 15px;
	}

	/* hero */
	.hero {
		position: relative;
		padding: 72px 0 40px;
		overflow: hidden;
	}
	.hero::before {
		content: '';
		position: absolute;
		width: 760px;
		height: 760px;
		right: -240px;
		top: -300px;
		border-radius: 50%;
		background: radial-gradient(
			circle,
			color-mix(in oklch, var(--acc) 18%, transparent),
			transparent 66%
		);
		pointer-events: none;
	}
	.hero-grid {
		position: relative;
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 48px;
		align-items: center;
	}
	@media (max-width: 920px) {
		.hero-grid {
			grid-template-columns: 1fr;
			gap: 36px;
		}
	}
	.eyebrow {
		display: inline-flex;
		align-items: center;
		gap: 8px;
		font-size: 13px;
		font-weight: 600;
		color: var(--acc-dk);
		background: color-mix(in oklch, var(--acc) 12%, transparent);
		border: 1px solid color-mix(in oklch, var(--acc) 22%, transparent);
		padding: 6px 13px;
		border-radius: 999px;
		margin-bottom: 22px;
	}
	.eyebrow :global(svg) {
		width: 15px;
		height: 15px;
	}
	.hero h1 {
		font-size: clamp(38px, 5vw, 58px);
		font-weight: 800;
		letter-spacing: -0.035em;
		line-height: 1.04;
		margin: 0;
	}
	.hero h1 .g {
		background: linear-gradient(120deg, var(--acc), var(--acc-dk));
		-webkit-background-clip: text;
		background-clip: text;
		-webkit-text-fill-color: transparent;
	}
	.hero p.lead {
		font-size: 18px;
		line-height: 1.6;
		color: var(--t2);
		margin: 22px 0 0;
		max-width: 540px;
	}
	.hero-cta {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		margin-top: 30px;
	}
	.hero-note {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 13px;
		color: var(--t3);
		margin-top: 18px;
	}
	.hero-note :global(svg) {
		width: 15px;
		height: 15px;
		color: var(--acc);
	}

	.hero-visual {
		position: relative;
	}
	.hero-bg {
		position: absolute;
		inset: -26px;
		border-radius: 26px;
		z-index: 0;
		background:
			linear-gradient(
				180deg,
				color-mix(in oklch, var(--bg) 32%, transparent),
				color-mix(in oklch, var(--bg) 58%, transparent)
			),
			url('/landing/hero-bg.webp') center/cover no-repeat;
		box-shadow: 0 40px 90px -42px rgba(15, 23, 42, 0.4);
		border: 1px solid color-mix(in oklch, var(--ink) 6%, transparent);
	}
	:global(.dark) .landing .hero-bg {
		background:
			linear-gradient(180deg, rgba(7, 13, 22, 0.5), rgba(7, 13, 22, 0.7)),
			url('/landing/hero-bg.webp') center/cover no-repeat;
		border-color: rgba(255, 255, 255, 0.08);
		box-shadow: 0 40px 90px -42px #000;
	}
	.mock {
		position: relative;
		z-index: 1;
		background: var(--surface);
		border: 1px solid var(--bd);
		border-radius: 18px;
		box-shadow: 0 30px 70px -28px rgba(15, 23, 42, 0.3);
		overflow: hidden;
		backdrop-filter: blur(10px);
	}
	:global(.dark) .landing .mock {
		background: #0c1320;
		box-shadow: 0 30px 70px -30px #000;
	}
	.mock-bar {
		display: flex;
		align-items: center;
		gap: 7px;
		height: 38px;
		padding: 0 14px;
		border-bottom: 1px solid var(--line);
		background: var(--surface-2);
	}
	.dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
	}
	.mock-bar .mb-title {
		margin-left: 10px;
		font-size: 12px;
		color: var(--t3);
		font-weight: 500;
	}

	/* mini schedule inside mock */
	.msched {
		display: grid;
		grid-template-columns: 34px 1fr 1fr 1fr;
	}
	.ms-axh {
		border-bottom: 1px solid var(--line);
	}
	.ms-colh {
		border-bottom: 1px solid var(--line);
		border-left: 1px solid var(--line);
		padding: 9px 8px;
	}
	.ms-colh .c {
		font-family: var(--font-mono);
		font-size: 9px;
		color: var(--t3);
	}
	.ms-colh .n {
		font-size: 11px;
		font-weight: 600;
	}
	.ms-axis {
		position: relative;
	}
	.ms-axis .tk {
		position: absolute;
		right: 6px;
		font-family: var(--font-mono);
		font-size: 8.5px;
		color: var(--t3);
		transform: translateY(-50%);
	}
	.ms-col {
		position: relative;
		border-left: 1px solid var(--line);
	}
	.ms-line {
		position: absolute;
		left: 0;
		right: 0;
		border-top: 1px solid color-mix(in oklch, var(--border) 40%, transparent);
	}
	:global(.dark) .landing .ms-line {
		border-top-color: rgba(255, 255, 255, 0.05);
	}
	.ms-bk {
		position: absolute;
		left: 4px;
		right: 4px;
		border-radius: 7px;
		padding: 5px 7px;
		overflow: hidden;
		box-shadow: 0 4px 10px -6px rgba(15, 23, 42, 0.3);
	}
	.ms-bk .t {
		font-family: var(--font-mono);
		font-size: 8px;
		font-weight: 600;
		opacity: 0.7;
	}
	.ms-bk .o {
		font-size: 10px;
		font-weight: 600;
		line-height: 1.2;
		margin-top: 1px;
	}
	.ms-s {
		background: linear-gradient(135deg, color-mix(in oklch, var(--acc) 12%, #fff), #fff);
		border: 1px solid color-mix(in oklch, var(--acc) 35%, transparent);
	}
	.ms-a {
		background: linear-gradient(135deg, #fffbeb, #fff);
		border: 1px solid color-mix(in srgb, #f59e0b 45%, transparent);
	}
	.ms-bk.ms-c {
		background: color-mix(in oklch, var(--surface-2) 70%, var(--bg));
		border: 1px solid var(--bd);
		color: var(--t2);
	}
	:global(.dark) .landing .ms-s {
		background: color-mix(in oklch, var(--acc) 16%, transparent);
		border-color: color-mix(in oklch, var(--acc) 45%, transparent);
	}
	:global(.dark) .landing .ms-a {
		background: rgba(245, 158, 11, 0.18);
		border-color: rgba(251, 191, 36, 0.55);
	}
	:global(.dark) .landing .ms-bk .o {
		color: #f1f5f9;
	}
	:global(.dark) .landing .ms-bk.ms-c .o {
		color: var(--t2);
	}
	.ms-now {
		position: absolute;
		left: 0;
		right: 0;
		border-top: 2px solid var(--acc);
		z-index: 2;
	}
	.ms-now::before {
		content: '';
		position: absolute;
		left: -3px;
		top: -3px;
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--acc);
	}

	/* trust strip */
	.trust {
		padding: 28px 0 8px;
	}
	.trust p {
		text-align: center;
		font-size: 12px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--t3);
		margin: 0 0 18px;
	}
	.trust-row {
		display: flex;
		flex-wrap: wrap;
		justify-content: center;
		gap: 14px 40px;
		opacity: 0.85;
	}
	.trust-row span {
		display: inline-flex;
		align-items: center;
		gap: 9px;
		font-size: 17px;
		font-weight: 700;
		letter-spacing: -0.02em;
		color: #94a3b8;
	}
	.trust-row span :global(svg) {
		width: 19px;
		height: 19px;
	}

	/* section heading */
	.sec {
		padding: 88px 0;
	}
	.sec-head {
		max-width: 660px;
		margin: 0 auto 52px;
		text-align: center;
	}
	.sec-tag {
		font-size: 13px;
		font-weight: 700;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		color: var(--acc-dk);
	}
	.sec-head h2 {
		font-size: clamp(30px, 3.6vw, 42px);
		font-weight: 800;
		letter-spacing: -0.03em;
		line-height: 1.1;
		margin: 12px 0 0;
	}
	.sec-head p {
		font-size: 17px;
		line-height: 1.6;
		color: var(--t2);
		margin: 16px 0 0;
	}

	/* features */
	.feat-grid {
		display: grid;
		grid-template-columns: repeat(3, 1fr);
		gap: 20px;
	}
	@media (max-width: 920px) {
		.feat-grid {
			grid-template-columns: 1fr 1fr;
		}
	}
	@media (max-width: 600px) {
		.feat-grid {
			grid-template-columns: 1fr;
		}
	}
	.feat {
		background: var(--surface);
		border: 1px solid var(--bd);
		border-radius: 18px;
		padding: 26px;
		backdrop-filter: blur(10px);
		transition:
			transform 0.18s var(--ez),
			border-color 0.18s,
			box-shadow 0.18s;
	}
	.feat:hover {
		transform: translateY(-3px);
		border-color: color-mix(in oklch, var(--acc) 35%, transparent);
		box-shadow: 0 18px 40px -22px rgba(15, 23, 42, 0.25);
	}
	.feat-ic {
		width: 46px;
		height: 46px;
		border-radius: 13px;
		background: color-mix(in oklch, var(--acc) 13%, transparent);
		color: var(--acc-dk);
		display: flex;
		align-items: center;
		justify-content: center;
		margin-bottom: 18px;
	}
	.feat-ic :global(svg) {
		width: 22px;
		height: 22px;
	}
	.feat h3 {
		font-size: 17px;
		font-weight: 700;
		letter-spacing: -0.01em;
		margin: 0 0 8px;
	}
	.feat p {
		font-size: 14px;
		line-height: 1.6;
		color: var(--t2);
		margin: 0;
	}

	/* split */
	.split {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 56px;
		align-items: center;
	}
	@media (max-width: 920px) {
		.split {
			grid-template-columns: 1fr;
			gap: 36px;
		}
	}
	.split.rev .split-text {
		order: 2;
	}
	@media (max-width: 920px) {
		.split.rev .split-text {
			order: 0;
		}
	}
	.split-text h2 {
		font-size: clamp(26px, 3vw, 36px);
		font-weight: 800;
		letter-spacing: -0.03em;
		line-height: 1.12;
		margin: 0;
	}
	.split-text > p {
		font-size: 16px;
		line-height: 1.65;
		color: var(--t2);
		margin: 16px 0 0;
	}
	.split-list {
		list-style: none;
		padding: 0;
		margin: 24px 0 0;
		display: flex;
		flex-direction: column;
		gap: 14px;
	}
	.split-list li {
		display: flex;
		gap: 12px;
		align-items: flex-start;
		font-size: 14.5px;
		line-height: 1.5;
	}
	.split-list .ck {
		width: 24px;
		height: 24px;
		border-radius: 8px;
		flex-shrink: 0;
		background: color-mix(in oklch, var(--acc) 14%, transparent);
		color: var(--acc-dk);
		display: flex;
		align-items: center;
		justify-content: center;
		margin-top: 1px;
	}
	.split-list .ck :global(svg) {
		width: 14px;
		height: 14px;
	}
	.split-list b {
		font-weight: 600;
	}
	.split-visual {
		position: relative;
		border-radius: 22px;
		overflow: hidden;
	}
	.split-photo {
		width: 100%;
		border-radius: 18px;
		border: 1px solid var(--bd);
		box-shadow: 0 30px 70px -30px rgba(15, 23, 42, 0.3);
		aspect-ratio: 4 / 5;
		object-fit: cover;
		max-height: 600px;
	}
	:global(.dark) .landing .split-photo {
		box-shadow: 0 30px 70px -30px #000;
	}

	/* security band (always Midnight) */
	.band {
		position: relative;
		background: #070d16;
		color: #e2e8f0;
		overflow: hidden;
	}
	:global(.dark) .landing .band {
		border-block: 1px solid rgba(255, 255, 255, 0.07);
	}
	.band::before {
		content: '';
		position: absolute;
		width: 680px;
		height: 680px;
		left: 50%;
		top: -360px;
		transform: translateX(-50%);
		border-radius: 50%;
		background: radial-gradient(
			circle,
			color-mix(in oklch, var(--acc) 26%, transparent),
			transparent 65%
		);
	}
	.band .sec-head h2 {
		color: #fff;
	}
	.band .sec-head p {
		color: #94a3b8;
	}
	.band .sec-tag {
		color: var(--acc-lt);
	}
	.band-grid {
		position: relative;
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 16px;
	}
	@media (max-width: 920px) {
		.band-grid {
			grid-template-columns: 1fr 1fr;
		}
	}
	.bcard {
		background: rgba(255, 255, 255, 0.04);
		border: 1px solid rgba(255, 255, 255, 0.09);
		border-radius: 16px;
		padding: 22px;
		backdrop-filter: blur(10px);
	}
	.bcard-ic {
		width: 42px;
		height: 42px;
		border-radius: 12px;
		background: color-mix(in oklch, var(--acc) 18%, transparent);
		color: var(--acc-lt);
		display: flex;
		align-items: center;
		justify-content: center;
		margin-bottom: 14px;
	}
	.bcard-ic :global(svg) {
		width: 20px;
		height: 20px;
	}
	.bcard h3 {
		font-size: 15px;
		font-weight: 700;
		margin: 0 0 6px;
		color: #fff;
	}
	.bcard p {
		font-size: 13px;
		line-height: 1.55;
		color: #94a3b8;
		margin: 0;
	}

	/* stats */
	.stats-row {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		gap: 24px;
		text-align: center;
	}
	@media (max-width: 760px) {
		.stats-row {
			grid-template-columns: 1fr 1fr;
			gap: 32px;
		}
	}
	.stat-v {
		font-size: clamp(34px, 4vw, 46px);
		font-weight: 800;
		letter-spacing: -0.03em;
		line-height: 1;
		background: linear-gradient(135deg, var(--acc), var(--acc-dk));
		-webkit-background-clip: text;
		background-clip: text;
		-webkit-text-fill-color: transparent;
	}
	.stat-l {
		font-size: 14px;
		color: var(--t2);
		margin-top: 10px;
	}

	/* CTA banner */
	.cta-band {
		position: relative;
		border-radius: 28px;
		overflow: hidden;
		background: linear-gradient(135deg, var(--acc), var(--acc-dk));
		padding: 56px 40px;
		text-align: center;
		color: #fff;
		box-shadow: 0 30px 70px -28px color-mix(in oklch, var(--acc) 60%, transparent);
	}
	.cta-band::before {
		content: '';
		position: absolute;
		inset: 0;
		background: radial-gradient(120% 140% at 80% 10%, rgba(255, 255, 255, 0.22), transparent 55%);
	}
	.cta-band h2 {
		position: relative;
		font-size: clamp(28px, 3.4vw, 40px);
		font-weight: 800;
		letter-spacing: -0.03em;
		margin: 0;
	}
	.cta-band p {
		position: relative;
		font-size: 17px;
		opacity: 0.92;
		margin: 14px auto 28px;
		max-width: 520px;
		line-height: 1.5;
	}
	.cta-band .row {
		position: relative;
		display: flex;
		gap: 12px;
		justify-content: center;
		flex-wrap: wrap;
	}
	.cta-band .btn-light {
		background: #fff;
		color: var(--acc-dk);
	}
	.cta-band .btn-light:hover {
		transform: translateY(-2px);
		box-shadow: 0 14px 30px -8px rgba(0, 0, 0, 0.3);
	}
	.cta-band .btn-outline {
		background: transparent;
		border-color: rgba(255, 255, 255, 0.5);
		color: #fff;
	}
	.cta-band .btn-outline:hover {
		background: rgba(255, 255, 255, 0.12);
	}

	/* FAQ */
	.faq {
		max-width: 780px;
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}
	.faq-item {
		background: var(--surface);
		border: 1px solid var(--bd);
		border-radius: 14px;
		overflow: hidden;
		backdrop-filter: blur(10px);
		transition:
			border-color 0.18s,
			box-shadow 0.18s;
	}
	.faq-item[open] {
		border-color: color-mix(in oklch, var(--acc) 32%, transparent);
		box-shadow: 0 14px 34px -22px rgba(15, 23, 42, 0.22);
	}
	.faq-item summary {
		list-style: none;
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		padding: 18px 20px;
		font-size: 16px;
		font-weight: 600;
		letter-spacing: -0.01em;
		color: var(--ink);
	}
	.faq-item summary::-webkit-details-marker {
		display: none;
	}
	.faq-item summary :global(svg) {
		width: 18px;
		height: 18px;
		color: var(--t3);
		flex-shrink: 0;
		transition:
			transform 0.22s var(--ez),
			color 0.18s;
	}
	.faq-item[open] summary :global(svg) {
		transform: rotate(180deg);
		color: var(--acc);
	}
	.faq-item summary:hover {
		color: var(--acc-dk);
	}
	.faq-a {
		padding: 0 20px 18px;
		font-size: 14.5px;
		line-height: 1.6;
		color: var(--t2);
		max-width: 64ch;
	}

	/* footer */
	.foot {
		border-top: 1px solid var(--bd);
		padding: 56px 0 40px;
	}
	.foot-grid {
		display: grid;
		grid-template-columns: 1.5fr 1fr 1fr 1fr;
		gap: 32px;
	}
	@media (max-width: 760px) {
		.foot-grid {
			grid-template-columns: 1fr 1fr;
			gap: 28px;
		}
	}
	.foot-brand p {
		font-size: 13.5px;
		color: var(--t2);
		line-height: 1.6;
		margin: 14px 0 0;
		max-width: 280px;
	}
	.foot-col h4 {
		font-size: 12px;
		font-weight: 700;
		letter-spacing: 0.06em;
		text-transform: uppercase;
		color: var(--t3);
		margin: 0 0 14px;
	}
	.foot-col a {
		display: block;
		font-size: 14px;
		color: var(--t2);
		padding: 5px 0;
		transition: color 0.15s;
	}
	.foot-col a:hover {
		color: var(--acc-dk);
	}
	.foot-bottom {
		display: flex;
		flex-wrap: wrap;
		gap: 12px;
		justify-content: space-between;
		align-items: center;
		margin-top: 44px;
		padding-top: 24px;
		border-top: 1px solid var(--bd);
		font-size: 13px;
		color: var(--t3);
	}
	.foot-badges {
		display: flex;
		gap: 16px;
	}
	.foot-badges span {
		display: inline-flex;
		align-items: center;
		gap: 6px;
	}
	.foot-badges :global(svg) {
		width: 14px;
		height: 14px;
	}
</style>
