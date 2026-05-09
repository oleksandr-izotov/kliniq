"""
End-to-end smoke test for Kliniq.

Hits the live Spring backend at https://localhost:8443 and uses Mailpit's
HTTP API at http://localhost:8025 to extract verification and reset tokens
from delivered emails. Also verifies the SvelteKit dev server's auth gate
if it's reachable at https://localhost:5173.

Run:
  python scripts/smoke_test.py                  # auth-only smoke
  python scripts/smoke_test.py --include-booking  # also exercises the
                                                  # booking happy path
                                                  # (needs `pip install
                                                  # psycopg[binary]` to
                                                  # seed manager+surgeon)
"""

from __future__ import annotations

import argparse
import re
import sys
import time

import requests
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

BACKEND = "https://localhost:8443"
MAILPIT = "http://localhost:8025"
WEB = "https://localhost:5173"

EMAIL = f"smoke-{int(time.time())}@kliniq.test"
PASSWORD = "correct-horse-battery-staple"
NEW_PASSWORD = "totally-new-passphrase-9876"
DISPLAY = "Smoke Tester"

# Booking-flow uses two separate users so it doesn't tangle with the
# password-reset assertions on the auth user above:
#   - ADMIN_EMAIL is bootstrapped to ADMIN role via a single psql line
#     (the only chicken-and-egg in the system — a fresh deploy has
#     no way to mint the first admin without it). Issues the invitation.
#   - BOOKING_EMAIL is the invitee who becomes a MANAGER+surgeon by
#     accepting the invitation through real HTTP endpoints. No more
#     psycopg side-channel for role promotion.
ADMIN_EMAIL = f"smoke-admin-{int(time.time())}@kliniq.test"
ADMIN_DISPLAY = "Smoke Admin"
BOOKING_EMAIL = f"smoke-book-{int(time.time())}@kliniq.test"
BOOKING_DISPLAY = "Booking Smoke"
DEV_PG_URL = "postgres://kliniq:kliniq_dev_only@localhost:55432/kliniq"


def section(title: str) -> None:
    print(f"\n=== {title} ===")


def ok(msg: str) -> None:
    print(f"  [OK]   {msg}")


def fail(msg: str, detail: str = "") -> None:
    print(f"  [FAIL] {msg}", file=sys.stderr)
    if detail:
        print(f"         {detail}", file=sys.stderr)
    sys.exit(1)


def make_session() -> requests.Session:
    s = requests.Session()
    s.verify = False
    return s


def csrf(s: requests.Session) -> str:
    token = s.cookies.get("XSRF-TOKEN")
    return token or ""


def api_post(s: requests.Session, path: str, body: dict, expect: int = 200) -> requests.Response:
    r = s.post(
        f"{BACKEND}{path}",
        json=body,
        headers={"X-XSRF-TOKEN": csrf(s), "Accept": "application/json"},
    )
    if r.status_code != expect:
        fail(f"POST {path} -> {r.status_code} (expected {expect})", r.text[:400])
    return r


def api_get(s: requests.Session, path: str, expect: int = 200) -> requests.Response:
    r = s.get(f"{BACKEND}{path}", headers={"Accept": "application/json"})
    if r.status_code != expect:
        fail(f"GET {path} -> {r.status_code} (expected {expect})", r.text[:400])
    return r


def fetch_token_from_mail(subject_substr: str, link_regex: str) -> str:
    deadline = time.time() + 8.0
    last_msgs: list[dict] = []
    while time.time() < deadline:
        resp = requests.get(f"{MAILPIT}/api/v1/messages?limit=30")
        last_msgs = resp.json().get("messages", [])
        candidates = [m for m in last_msgs if subject_substr.lower() in m["Subject"].lower()]
        if candidates:
            best = candidates[0]  # mailpit returns newest first
            full = requests.get(f"{MAILPIT}/api/v1/message/{best['ID']}").json()
            for body_field in ("HTML", "Text"):
                body = full.get(body_field, "") or ""
                m = re.search(link_regex, body)
                if m:
                    return m.group(1)
        time.sleep(0.3)
    subjects = [m["Subject"] for m in last_msgs[:5]]
    fail(
        f"no mail with subject containing '{subject_substr}' arrived in 8s",
        f"recent subjects: {subjects}",
    )
    return ""  # unreachable


def bootstrap_admin(email: str) -> None:
    """The chicken-and-egg admin: a fresh deploy has no admin to issue
    the first invitation, so we promote a freshly-registered user via
    one psql UPDATE. This is the only role change the smoke does
    side-channel; everything below it goes through real HTTP."""
    try:
        import psycopg
    except ImportError:
        fail(
            "--include-booking requires psycopg",
            "install with `pip install psycopg[binary]` and re-run",
        )
    with psycopg.connect(DEV_PG_URL, autocommit=True) as conn:  # type: ignore[attr-defined]
        conn.execute(
            "UPDATE users SET role = 'ADMIN' WHERE email_normalized = %s",
            (email.lower(),),
        )


def cleanup_booking_fixtures() -> None:
    """Drop booking-flow rows so reruns stay independent. Mirrors what
    the Playwright `cleanupTestData` helper does in apps/web/e2e."""
    try:
        import psycopg
    except ImportError:
        return
    with psycopg.connect(DEV_PG_URL, autocommit=True) as conn:  # type: ignore[attr-defined]
        conn.execute("DELETE FROM bookings WHERE patient_ref LIKE 'P-9999-%'")
        conn.execute("DELETE FROM operating_rooms WHERE code LIKE 'SMOKE-%'")
        conn.execute(
            "DELETE FROM user_invitations WHERE email_normalized LIKE 'smoke-%@kliniq.test'"
        )
        conn.execute(
            "DELETE FROM users WHERE email_normalized "
            "LIKE 'smoke-book-%' OR email_normalized LIKE 'smoke-admin-%'"
        )


def run_booking_flow() -> None:
    section("BOOKING FLOW: bootstrap admin")
    cleanup_booking_fixtures()

    # ---- 1. Register + verify the chicken-and-egg admin ------------------
    admin = make_session()
    admin.get(f"{BACKEND}/api/v1/auth/me", verify=False)
    api_post(admin, "/api/v1/auth/register", {
        "email": ADMIN_EMAIL, "password": PASSWORD, "displayName": ADMIN_DISPLAY,
    })
    verify_token = fetch_token_from_mail("verify", r"verify\?token=([A-Za-z0-9_-]+)")
    api_post(admin, "/api/v1/auth/verify", {"token": verify_token})
    bootstrap_admin(ADMIN_EMAIL)
    api_post(admin, "/api/v1/auth/login", {"email": ADMIN_EMAIL, "password": PASSWORD})
    ok(f"admin {ADMIN_EMAIL[:24]}... bootstrapped")

    # ---- 2. Admin invites the booking user as MANAGER+surgeon -----------
    section("invite booking user via /admin/invitations")
    r = api_post(admin, "/api/v1/admin/invitations", {
        "email": BOOKING_EMAIL,
        "role": "MANAGER",
        "isSurgeon": True,
        "specialty": "GENERAL",
    }, expect=201)
    ok(f"invitation issued for {BOOKING_EMAIL[:24]}... role=MANAGER surgeon=GENERAL")

    # ---- 3. Recipient accepts the invitation through the public endpoint -
    section("accept invitation via /auth/invitation/accept")
    invite_token = fetch_token_from_mail("invited", r"invite\?token=([A-Za-z0-9_-]+)")
    s = make_session()
    s.get(f"{BACKEND}/api/v1/auth/me", verify=False)

    # Preview surfaces the role + email we'll get on accept — exercise it
    # so the SPA's accept-page contract has smoke coverage.
    r = api_post(s, "/api/v1/auth/invitation/preview", {"token": invite_token})
    if r.json()["role"] != "MANAGER" or r.json()["email"] != BOOKING_EMAIL:
        fail("preview returned unexpected shape", r.text[:300])
    ok("preview returns MANAGER + correct email")

    api_post(s, "/api/v1/auth/invitation/accept", {
        "token": invite_token,
        "password": PASSWORD,
        "displayName": BOOKING_DISPLAY,
    })
    # Session cookie is set by the accept response — same primitive login uses.
    me = api_get(s, "/api/v1/auth/me")
    if me.json()["role"] != "MANAGER" or not me.json()["isSurgeon"]:
        fail("accept landed wrong role/flag", me.text[:300])
    ok(f"accepted as MANAGER + GENERAL surgeon, /me reflects it")

    section("create operating room")
    or_code = f"SMOKE-{int(time.time()) % 100000}"
    r = api_post(s, "/api/v1/operating-rooms", {
        "code": or_code, "name": "Smoke Suite", "notes": "smoke-test scratch room",
    }, expect=201)
    or_id = r.json()["id"]
    ok(f"OR {or_code} created (id={or_id[:8]}...)")

    section("look up our own surgeon id")
    r = api_get(s, "/api/v1/users/surgeons")
    surgeons = r.json()
    me = next((u for u in surgeons if u["displayName"] == BOOKING_DISPLAY), None)
    if me is None:
        fail("our display name not in /users/surgeons", str(surgeons))
    surgeon_id = me["id"]
    ok(f"surgeon picker contains us (id={surgeon_id[:8]}...)")

    section("create a booking")
    starts = "2099-06-15T09:00:00Z"
    ends = "2099-06-15T10:00:00Z"
    patient_ref = f"P-9999-{int(time.time()) % 1000:03d}"
    r = api_post(s, "/api/v1/bookings", {
        "operatingRoomId": or_id,
        "surgeonId": surgeon_id,
        "startsAt": starts,
        "endsAt": ends,
        "opType": "Smoke arthroscopy",
        "patientRef": patient_ref,
    }, expect=201)
    booking_id = r.json()["id"]
    if r.json()["status"] != "SCHEDULED":
        fail(f"new booking should be SCHEDULED, got {r.json()['status']}")
    ok(f"booking {booking_id[:8]}... created in SCHEDULED")

    section("conflict: overlapping booking is rejected with 409")
    api_post(s, "/api/v1/bookings", {
        "operatingRoomId": or_id,
        "surgeonId": surgeon_id,
        "startsAt": "2099-06-15T09:30:00Z",
        "endsAt": "2099-06-15T10:30:00Z",
        "opType": "Conflicting op",
        "patientRef": f"P-9999-{int(time.time()) % 1000:03d}",
    }, expect=409)
    ok("overlapping POST -> 409 BOOKING_CONFLICT as expected")

    section("schedule day-view returns the booking")
    r = api_get(s, "/api/v1/schedule?date=2099-06-15")
    rooms = r.json()["operatingRooms"]
    our_room = next((rm for rm in rooms if rm["id"] == or_id), None)
    if our_room is None:
        fail(f"our OR not in schedule response", str(rooms))
    if not any(b["id"] == booking_id for b in our_room["bookings"]):
        fail("our booking not in schedule response", str(our_room))
    ok("schedule day-view shows the new booking")

    section("cancel: status flips to CANCELLED")
    r = api_post(s, f"/api/v1/bookings/{booking_id}/cancel", {})
    if r.json()["status"] != "CANCELLED":
        fail(f"after /cancel status should be CANCELLED, got {r.json()['status']}")
    ok("POST /cancel -> CANCELLED")

    section("after cancel: same slot is bookable again (EXCLUDE predicate is partial)")
    api_post(s, "/api/v1/bookings", {
        "operatingRoomId": or_id,
        "surgeonId": surgeon_id,
        "startsAt": starts,
        "endsAt": ends,
        "opType": "Re-booked after cancel",
        "patientRef": f"P-9999-{(int(time.time()) + 1) % 1000:03d}",
    }, expect=201)
    ok("freed slot accepts a new booking")

    cleanup_booking_fixtures()
    ok("booking-flow fixtures cleaned up")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--include-booking", action="store_true",
        help="also exercise the booking happy path (requires psycopg)",
    )
    args = parser.parse_args()

    print(f"Smoke target: {BACKEND}")
    print(f"Test email:   {EMAIL}")

    # ---- Backend flow ------------------------------------------------------
    s = make_session()

    section("prime CSRF (anonymous /me)")
    r = s.get(f"{BACKEND}/api/v1/auth/me", verify=False)
    if r.status_code != 401:
        fail(f"unauth /me should be 401, got {r.status_code}", r.text[:300])
    ok("anon /me -> 401 as expected")
    if not csrf(s):
        fail("XSRF-TOKEN cookie not set after first GET")
    ok(f"XSRF-TOKEN cookie set ({csrf(s)[:8]}...)")

    section("POST /register")
    api_post(s, "/api/v1/auth/register", {
        "email": EMAIL, "password": PASSWORD, "displayName": DISPLAY,
    })
    ok(f"registered {EMAIL}")

    section("verify email via mailpit token")
    verify_token = fetch_token_from_mail("verify", r"verify\?token=([A-Za-z0-9_-]+)")
    ok(f"got verify token ({verify_token[:8]}...)")
    api_post(s, "/api/v1/auth/verify", {"token": verify_token})
    ok("POST /verify -> 200")

    section("POST /login (fresh credentials)")
    r = api_post(s, "/api/v1/auth/login", {"email": EMAIL, "password": PASSWORD})
    user = r.json()
    if user["email"] != EMAIL:
        fail(f"login response email mismatch: {user}")
    ok(f"logged in as {user['displayName']!r}")
    session_cookie = s.cookies.get("__Host-kliniq_session")
    if not session_cookie:
        fail("__Host-kliniq_session cookie not set after login")
    ok(f"session cookie set ({session_cookie[:8]}...)")

    section("authenticated GET /me")
    r = api_get(s, "/api/v1/auth/me")
    if r.json()["email"] != EMAIL:
        fail(f"/me email mismatch: {r.json()}")
    ok(f"/me returns {r.json()['displayName']!r}")

    section("POST /password/forgot")
    api_post(s, "/api/v1/auth/password/forgot", {"email": EMAIL})
    ok("forgot password accepted")

    section("complete reset via mailpit token")
    reset_token = fetch_token_from_mail("reset", r"reset\?token=([A-Za-z0-9_-]+)")
    ok(f"got reset token ({reset_token[:8]}...)")
    api_post(
        s, "/api/v1/auth/password/reset",
        {"token": reset_token, "newPassword": NEW_PASSWORD},
    )
    ok("POST /password/reset -> 200")

    # Reset should kill all sessions for that user.
    section("after reset: previous session is dead")
    api_get(s, "/api/v1/auth/me", expect=401)
    ok("old session -> 401 as expected")

    section("login with OLD password fails")
    s2 = make_session()
    s2.get(f"{BACKEND}/api/v1/auth/me", verify=False)
    api_post(s2, "/api/v1/auth/login", {"email": EMAIL, "password": PASSWORD}, expect=401)
    ok("old password -> 401")

    # Failed attempt above arms the per-IP backoff (~2s window). The
    # Playwright auth flow sleeps the same way; without this, the next
    # POST /login comes back 429 LOGIN_BACKOFF.
    time.sleep(2.5)

    section("login with NEW password succeeds")
    api_post(s2, "/api/v1/auth/login", {"email": EMAIL, "password": NEW_PASSWORD})
    ok("new password -> 200")

    section("POST /logout")
    api_post(s2, "/api/v1/auth/logout", {})
    ok("logout accepted")

    section("post-logout /me is unauthenticated")
    api_get(s2, "/api/v1/auth/me", expect=401)
    ok("post-logout /me -> 401")

    # ---- SvelteKit dev server (optional) -----------------------------------
    section("SvelteKit dev server (optional)")
    try:
        ping = requests.get(WEB, verify=False, allow_redirects=False, timeout=2)
    except Exception as e:
        print(f"  [SKIP] {WEB} unreachable: {e}")
    else:
        # Anonymous: should redirect to /login
        if ping.status_code == 302 or ping.status_code == 303:
            loc = ping.headers.get("Location", "")
            if "/login" in loc:
                ok(f"anon GET / -> {ping.status_code} -> {loc}")
            else:
                fail(f"anon redirect went to unexpected location: {loc}")
        elif ping.status_code == 200:
            # Could be that hooks couldn't reach backend? Inspect.
            fail(
                f"anon GET / returned 200 (expected redirect to /login)",
                "hooks.server.ts may not be enforcing the guard",
            )
        else:
            fail(f"anon GET / returned {ping.status_code}")

        # With session: log in fresh, then hit / via SvelteKit using the
        # same session jar so domain matching is preserved (requests stores
        # bare-hostname cookies under "localhost.local" internally; copying
        # them across jars with domain="localhost" silently misses).
        s3 = make_session()
        s3.get(f"{BACKEND}/api/v1/auth/me", verify=False)
        api_post(s3, "/api/v1/auth/login", {"email": EMAIL, "password": NEW_PASSWORD})
        # Hit SvelteKit /. Expect 200 (authenticated home renders).
        r = s3.get(WEB, allow_redirects=False, timeout=10)
        if r.status_code == 200:
            if "Welcome back" in r.text or "Sign out" in r.text:
                ok("authed GET / -> 200 with authenticated home")
            else:
                ok(f"authed GET / -> 200 (could not confirm content; len={len(r.text)})")
        else:
            fail(f"authed GET / -> {r.status_code}", r.text[:300])

    if args.include_booking:
        run_booking_flow()

    print("\nAll checks passed.")


if __name__ == "__main__":
    main()
