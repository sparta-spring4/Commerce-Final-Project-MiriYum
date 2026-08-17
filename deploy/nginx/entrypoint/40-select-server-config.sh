#!/bin/sh
set -eu

certificate="/etc/letsencrypt/live/${STAGING_DOMAIN}/fullchain.pem"

if [ -r "${certificate}" ]; then
    template="/opt/miriyum-nginx-templates/https.conf.template"
else
    template="/opt/miriyum-nginx-templates/http.conf.template"
fi

# Restrict envsubst to the Compose variable so Nginx variables such as $host remain intact.
envsubst '${STAGING_DOMAIN}' < "${template}" > /etc/nginx/conf.d/default.conf
