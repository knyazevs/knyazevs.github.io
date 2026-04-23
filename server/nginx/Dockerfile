# Stage 1: compile nginx with ssl_fingerprint module (adds $ssl_ja3 variable)
FROM nginx:1.25.3-alpine AS builder

ARG NGINX_VERSION=1.25.3

RUN apk add --no-cache \
    git gcc g++ make \
    openssl-dev pcre2-dev zlib-dev \
    linux-headers wget

WORKDIR /build

# Download nginx source matching the base image version
RUN wget -q "https://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz" \
    && tar xzf "nginx-${NGINX_VERSION}.tar.gz"

# JA3/ssl fingerprint module — adds $ssl_ja3 and $ssl_ja3_full nginx variables
RUN git clone --depth 1 \
    https://github.com/nicholasgasior/nginx-ssl-fingerprint.git \
    /build/nginx-ssl-fingerprint

# Replicate the same ./configure flags as the alpine base image + our module
WORKDIR /build/nginx-${NGINX_VERSION}
RUN ./configure \
    --prefix=/etc/nginx \
    --sbin-path=/usr/sbin/nginx \
    --modules-path=/usr/lib/nginx/modules \
    --conf-path=/etc/nginx/nginx.conf \
    --error-log-path=/var/log/nginx/error.log \
    --http-log-path=/var/log/nginx/access.log \
    --pid-path=/var/run/nginx.pid \
    --with-compat \
    --with-http_ssl_module \
    --with-http_v2_module \
    --with-http_realip_module \
    --with-stream \
    --with-stream_ssl_module \
    --with-stream_ssl_preread_module \
    --add-module=/build/nginx-ssl-fingerprint \
    && make -j"$(nproc)" \
    && make install

# Stage 2: minimal runtime image
FROM nginx:1.25.3-alpine

COPY --from=builder /usr/sbin/nginx /usr/sbin/nginx
COPY nginx.conf /etc/nginx/nginx.conf

EXPOSE 80 443
CMD ["nginx", "-g", "daemon off;"]
