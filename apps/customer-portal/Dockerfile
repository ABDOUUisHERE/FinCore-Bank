FROM node:24.13.0-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
COPY apps ./apps
COPY packages ./packages
RUN npm ci
COPY tsconfig.json ./
RUN npm run build --workspace @fincore/customer-portal
FROM nginxinc/nginx-unprivileged:1.28.0-alpine
COPY infrastructure/docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/apps/customer-portal/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1:8080/health || exit 1
