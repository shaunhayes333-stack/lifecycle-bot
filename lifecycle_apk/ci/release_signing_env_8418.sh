# V5.0.8418 — sourced by build.yml's "Build Release APK" step (working directory lifecycle_apk).
# GitHub secrets first: when RELEASE_KEYSTORE_B64 is present it is decoded to a temp keystore and
# RELEASE_KEYSTORE_FILE is exported for app/build.gradle.kts, which then signs with
# RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS (default aate_release) and RELEASE_KEY_PASSWORD
# (default: the store password). Without it RELEASE_KEYSTORE_FILE is unset and the repository
# keystore (keystore/release.keystore with KEYSTORE_PASSWORD / KEY_PASSWORD) signs, as before.
# Returns non-zero (the step fails) on a half-configured secret set.
release_signing_8418() {
    if [ -n "${RELEASE_KEYSTORE_B64:-}" ]; then
        if [ -z "${RELEASE_KEYSTORE_PASSWORD:-}" ]; then
            echo "::error::RELEASE_KEYSTORE_B64 is set but RELEASE_KEYSTORE_PASSWORD is empty"
            return 1
        fi
        local dir="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
        RELEASE_KEYSTORE_FILE="$dir/release_8418.keystore"
        if ! printf '%s' "$RELEASE_KEYSTORE_B64" | base64 -d > "$RELEASE_KEYSTORE_FILE" 2>/dev/null || [ ! -s "$RELEASE_KEYSTORE_FILE" ]; then
            echo "::error::RELEASE_KEYSTORE_B64 did not decode to a keystore"
            rm -f "$RELEASE_KEYSTORE_FILE"
            unset RELEASE_KEYSTORE_FILE
            return 1
        fi
        export RELEASE_KEYSTORE_FILE
        echo "Signing: GitHub secrets keystore (RELEASE_KEYSTORE_B64)"
    else
        unset RELEASE_KEYSTORE_FILE
        echo "Signing: repository keystore (no RELEASE_KEYSTORE_B64 secret)"
    fi
    return 0
}
