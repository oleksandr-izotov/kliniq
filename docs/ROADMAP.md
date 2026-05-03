# Roadmap

Kliniq is shipped in three versions. Each version has hard scope so we don't drift.

---

## V1 — Internal portal MVP

**Goal:** A clinic manager can log in, see today's operating-room schedule, and create / edit / cancel bookings without conflicts. Clean UI, secure auth, real-time live updates.

**Target effort:** ~6-10 weeks at a chaotic pace (mix of intense days and slower ones).

### Features

| Area | Scope |
|---|---|
| **Auth** | Email/password + email verification + password reset + passkeys (WebAuthn) for all users. Sessions over secure cookies (Redis-backed). Roles: `ADMIN`, `MANAGER`, `STAFF`. |
| **Users** | Admin can invite/disable staff. Self-service profile (name, email, password, passkeys). |
| **Operating rooms** | CRUD by `MANAGER`+. Fields: name, code, status (`ACTIVE` / `MAINTENANCE`), notes. |
| **Surgeons** | Created as users with role `STAFF` + flag `is_surgeon` + specialty enum. |
| **Bookings** | CRUD by `STAFF`+. Fields: surgeon, operating room, start, end, type of operation (free text in V1), patient reference (anonymous code, NO PII). Conflict detection (no overlap on same OR). Lifecycle: `SCHEDULED` → `IN_PROGRESS` → `COMPLETED` / `CANCELLED`. |
| **Schedule view** | Day view + week view. List + simple time-grid. No drag-drop in V1 (V2). |
| **Real-time** | SSE endpoint pushing booking changes to all connected staff browsers. |
| **Audit log** | Every create/update/delete on bookings, users, ORs is logged with `who, what, when, before, after`. |
| **Settings** | Clinic-wide settings: name, working hours, default booking duration. |

### Non-functional acceptance criteria (V1 done = all green)

- [ ] All endpoints behind auth except `/auth/*` and `/health`
- [ ] CSRF protection enabled
- [ ] Rate limiting on `/auth/login` and `/auth/register` (10/min/IP)
- [ ] Argon2id password hashing
- [ ] No PII in logs
- [ ] CI passes: backend tests, frontend tests, lint, typecheck, gitleaks
- [ ] >70% line coverage on backend `domain/` and `usecase/` packages
- [ ] Lighthouse score ≥ 90 on `/login` and `/dashboard`
- [ ] Works on Chrome, Firefox, Safari (desktop)
- [ ] Mobile viewport doesn't break (responsive, even if not optimal)
- [ ] Dark mode works
- [ ] All UI strings in English, ready for i18n later
- [ ] README has working "Quick start" that takes a fresh checkout to a running app

### Explicitly OUT of V1

- Customer portal (V2)
- Recurring routines (V2)
- Drag-drop schedule (V2)
- Billing (V3)
- Email notifications (only verify/reset emails)
- Multi-tenant (single clinic only)
- Mobile-native app

---

## V2 — Customer portal + scheduling polish

**Goal:** External surgeons can self-book available slots. Staff can drag bookings around. Recurring routines reduce manual entry.

**Target effort:** ~4-6 weeks after V1.

### Features

- Customer portal as separate route group `/portal/*` in same SvelteKit app
- Surgeon self-registration with admin approval flow
- Public availability view (only free slots, no PII)
- Customer-side booking creation with rate limiting and allowlist on API
- Drag-drop schedule reorganization (internal staff)
- Recurring routines: "every Monday 9-11am with Dr. Smith for 8 weeks"
- Email notifications on booking events (Resend)
- Google OAuth as alternative login
- Audit log UI for admins

---

## V3 — Billing, reporting, polish

**Goal:** Track financials, generate invoices, give admins insight.

**Target effort:** ~4-8 weeks. Stretch goal.

### Features

- Billing policies (per-OR-hour rates, customer-specific overrides)
- Invoice generation (PDF via Apache PDFBox or similar)
- Stripe payment integration
- Reports: utilization per OR, revenue per surgeon, cancellations
- Admin dashboard with charts (Tremor or Recharts equivalents in Svelte)
- File uploads (consent forms, scans) to S3-compatible storage
- PWA install support
- Full i18n (EN + DE + RU)
- Mobile-optimized layouts

---

## Forever-deferred / out of scope

- Real medical record handling (GDPR Art 9 — out of scope, we use anonymous patient codes)
- Insurance integration
- Multi-tenant with billing per clinic
- Native mobile app
- AI/ML features (bookings prediction, etc.) — could be a fun spike post-V3

---

## Milestones

| Milestone | Definition of Done |
|---|---|
| **M0 — Foundation** | Sprint 0 done: empty repo runs hello-world end-to-end with HTTPS, CI green |
| **M1 — Auth** | Sprint 1 done: register, verify email, login (password + passkey), reset password |
| **M2 — Booking core** | Bookings CRUD with conflict detection, schedule view, audit log |
| **M3 — Real-time** | Live updates via SSE working in two browser tabs |
| **M4 — V1 ship** | All V1 acceptance criteria green, deployed to Hetzner via Coolify |
| **M5 — V2 ship** | Customer portal live |
| **M6 — V3 ship** | Billing live |

After M4 we revisit this roadmap with what we learned. Plans past M4 are intentionally fuzzy.
