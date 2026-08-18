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

recover_http() {
    # Do not leave Nginx in a broken TLS state when certificate issuance or reload fails.
    MIRIYUM_STAGING_FORCE_HTTP=true compose up -d --force-recreate nginx
}

activate_tls() {
    # A previous recovery leaves the container with FORCE_HTTP=true. Recreate it with
    # the explicit TLS selector instead of restarting the old container environment.
    if ! MIRIYUM_STAGING_FORCE_HTTP=false compose up -d --force-recreate nginx; then
        return 1
    fi

    if ! compose ps --status running --services nginx | grep -qx nginx; then
        return 1
    fi

    if ! compose exec -T nginx nginx -t; then
        return 1
    fi

    # nginx -t alone also accepts the HTTP template, so assert that TLS was selected.
    compose exec -T nginx grep -Fqx '    listen 443 ssl;' /etc/nginx/conf.d/default.conf
}

issue() {
    if ! run_certbot certonly \
        --webroot \
        --webroot-path /var/www/certbot \
        --domain "${STAGING_DOMAIN}" \
        --email "${LETSENCRYPT_EMAIL}" \
        --agree-tos \
        --non-interactive \
        --no-eff-email \
        --keep-until-expiring; then
        recover_http
        return 1
    fi

    if ! test -f "${LETSENCRYPT_DIR}/live/${STAGING_DOMAIN}/fullchain.pem" \
        || ! test -r "${LETSENCRYPT_DIR}/live/${STAGING_DOMAIN}/fullchain.pem" \
        || ! test -f "${LETSENCRYPT_DIR}/live/${STAGING_DOMAIN}/privkey.pem" \
        || ! test -r "${LETSENCRYPT_DIR}/live/${STAGING_DOMAIN}/privkey.pem"; then
        recover_http
        return 1
    fi

    if ! activate_tls; then
        recover_http
        return 1
    fi
}

renew() {
    run_certbot renew --webroot --webroot-path /var/www/certbot
    compose exec -T nginx nginx -s reload
}

case "${1:-}" in
    issue|renew|recover-http)
        require_configuration
        prepare_directories
        if [ "$1" = "recover-http" ]; then
            recover_http
        else
            "$1"
        fi
        ;;
    *)
        echo "Usage: $0 {issue|renew|recover-http}" >&2
        exit 64
        ;;
esac
