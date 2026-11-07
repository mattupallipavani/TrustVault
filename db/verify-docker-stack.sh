#!/usr/bin/env bash
# End-to-end verification of the Docker stack. Reads secrets from .env and
# never prints them. Exit status is non-zero if any check fails.
set -uo pipefail
cd "$(dirname "$0")/.."

DEMO_PASSWORD=$(python3 -c "
import re,sys
for ln in open('.env'):
    if ln.startswith('DEMO_PASSWORD='):
        print(ln.split('=',1)[1].strip()); break
")
[ -n "$DEMO_PASSWORD" ] || { echo "no DEMO_PASSWORD in .env"; exit 1; }

BASE=http://localhost:8080
JAR=$(mktemp); VIEWER_JAR=$(mktemp); ADMIN_JAR=$(mktemp)
FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  PASS  $1 ($2)"; else echo "  FAIL  $1 (got $2, want $3)"; FAIL=1; fi; }

csrf() { # $1 = cookie jar
  curl -s -c "$1" -b "$1" "$BASE/api/auth/csrf" >/dev/null
  awk '/XSRF-TOKEN/{print $7}' "$1" | tail -1
}

login() { # $1 jar, $2 email, $3 password
  local token; token=$(csrf "$1")
  curl -s -o /dev/null -w '%{http_code}' -c "$1" -b "$1" \
    -H "X-XSRF-TOKEN: $token" \
    -d "username=$2" --data-urlencode "password=$3" "$BASE/api/auth/login"
}

# Clear rate-limit counters from any previous run, otherwise this run's own
# logins are throttled by the burst the last run deliberately triggered.
# (the redis image is alpine: sh only, and busybox xargs has no -r)
docker exec trustvault-redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "rate:*" | while read -r k; do redis-cli -a "$REDIS_PASSWORD" --no-auth-warning del "$k" >/dev/null; done' >/dev/null 2>&1
if ! docker exec trustvault-redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "rate:*"' 2>/dev/null | grep -q .; then :; else echo "  NOTE  rate-limit keys still present before the run"; fi

echo "== authentication =="
code=$(login "$JAR" owner@trustvault.local "$DEMO_PASSWORD");            check "owner login" "$code" "200"
code=$(login "$ADMIN_JAR" admin@trustvault.local "$DEMO_PASSWORD");      check "admin login" "$code" "200"
code=$(login "$VIEWER_JAR" viewer@trustvault.local "$DEMO_PASSWORD");    check "viewer login" "$code" "200"

echo "== upload into MinIO =="
SRC=$(mktemp /tmp/tv-e2e-XXXX).txt
printf 'TrustVault docker stack end-to-end upload check\n' > "$SRC"
WANT=$(shasum -a 256 "$SRC" | awk '{print $1}')
TOKEN=$(csrf "$JAR")
UPLOAD=$(curl -s -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $TOKEN" \
  -F "file=@$SRC" -F "category=General" "$BASE/api/documents/upload")
DOC_ID=$(printf '%s' "$UPLOAD" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
check "upload returns a document id" "$([ -n "$DOC_ID" ] && echo yes || echo no)" "yes"

echo "== download and integrity =="
TOKEN=$(csrf "$JAR")
DEST=$(mktemp /tmp/tv-dl-XXXX).txt
code=$(curl -s -o "$DEST" -w '%{http_code}' -b "$JAR" -c "$JAR" "$BASE/api/documents/$DOC_ID/download")
check "owner download" "$code" "200"
GOT=$(shasum -a 256 "$DEST" | awk '{print $1}')
check "downloaded bytes match SHA-256" "$([ "$WANT" = "$GOT" ] && echo match || echo differ)" "match"

echo "== authorization still enforced inside the container =="
TOKEN=$(csrf "$VIEWER_JAR")
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$VIEWER_JAR" -c "$VIEWER_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" "$BASE/api/documents/$DOC_ID/download")
check "viewer cannot read another user's document" "$code" "403"
TOKEN=$(csrf "$VIEWER_JAR")
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$VIEWER_JAR" -c "$VIEWER_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" -F "file=@$SRC" -F "category=General" "$BASE/api/documents/upload")
check "viewer cannot upload" "$code" "403"
TOKEN=$(csrf "$VIEWER_JAR")
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$VIEWER_JAR" -c "$VIEWER_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" "$BASE/api/admin/audit/verify")
check "viewer cannot reach admin audit" "$code" "403"

echo "== audit chain inside the container =="
VERIFY=$(curl -s -b "$ADMIN_JAR" -c "$ADMIN_JAR" "$BASE/api/admin/audit/verify")
printf '        %s\n' "$(printf '%s' "$VERIFY" | head -c 160)"
printf '%s' "$VERIFY" | grep -q '"valid":true' && echo "  PASS  audit chain valid" || { echo "  FAIL  audit chain invalid"; FAIL=1; }

echo "== object really landed in MinIO =="
# MinIO stores each object as <bucket>/<object-name>/xl.meta, so the object
# count is the number of entries inside the bucket directory. The image has no
# find(1); ls is enough.
OBJ=$(docker exec trustvault-minio bash -c 'ls /data/trustvault-documents | wc -l' 2>/dev/null | tr -d ' ')
[ -n "$OBJ" ] && [ "$OBJ" -ge 1 ] && echo "  PASS  MinIO bucket holds $OBJ object(s)" || { echo "  FAIL  no object in MinIO bucket"; FAIL=1; }

echo "== Redis-backed rate limiting =="
# The login limit is 15 per minute per client IP, and the successful logins
# above count towards it, so the burst has to exceed 15.
RL=000
for i in $(seq 1 20); do
  T=$(csrf "$VIEWER_JAR")
  RL=$(curl -s -o /dev/null -w '%{http_code}' -c "$VIEWER_JAR" -b "$VIEWER_JAR" \
    -H "X-XSRF-TOKEN: $T" -d "username=viewer@trustvault.local" --data-urlencode "password=wrong-password" "$BASE/api/auth/login")
  [ "$RL" = "429" ] && break
done
check "repeated failed logins are throttled" "$RL" "429"
KEYS=$(docker exec trustvault-redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning keys "*" | head -20' 2>/dev/null)
[ -n "$KEYS" ] && echo "  PASS  credentials live in Redis: $(printf '%s' "$KEYS" | tr '\n' ' ')" || { echo "  FAIL  no rate-limit keys in Redis"; FAIL=1; }

rm -f "$JAR" "$VIEWER_JAR" "$ADMIN_JAR" "$SRC" "$DEST"
echo
[ "$FAIL" = "0" ] && echo "RESULT: all checks passed" || echo "RESULT: failures above"
exit "$FAIL"
