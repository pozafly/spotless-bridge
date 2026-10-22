#!/usr/bin/env bash
set -euo pipefail

: "${JETBRAINS_MARKETPLACE_TOKEN:?Set JETBRAINS_MARKETPLACE_TOKEN}"
: "${RELEASE_VERSION:?Set RELEASE_VERSION}"
: "${RUNNER_TEMP:?Set RUNNER_TEMP}"
: "${GITHUB_STEP_SUMMARY:?Set GITHUB_STEP_SUMMARY}"
zip=${1:?Pass the verified plugin ZIP}
test -s "$zip"

# Reuse the verified ZIP. Never retry an upload with an ambiguous outcome.
if ! curl --fail-with-body --silent --show-error --connect-timeout 20 --max-time 180 \
  --header "Authorization: Bearer $JETBRAINS_MARKETPLACE_TOKEN" \
  --form "pluginId=35218" \
  --form "file=@$zip;type=application/zip" \
  --output "$RUNNER_TEMP/jetbrains-upload-response.json" \
  https://plugins.jetbrains.com/api/updates/upload; then
  echo "Marketplace upload failed or its outcome is unknown. Check the plugin's Versions page before retrying." >&2
  if [ -s "$RUNNER_TEMP/jetbrains-upload-response.json" ]; then
    cat "$RUNNER_TEMP/jetbrains-upload-response.json" >&2
  fi
  exit 1
fi

echo "JetBrains Marketplace: upload accepted; review status is separate from upload success."
echo "JetBrains Marketplace: $RELEASE_VERSION uploaded to Stable. Check [review status](https://plugins.jetbrains.com/plugin/35218-spotless-bridge/versions)." >> "$GITHUB_STEP_SUMMARY"
