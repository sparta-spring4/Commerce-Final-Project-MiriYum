FROM node:24-alpine AS build

WORKDIR /app/frontend

COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile --ignore-scripts

COPY frontend/ ./
ARG VITE_KAKAO_MAP_APP_KEY
ARG MIRIYUM_PORTONE_STORE_ID
ARG MIRIYUM_PORTONE_CHANNEL_KEY
ENV VITE_KAKAO_MAP_APP_KEY=${VITE_KAKAO_MAP_APP_KEY}
ENV MIRIYUM_PORTONE_STORE_ID=${MIRIYUM_PORTONE_STORE_ID}
ENV MIRIYUM_PORTONE_CHANNEL_KEY=${MIRIYUM_PORTONE_CHANNEL_KEY}
RUN pnpm run build

FROM nginx:1.27-alpine

COPY frontend/nginx.staging.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/frontend/dist /usr/share/nginx/html
