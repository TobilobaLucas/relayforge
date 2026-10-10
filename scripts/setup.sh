#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
if [ -f .env ]; then
  echo '.env already exists. Keeping your existing credentials.'
  exit 0
fi
umask 077
{
  printf 'RELAY_ADMIN_TOKEN=%s\n' "$(openssl rand -base64 32)"
  printf 'RELAY_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
  printf 'DATABASE_PASSWORD=%s\n' "$(openssl rand -base64 32)"
} > .env
echo 'Created .env with random local credentials. This file is ignored by Git.'
