#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd -- "$script_dir/.." && pwd)"

read_secret_file() {
  local variable_name="$1"
  local file_variable="${variable_name}_FILE"
  local file_path="${!file_variable:-}"

  if [[ -z "${!variable_name:-}" && -n "$file_path" ]]; then
    if [[ ! -f "$file_path" || ! -r "$file_path" ]]; then
      printf '%s must name a readable file.\n' "$file_variable" >&2
      exit 2
    fi
    IFS= read -r "secret_value" < "$file_path" || [[ -n "${secret_value:-}" ]]
    printf -v "$variable_name" '%s' "${secret_value:-}"
    export "$variable_name"
    unset secret_value
  fi
}

read_secret_file RADIO_BETA_STORE_PASSWORD
read_secret_file RADIO_BETA_KEY_PASSWORD

if [[ -z "${RADIO_BETA_KEYSTORE:-}" ]]; then
  printf 'Set RADIO_BETA_KEYSTORE to the beta keystore path.\n' >&2
  exit 2
fi

cd "$android_dir"
# Version names follow Lisbon wall time; Android upgrades compare the numeric code.
# Seconds since the Unix epoch remain below Android's 2,100,000,000 limit until 2036.
build_epoch="$(date +%s)"
build_version="0.4.$(TZ=Europe/Lisbon date +%Y%m%d-%H%M%S)"
build_code="$build_epoch"
exec ./gradlew --no-daemon --no-configuration-cache assembleRelease \
  "-PbetaVersionName=$build_version" "-PbetaVersionCode=$build_code" "$@"
