FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_TOKEN_URL
ARG VITE_PRESENTATION_MODE=false
ENV VITE_TOKEN_URL=$VITE_TOKEN_URL VITE_PRESENTATION_MODE=$VITE_PRESENTATION_MODE
RUN npm run build
FROM nginxinc/nginx-unprivileged:1.29-alpine
ARG NGINX_CONFIG=nginx.conf
COPY ${NGINX_CONFIG} /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
