# Local TLS certificates

This directory holds locally-trusted TLS certificates issued by [mkcert](https://github.com/FiloSottile/mkcert) for development. The certificates themselves (`*.pem`, `*.p12`, `*.key`) are gitignored — each developer generates their own on their machine.

## Why

We run the local dev servers over HTTPS to keep dev/prod parity. The frontend (`https://localhost:5173`) and the API (`https://localhost:8443`) both terminate TLS, and cookies marked `Secure` would be silently dropped over plain HTTP — which would break our session-based auth at the worst possible time.

mkcert installs a local Certificate Authority (CA) into the OS trust store and signs certificates with it. Browsers and the JVM trust them transparently — no scary warnings, no `--insecure` flags.

## First-time setup

Install mkcert:

```powershell
winget install --id FiloSottile.mkcert --scope user
```

Trust the local CA (one-time, prompts UAC):

```powershell
mkcert -install
```

Generate the certs:

```bash
cd certs
mkcert -cert-file localhost.pem -key-file localhost-key.pem localhost 127.0.0.1 ::1

# Spring Boot needs PKCS12 with a password
openssl pkcs12 -export -in localhost.pem -inkey localhost-key.pem -out localhost.p12 -name kliniq-api -password pass:changeit
```

After this you should have:

```
certs/
├── README.md           (committed)
├── localhost.pem       (gitignored — cert)
├── localhost-key.pem   (gitignored — private key)
└── localhost.p12       (gitignored — PKCS12 bundle for Spring)
```

## How they're used

| Service | Format | Path / config |
|---|---|---|
| **API** (Spring Boot) | `localhost.p12` | `apps/api/src/main/resources/application-local.yml` → `server.ssl.key-store: file:../../certs/localhost.p12` (resolved from working dir) |
| **Web** (SvelteKit / Vite) | `localhost.pem` + `localhost-key.pem` | `apps/web/vite.config.ts` → `server.https = { cert, key }` |

## Renewal

mkcert certs default to ~825 days (the modern browser limit). When yours expire, just regenerate using the same command. The mkcert-issued root CA stays trusted forever (until you `mkcert -uninstall` it).

## Where the root CA lives

```bash
mkcert -CAROOT
```

On this machine: `C:\Users\Admin\AppData\Local\mkcert`. You can copy `rootCA.pem` from there to other dev machines if you ever want to share the same trust anchor (rare, usually overkill).

## Production note

These certs are **dev only**. Production uses real Let's Encrypt certs auto-managed by Caddy (configured later when we deploy via Coolify). Never copy these `.pem` files to a server.
