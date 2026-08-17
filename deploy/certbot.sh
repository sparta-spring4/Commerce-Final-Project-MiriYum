#!/bin/sh
set -eu

APP_DIR="${APP_DIR:-/opt/miriyum}"
COMPOSE_FILE="${COMPOSE_FILE:-${APP_DIR}/docker-compose.prod.yml}"
ENV_FILE="${ENV_FILE:-${APP_DIR}/.env}"
CERTBOT_IMAGE="${CERTBOT_IMAGE:-certbot/certbot:v5.7.0}"

require_configuration() {
    : "${STAGING_DOMAIN:?STAGING_DOMAIN is required}"
    : "${LETSENCRYPT_EMAIL:?LETSENCRYPT_EMAIL is required}"
    : "${LETSENCRYPT_DIR:?LETSENCRYPT_DIR is required}"
    : "${CERTBOT_WEBROOT_DIR:?CERTBOT_WEBROOT_DIR is required}"
}

prepare_directories() {
    install -d -m 0700 "${LETSENCRYPT_DIR}"
    install -d -m 0755 "${CERTBOT_WEBROOT_DIR}"
}

compose() {
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

run_certbot() {
    docker run --rm \
        -v "${LETSENCRYPT_DIR}:/etc/letsencrypt" \
        -v "${CERTBOT_WEBROOT_DIR}:/var/www/certbot" \
        "${CERTBOT_IMAGE}" "$@"
}

issue() {
    run_certbot certonly \
        --webroot \
        --webroot-path /var/www/certbot \
        --domain "${STAGING_DOMAIN}" \
        --email "${LETSENCRYPT_EMAIL}" \
        --agree-tos \
        --non-interactive \
        --no-eff-email \
        --keep-until-expiring

    test -r "${LETSENCRYPT_DIR}/live/${STAGING_DOMAIN}/fullchain.pem"
    # Restart runs the Nginx selector again so it can switch from HTTP-only to TLS.
    compose restart nginx
}

renew() {
    run_certbot renew --webroot --webroot-path /var/www/certbot
    compose exec -T nginx nginx -s reload
}

case "${1:-}" in
    issue|renew)
        require_configuration
        prepare_directories
        "$1"
        ;;
    *)
        echo "Usage: $0 {issue|renew}" >&2
        exit 64
        ;;
esac
