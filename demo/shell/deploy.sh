#!/usr/bin/env bash
# Deploy one release of the api service onto a host.
#
# Invoked by the deploy job as: deploy.sh <version>
# DATABASE_URL and SENTRY_DSN come from the job environment.

set -eo pipefail

APP=api
RELEASE_ROOT=/srv/releases
KEEP_RELEASES=5

VERSION=$1
TARGET="$RELEASE_ROOT/$APP/$VERSION"
ARTIFACT="/tmp/$APP-$VERSION.tar.gz"

log() {
  printf '[%s] %s\n' "$(date -u +%FT%TZ)" "$*"
}

log "fetching $APP $VERSION"
curl -sS -o "$ARTIFACT" \
  "https://artifacts.internal/$APP/$VERSION/$APP.tar.gz"

log "unpacking into $TARGET"
mkdir -p "$TARGET"
tar -xzf "$ARTIFACT" -C "$TARGET"

log "writing runtime config"
cat > "$TARGET/.env" <<ENV
DATABASE_URL=$DATABASE_URL
SENTRY_DSN=$SENTRY_DSN
RELEASE_VERSION=$VERSION
ENV

log "switching current symlink"
ln -sfn "$TARGET" "$RELEASE_ROOT/$APP/current"
systemctl restart "$APP"

log "pruning old releases"
cd "$RELEASE_ROOT/$APP"
ls -1t | tail -n +$((KEEP_RELEASES + 1)) | xargs rm -rf

log "deployed $VERSION"
