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
exec ./gradlew --no-daemon --no-configuration-cache assembleRelease "$@"
