"""
End-to-end smoke test for Kliniq auth.

Hits the live Spring backend at https://localhost:8443 and uses Mailpit's
HTTP API at http://localhost:8025 to extract verification and reset tokens
from delivered emails. Also verifies the SvelteKit dev server's auth gate
if it's reachable at https://localhost:5173.

Run: python scripts/smoke_test.py
"""

from __future__ import annotations

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


def main() -> None:
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

    print("\nAll checks passed.")


if __name__ == "__main__":
    main()
