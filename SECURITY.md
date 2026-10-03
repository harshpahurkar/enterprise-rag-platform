# Security policy

## Supported versions

| Version | Supported |
|---|---|
| `main` | Yes |
| Any other branch, tag or fork | No |

Fixes land on `main` only. There are no release branches.

## Reporting a vulnerability

Report it privately through GitHub's private vulnerability reporting: open the repository's **Security** tab and choose **Report a vulnerability**. Please don't open a public issue, discussion or pull request for a security problem.

Include:

- what an attacker can do, and with which role (none, EMPLOYEE, a department role, or ADMIN)
- steps or a request sequence that reproduces it
- the commit you tested and whether you used the `demo` profile

The report stays private until a fix is on `main`. After that, the advisory is published with credit to you unless you ask otherwise.

Out of scope:

- anything that only works because the `demo` profile is reachable from another machine (see below)
- missing TLS on a deployment without a TLS proxy
- findings already listed in the residual-risk register in [`docs/threat-model.md`](docs/threat-model.md#residual-risk-register), unless you can push one past the impact it describes there

## The demo profile

`docker compose up` starts the app with `SPRING_PROFILES_ACTIVE=demo`. That profile creates five users (`admin`, `hr.manager`, `finance.analyst`, `legal.counsel`, `engineer`) and loads fictional documents with fake PII.

> **Warning: the demo credentials are public.** The default password `demo-password` is in this repository, and while the `demo` profile is on, the login page lists the demo usernames (never the password). `admin` holds every role and can upload and delete documents. Never expose a `demo` instance beyond localhost, and never load real documents into one.

Compose binds the app and database ports to `127.0.0.1`, so out of the box only your own machine can reach them. Anything reachable from another machine counts as a deployment, and a deployment needs at least these steps first:

- turn off the `demo` profile and create real users with unique passwords
- set a fresh `JWT_SECRET` (`openssl rand -base64 48`) and a new `POSTGRES_PASSWORD`
- terminate TLS in front of the app, which serves plain HTTP
- read the residual-risk register in the threat model

## Security model at a glance

Details, evidence and residual risks are in [`docs/threat-model.md`](docs/threat-model.md).

- Every read filters on `document.allowed_roles && userRoles` in SQL, so a chunk the caller can't read is never fetched and never reaches a prompt. Delete is scoped the same way: a document outside the caller's roles answers 404, like one that doesn't exist.
- The JWT carries identity only. Roles load from `app_user` on each request, so a role change or a deleted user takes effect at once.
- Tokens are HS256 with a key of at least 32 bytes from the environment, issuer `rag-platform`, a required `exp` and a 1 hour lifetime. The SPA keeps them in `sessionStorage` and sends them as a bearer header. There are no cookies, so there is no CSRF surface.
- Login uses BCrypt, one generic error message, a dummy hash check for unknown usernames so timing doesn't reveal which exist, and a limit of 10 attempts per minute per IP.
- Ask allows 20 questions per hour per user and 500 a day across all users, a ceiling on LLM spend. A prompt-injection check runs before retrieval and again inside the AI Service. Emails, phone numbers, SIN/SSN and card numbers are masked before anything goes to Anthropic, only chunks scoring 0.6 or higher are sent, and answers must cite their sources.
- Search allows 120 queries per minute per user.
- Uploads and deletes are ADMIN only, checked by the URL rules and again on the controller methods. Uploads allow 30 per hour per user and 25 MB per file, with extraction capped at 5,000,000 characters and 2000 chunks.
- The browser gets a CSP with no inline script or style (`default-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `nosniff`, `Referrer-Policy: no-referrer`, a Permissions-Policy that turns off camera, microphone, geolocation and payment, same-origin COOP and CORP, and no-store caching. There is no CORS grant, so another origin can't read API responses or pass a preflight. Signing out or an expired session clears the session token and the SPA's cached data.
- Errors never return internals: a 500 carries no exception message, class name or stack trace.
- Logins, rejected tokens, 403s, uploads, deletes, guardrail blocks and rate-limit hits are logged as one key=value line each on the `SECURITY_AUDIT` logger. Values are stripped of line breaks, and passwords, tokens, questions and document text are never logged.
- The container runs as uid 10001 with a read-only root filesystem, no Linux capabilities and `no-new-privileges`; compose binds ports to localhost, and `.env` stays out of git. CI runs CodeQL, Semgrep, gitleaks, OSV-Scanner and Trivy, with Dependabot and SHA-pinned actions.
- Known gaps: regex-only PII masking, a heuristic injection check, in-memory rate limits, no TLS of its own, no SSO or MFA, and an audit log that stays on stdout with no alerting. Each one has a fix trigger in the register.
