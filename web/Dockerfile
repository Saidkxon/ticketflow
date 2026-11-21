# syntax=docker/dockerfile:1
# Build context is the web/ folder:
#   docker build -t ticketflow-web web

FROM node:24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
# Build-time switch for the location question (ADR-0015). Empty or anything but "true" means it cannot be skipped.
ARG VITE_LOCATION_ALLOW_NOT_NOW=false
ENV VITE_LOCATION_ALLOW_NOT_NOW=$VITE_LOCATION_ALLOW_NOT_NOW
# Type check and bundle. Lint and tests run in CI (web-build), not in the image build.
RUN npm run build

FROM nginx/nginx-unprivileged:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
# This image listens on 8080 and runs as a non-root user.
EXPOSE 8080
