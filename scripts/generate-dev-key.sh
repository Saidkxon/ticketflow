#!/usr/bin/env bash
# Makes the development key that signs access tokens (ADR-0004): deploy/keys/dev-1.pem.
# The key is git-ignored. It is for development and CI only, never for a real deployment.
# Usage: bash scripts/generate-dev-key.sh [key-id]
set -euo pipefail

cd "$(dirname "$0")/.."
id="${1:-dev-1}"
dir="deploy/keys"
file="$dir/$id.pem"

if [ -e "$file" ]; then
  echo "$file already exists, nothing to do."
  exit 0
fi

mkdir -p "$dir"
# PKCS#8 PEM, the format the Identity service reads. 2048 bits is the minimum it accepts.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$file"
# Readable by the container user. Fine for a development key; a real key gets stricter rights.
chmod 644 "$file"
echo "Created $file"
