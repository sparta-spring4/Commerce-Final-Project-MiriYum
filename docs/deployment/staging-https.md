# Staging HTTPS Runbook

This runbook enables HTTPS for the existing staging EC2 endpoint without adding an ALB.

## Preconditions

- `staging-api.miriyum.click` resolves to the staging EC2 public IP.
- The staging security group allows TCP 80 and TCP 443 from the internet.
- The staging CD workflow has deployed `/opt/miriyum/certbot.sh` and the Nginx templates.

## EC2 Environment

Through SSM, add these values to `/opt/miriyum/.env`. Replace the email with the team certificate contact; do not add it to GitHub or Git.

```dotenv
STAGING_DOMAIN=staging-api.miriyum.click
LETSENCRYPT_DIR=/opt/miriyum/letsencrypt
CERTBOT_WEBROOT_DIR=/opt/miriyum/certbot-www
LETSENCRYPT_EMAIL=team-contact@example.com
```

## Issue the Initial Certificate

Run this through SSM after the HTTPS configuration deployment succeeds:

```sh
cd /opt/miriyum
set -a
. ./.env
set +a
./certbot.sh issue
```

The Nginx container restarts after Certbot succeeds and selects the TLS configuration.

## Verify

From a local PowerShell terminal, run:

```powershell
curl.exe -I http://staging-api.miriyum.click/api/v1/consumers/auth/token-refreshes
curl.exe -I https://staging-api.miriyum.click/api/v1/consumers/auth/token-refreshes
curl.exe -I https://staging-api.miriyum.click/actuator/health
```

The first request should redirect to HTTPS. The second should reach the API and can validly return an API-level `401` or `405`. The final request must remain `404`; Actuator health is intentionally private.

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
