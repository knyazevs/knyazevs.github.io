#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# knyazevs — скрипт деплоя сервера на VPS.
# Образ собирается в GitHub Actions (см. .github/workflows/deploy-server.yml)
# и пушится в GHCR; сюда прилетает только IMAGE_REF — тянем и рестартим.
# TLS и reverse-proxy держит Nginx Proxy Manager на хосте
# (api.sknyazev.pro → 127.0.0.1:8091).
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

DEPLOY_PATH="${DEPLOY_PATH:-/opt/knyazevs}"
cd "$DEPLOY_PATH"

# ── Рабочие директории ────────────────────────────────────────────────────────
echo "==> Создание рабочих директорий"
mkdir -p data

# ── Проверка обязательных переменных ─────────────────────────────────────────
# Если хоть одна из этих пуста — сервер либо упадёт на старте
# (SESSION_SECRET через ?: error), либо внешне оживёт, но RAG и LLM-запросы
# будут валиться с 401/500. Лучше падать здесь с понятным сообщением.
REQUIRED_VARS=(
  IMAGE_REF
  SESSION_SECRET
  OPENROUTER_API_KEY
  OPENAI_API_KEY
)
MISSING=()
for var in "${REQUIRED_VARS[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    MISSING+=("$var")
  fi
done

if (( ${#MISSING[@]} > 0 )); then
  echo "❌ Не заданы обязательные переменные: ${MISSING[*]}"
  echo "   Проверьте GitHub → Settings → Secrets and variables → Actions."
  echo "   SESSION_SECRET сгенерировать: openssl rand -hex 32"
  exit 1
fi
echo "==> IMAGE_REF=$IMAGE_REF"

# ── Генерация .env ────────────────────────────────────────────────────────────
echo "==> Генерация .env"
cat > .env << EOF
# Сгенерировано автоматически при деплое $(date -u +"%Y-%m-%dT%H:%M:%SZ")

IMAGE_REF=${IMAGE_REF}
OPENROUTER_API_KEY=${OPENROUTER_API_KEY}
OPENAI_API_KEY=${OPENAI_API_KEY}
SESSION_SECRET=${SESSION_SECRET}
LLM_BASE_URL=${LLM_BASE_URL:-https://openrouter.ai/api/v1}
LLM_MODEL=${LLM_MODEL:-anthropic/claude-opus-4-7}
CLASSIFIER_MODEL=${CLASSIFIER_MODEL:-openai/gpt-4o-mini}
EMBEDDING_BASE_URL=${EMBEDDING_BASE_URL:-https://api.openai.com/v1}
EMBEDDING_MODEL=${EMBEDDING_MODEL:-text-embedding-3-small}
CORS_ALLOWED_ORIGIN=${CORS_ALLOWED_ORIGIN:-https://knyazevs.github.io}
EOF

chmod 600 .env

# ── Pull & up ────────────────────────────────────────────────────────────────
# Никаких build на сервере — тянем готовый K/N-бинарь из GHCR.
echo "==> docker compose pull"
docker compose -f docker-compose.prod.yml pull

echo "==> docker compose up -d"
docker compose -f docker-compose.prod.yml up -d --remove-orphans

# ── Очистка старых образов ────────────────────────────────────────────────────
echo "==> Очистка старых образов"
docker image prune -f

# ── Проверка здоровья ────────────────────────────────────────────────────────
# Бьём прямо в Ktor через host-порт 8091 — до NPM, чтобы health-check
# не зависел от конфигурации proxy/TLS.
# GET /api/health — публичный, без session-auth и rate-limit, отдаёт 200.
echo "==> Ожидание запуска сервера..."
HTTP="000"
ATTEMPTS=60
for i in $(seq 1 "$ATTEMPTS"); do
  sleep 3
  HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 \
    http://127.0.0.1:8091/api/health || true)
  echo "  попытка $i/$ATTEMPTS — статус: $HTTP"
  [[ "$HTTP" == "200" ]] && break
done

if [[ "$HTTP" == "200" ]]; then
  echo "✅ Деплой успешен (статус $HTTP)"
else
  echo "❌ Сервер не ответил за $((ATTEMPTS * 3)) сек (последний статус: $HTTP)"
  docker compose -f docker-compose.prod.yml logs --tail=150 ktor
  exit 1
fi
