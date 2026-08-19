# Staging HTTPS Runbook

This runbook enables HTTPS for the staging EC2 endpoint without adding an ALB. The public UI is
`staging.miriyum.click`; `staging-api.miriyum.click` remains an API-only endpoint.

## Preconditions

- `staging-api.miriyum.click` and `staging.miriyum.click` both resolve to the staging EC2 public IP.
- The staging security group allows TCP 80 and TCP 443 from the internet.
- The staging CD workflow has deployed `/opt/miriyum/certbot.sh` and the Nginx templates.

## EC2 Environment

Through SSM, add these values to `/opt/miriyum/.env`. Replace the email with the team certificate contact; do not add it to GitHub or Git.

```dotenv
STAGING_DOMAIN=staging-api.miriyum.click
STAGING_FRONTEND_DOMAIN=staging.miriyum.click
LETSENCRYPT_DIR=/opt/miriyum/letsencrypt
CERTBOT_WEBROOT_DIR=/opt/miriyum/certbot-www
LETSENCRYPT_EMAIL=team-contact@example.com
```

Set `MIRIYUM_ALLOWED_ORIGIN=https://staging.miriyum.click` in the same server-local file before
the first hosted-login smoke. The backend accepts one configured browser Origin; keep the local
Vite proxy only for local development, not as evidence for the hosted staging flow.

For Kakao login, add `https://staging.miriyum.click/auth/kakao/callback` to the Kakao Developer
Console redirect URIs and append the exact same value to the comma-separated
`MIRIYUM_KAKAO_REDIRECT_URIS` value in the server-local `.env`. Do not remove the existing local
callback until its local test path is retired.

## Issue the Initial Certificate

Run this through SSM after the HTTPS configuration deployment succeeds:

```sh
cd /opt/miriyum
set -a
. ./.env
set +a
./certbot.sh issue
```

After Certbot succeeds, the helper verifies both certificate files and force-recreates Nginx with `MIRIYUM_STAGING_FORCE_HTTP=false`. It then verifies that the container is running, `nginx -t` passes, and the rendered configuration contains the TLS listener. If certificate issuance, file verification, or TLS transition fails, the helper force-recreates Nginx with the HTTP-only configuration and exits non-zero.

## Verify

From a local PowerShell terminal, run:

```powershell
curl.exe -I http://staging-api.miriyum.click/api/v1/consumers/auth/token-refreshes
curl.exe -I https://staging-api.miriyum.click/api/v1/consumers/auth/token-refreshes
curl.exe -I https://staging-api.miriyum.click/actuator/health
curl.exe -I https://staging.miriyum.click/
curl.exe -I https://staging.miriyum.click/api/v1/consumers/auth/token-refreshes
curl.exe -I https://staging.miriyum.click/actuator/health
```

The API HTTP request should redirect to HTTPS. API requests can validly return API-level `401` or
`405`. The UI root must return `200`, and its `/api/` request must reach the same backend. Both
Actuator requests must remain `404`; Actuator health is intentionally private on every public host.

## Recover HTTP-only Nginx

If the initial issuance or TLS transition fails, the helper already triggers this recovery automatically. Use the command below only when an operator needs to restore the HTTP-only configuration explicitly:

```sh
cd /opt/miriyum
set -a
. ./.env
set +a
./certbot.sh recover-http
```

## Renew

Run monthly through SSM until the team approves a scheduled renewal owner:

```sh
cd /opt/miriyum
set -a
. ./.env
set +a
./certbot.sh renew
```

Certbot renews only certificates close to expiry, then Nginx reloads its certificate files. Adding an EventBridge schedule or SSM Association is out of scope and needs separate ownership and cost approval.
