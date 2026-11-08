#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"
RELEASE_JSON="app-release.json"
BRANCH="${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}"

read_field() {
  node -e "console.log(JSON.parse(require('fs').readFileSync('${RELEASE_JSON}','utf8'))['$1'] ?? '')"
}

# The manager polls app-release.json to decide whether an update exists, so the file must not
# reach the branch until the APK it points at can actually be downloaded. semantic-release
# builds the file during prepare, this step publishes it once the release assets are live.
# This fork uses Android APK signing; remove any legacy Morphe GPG signature URL from the manifest.
node - <<'NODE'
const fs = require('fs');
const file = 'app-release.json';
const json = JSON.parse(fs.readFileSync(file, 'utf8'));
delete json.signature_download_url;
fs.writeFileSync(file, JSON.stringify(json, null, 2) + '\n');
NODE
JSON_VERSION=$(read_field version)

# The file is carried in the working tree between phases, so refuse to publish anything that
# does not describe the release we just made rather than committing a stale link
if [ "${JSON_VERSION}" != "${VERSION}" ]; then
  echo "ERROR: ${RELEASE_JSON} describes '${JSON_VERSION}', expected '${VERSION}'"
  exit 1
fi

DOWNLOAD_URL=$(read_field download_url)
APK_ATTEMPTS=30
RETRY_DELAY=10

wait_for_asset() {
  local url="$1"
  local attempts="$2"
  local attempt

  echo "Waiting for ${url}"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if curl -sfIL -o /dev/null "${url}"; then
      echo "Available after ${attempt} attempt(s)"
      return 0
    fi
    if [ "${attempt}" -lt "${attempts}" ]; then
      sleep "${RETRY_DELAY}"
    fi
  done

  echo "ERROR: ${url} is still unavailable after $((attempts * RETRY_DELAY))s"
  return 1
}

wait_for_asset "${DOWNLOAD_URL}" "${APK_ATTEMPTS}"

git config user.name "${GIT_AUTHOR_NAME:-github-actions[bot]}"
git config user.email "${GIT_AUTHOR_EMAIL:-41898282+github-actions[bot]@users.noreply.github.com}"

git add "${RELEASE_JSON}"

if git diff --cached --quiet; then
  echo "No release JSON changes to publish"
  exit 0
fi

git commit -m "chore: Publish release v${VERSION} [skip ci]"

# The release commit was pushed moments ago, so rebase once if the branch moved underneath us
if ! git push origin "HEAD:${BRANCH}"; then
  git pull --rebase origin "${BRANCH}"
  git push origin "HEAD:${BRANCH}"
fi

echo "Published ${RELEASE_JSON} for v${VERSION}"
