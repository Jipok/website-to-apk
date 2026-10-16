#!/usr/bin/env bash
set -eu

# Color definitions
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color
readonly BOLD='\033[1m'

# Info for keystore generation
INFO="CN=Developer, OU=Organization, O=Company, L=City, S=State, C=US"

log() {
    echo -e "${GREEN}[+]${NC} $1"
}

info() {
    echo -e "${BLUE}[*]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[!]${NC} $1"
}

error() {
    echo -e "${RED}[!]${NC} $1"
    exit 1
}

try() {
    local log_file=$(mktemp)

    if [ $# -eq 1 ]; then
        # Если передан один аргумент - используем eval для сложных команд
        if ! eval "$1" &> "$log_file"; then
            echo -e "${RED}[!]${NC} Failed: $1"
            cat "$log_file"
            rm -f "$log_file"
            exit 1
        fi
    else
        # Если несколько аргументов - запускаем напрямую
        if ! "$@" &> "$log_file"; then
            echo -e "${RED}[!]${NC} Failed: $*"
            cat "$log_file"
            rm -f "$log_file"
            exit 1
        fi
    fi
    rm -f "$log_file"
}


set_var() {
    local java_file="$SRC_JAVA/MainActivity.java"
    [ ! -f "$java_file" ] && error "MainActivity.java not found"

    local pattern="$@"
    [ -z "$pattern" ] && error "Empty pattern. Usage: set_var \"varName = value\""

    # Extract variable name and new value
    local var_name="${pattern%% =*}"
    local new_value="${pattern#*= }"

    # No quote handling here: `apply_config` already stripped a *pair* of surrounding quotes on the
    # way in. Stripping per edge a second time ate a real trailing quote — `userAgent = "Say "hi""`
    # shipped the literal `Say "hi` (tests/run.sh check_value_quotes).

    # `true`/`false`/an integer go in bare; everything else becomes a Java string literal, so a
    # backslash or a double quote in the config has to survive the Java compiler
    local java_value="$new_value"
    if [[ ! "$new_value" =~ ^(true|false)$ ]] && [[ ! "$new_value" =~ ^[0-9]+$ ]]; then
        java_value="${new_value//\\/\\\\}"
        java_value="${java_value//\"/\\\"}"
        java_value="\"$java_value\""
    fi

    local tmp_file
    tmp_file=$(mktemp)

    # Only the marked config block counts: `mainURL` also appears as `mainURL = url;` in
    # onNewIntent and `cookies` as a method local, so an unanchored search depends on declaration
    # order. The value travels in the environment, not via `awk -v`, which runs escape processing
    # and turned `Mozilla\5.0` into `Mozilla.0`.
    if ! SETVAR_FIELD="^[[:space:]]*[^[:space:]]+[[:space:]]+$var_name[[:space:]]*=" SETVAR_VALUE="$java_value" awk '
        /\/\/ <webtoapk:config>/   { in_config = 1 }
        /\/\/ <\/webtoapk:config>/ { in_config = 0 }
        in_config && !found && $0 ~ ENVIRON["SETVAR_FIELD"] {
            match($0, ENVIRON["SETVAR_FIELD"])
            print substr($0, 1, RLENGTH) " " ENVIRON["SETVAR_VALUE"] ";"
            found = 1
            next
        }
        { print }
        END { if (!found) exit 1 }
    ' "$java_file" > "$tmp_file"; then
        rm -f "$tmp_file"
        error "Variable '$var_name' not found in the config block of MainActivity.java"
    fi

    # Nothing changed when the merged config matches the template's default
    if diff -q "$java_file" "$tmp_file" >/dev/null; then
        rm "$tmp_file"
        return 0
    fi

    mv "$tmp_file" "$java_file"
    log "Updated $var_name to $java_value"

    # Handle permissions based on specific variable names
    case "$var_name" in
        "geolocationEnabled")
            # Android 12+ offers "precise or approximate" and grants ACCESS_COARSE_LOCATION alone
            # when the user picks approximate, so both have to be declared and FINE is not required
            update_permission "android.permission.ACCESS_FINE_LOCATION" "$new_value"
            update_permission "android.permission.ACCESS_COARSE_LOCATION" "$new_value"
            ;;
        "cameraEnabled")
            update_permission "android.permission.CAMERA" "$new_value"
            ;;
        "microphoneEnabled")
            update_permission "android.permission.RECORD_AUDIO" "$new_value"
            update_permission "android.permission.MODIFY_AUDIO_SETTINGS" "$new_value"
            ;;
    esac
}


# The signing key used to live in app/; move it out of the generated tree for old checkouts
migrate_keystore() {
    if [ -f app/my-release-key.jks ] && [ ! -f my-release-key.jks ]; then
        try mv app/my-release-key.jks my-release-key.jks
        info "Moved the signing key to ./my-release-key.jks"
    fi
}


# Remembers what the tree was generated for, so that `./make.sh apk` works without a config: the
# `appid` (short form of the application id, com.<appid>.webtoapk — used for the artifact name, the
# adb launch and config.gradle; nothing to do with the Java package, $JAVA_PKG) and the version, which
# the banner prints even when `apply_config` ran in another process. Reading the version back does not
# reopen D39: `apply_config` resets both fields *before* it reads the first config key, so nothing can
# be inherited from the previous generation. The name is stored escaped (write_config_gradle), so `\`
# and `'` have to come back out here — `7.7'beta` must not print as `7.7\'beta`.
read_generated_config() {
    local file="config.gradle"
    appid=$(sed -n 's/^ext\.appId *= *//p' "$file" 2>/dev/null | tr -d "'\"" | cut -d. -f2 | head -n1)
    appid="${appid:-myexample}"
    # A missing key falls back to what apply_config starts from; an empty *value* is kept (hence no
    # `if`, the way the name gets one for a different reason: `''` is a value, silence is not)
    appVersionCode=$(sed -n 's/^ext\.appVersionCode *= *//p' "$file" 2>/dev/null | head -n1)
    appVersionCode="${appVersionCode:-1}"
    appVersionName=$(sed -n 's/^ext\.appVersionName *= *//p' "$file" 2>/dev/null | head -n1)
    if [ -z "$appVersionName" ]; then
        appVersionName="1.0"
    else
        appVersionName="${appVersionName#\'}"
        appVersionName="${appVersionName%\'}"
        appVersionName="${appVersionName//\\\\/\\}"
        appVersionName="${appVersionName//\\\'/\'}"
    fi
}


# The application id and the version are read by build.gradle from this generated
# file, so the tracked build.gradle stays the same for every app.
write_config_gradle() {
    local file="config.gradle"
    # Groovy single-quoted strings process backslash escapes. A lone `'` ends the literal (Gradle:
    # `Unexpected character` and the build dies); a lone `\` eats the next character — `a\b` became
    # `a` + backspace, measured, and nothing said so. appId needs none of this: it is validated to
    # [a-zA-Z0-9_]. Escape `\` first, or the added backslashes get escaped in turn.
    local version_name="${appVersionName//\\/\\\\}"
    version_name="${version_name//\'/\\\'}"
    local content="ext.appId = 'com.$appid.webtoapk'
ext.appVersionCode = $appVersionCode
ext.appVersionName = '$version_name'"

    if [ -f "$file" ] && [ "$content" = "$(cat "$file")" ]; then
        return 0
    fi

    printf '%s\n' "$content" > "$file"
}


# src/ is the source of truth; app/src is unpacked from it on every apply_config, so a
# configured tree never accumulates edits and `clean` only has to delete the result.
sync_sources() {
    try rm -rf app/src/main/java app/src/main/res app/src/main/assets app/src/main/AndroidManifest.xml
    try mkdir -p "$SRC_JAVA" app/src/main/assets

    try cp -a src/build.gradle src/proguard-rules.pro app/
    try cp -a src/java/. "$SRC_JAVA/"
    try cp -a src/res app/src/main/
    try cp -a src/assets/. app/src/main/assets/
    try cp -a src/AndroidManifest.xml app/src/main/
}


merge_config_with_default() {
    local default_conf="default.conf"
    local user_conf="$1"
    local merged_conf
    merged_conf=$(mktemp)

    # Temporary file for default lines that are missing in user config
    local temp_defaults
    temp_defaults=$(mktemp)

    # For each non-empty, non-comment line in default.conf
    while IFS= read -r line; do
        # Extract key (everything up to '=')
        key=$(echo "$line" | cut -d '=' -f1 | xargs)
        if [ -n "$key" ]; then
            # Check if the key is missing in the user config
            if ! grep -q -E "^[[:space:]]*$key[[:space:]]*=" "$user_conf"; then
                # Key is missing – add the default line
                echo "$line" >> "$temp_defaults"
            fi
        fi
    done < <(grep -vE '^[[:space:]]*(#|$)' "$default_conf")

    # Now combine default lines (if any) with the user configuration.
    # The defaults will be added on top, but since they are defined earlier they
    # can be overridden by any subsequent assignment (если вдруг порядок имеет значение).
    cat "$temp_defaults" "$user_conf" > "$merged_conf"

    rm -f "$temp_defaults"
    echo "$merged_conf"
}

apply_config() {
    local config_file="${1:-webapk.conf}"

    # If config file is not found in project root, try in caller's directory
    if [ ! -f "$config_file" ] && [ -f "$ORIGINAL_PWD/$config_file" ]; then
        config_file="$ORIGINAL_PWD/$config_file"
    fi

    [ ! -f "$config_file" ] && error "Config file not found: $config_file"

    export CONFIG_DIR="$(dirname "$config_file")"

    info "Using config: $config_file"

    config_file=$(merge_config_with_default "$config_file")

    # The template lands at the fixed package path, so the id no longer has to be known before the
    # unpack: it only rewrites the applicationId in config.gradle, from the key loop below
    sync_sources

    # config.gradle is a pure function of *this* config, so start from the defaults: the dispatch
    # reads the previous generation into these globals, and neither chid nor set_version rewrote the
    # file when nothing changed, so a value could be inherited. A mid-loop error now leaves the
    # previous file intact instead of half-updated (the new appId with the old version), D39.
    appid="myexample"
    appVersionCode=1
    appVersionName="1.0"

    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Skip empty lines and comments
        [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue

        # An inline comment is ` # ...` — but not inside a quoted value. Everything after the first `=`
        # is the value, and a value that has to keep a ` #` of its own (a display name with "#1", a
        # Basic Auth password, a cookie) is written in quotes; `set_var()` strips them later. Cutting
        # at ` #` unconditionally used to change such values silently — the app shipped under a
        # different name and nothing said so.
        if [[ "$value" =~ ^[[:space:]]*\"(.*)\"[[:space:]]*(#.*)?$ ]] \
            || [[ "$value" =~ ^[[:space:]]*\'(.*)\'[[:space:]]*(#.*)?$ ]]; then
            value="${BASH_REMATCH[1]}"
        else
            value="${value%%[[:space:]]#*}"
        fi

        # Remove spaces, tabs, and invisible unicode characters (zero-width space)
        key=$(echo "$key" | tr -cd '[:alnum:]_')
        # Trim whitespaces. `printf`, not `echo`: a bare `-n` (or -e/-E) value came back empty
        value=$(printf '%s' "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

        case "$key" in
            "id")
                chid "$value"
                ;;
            "name")
                rename "$value"
                ;;
            "versionCode"|"versionName")
                set_version "$key" "$value"
                ;;
            "deeplink")
                set_deep_link "$value"
                ;;
            "trustUserCA")
                set_network_security_config "$value"
                ;;
            "ca")
                set_ca "$value"
                ;;
            "icon")
                set_icon "$value"
                ;;
            "scripts")
                set_userscripts "$value"
                ;;
            "site")
                set_site "$value"
                ;;
            *)
                set_var "$key = $value"
                ;;
        esac
    done < <(sed -e '/^[[:space:]]*#/d' "$config_file")

    # One writer, after the whole config has been read
    write_config_gradle

    # The merged config is a temp file (defaults + the user's, so it can hold basicAuth and cookies)
    rm -f "$config_file"
}


build_artifact() {
    [ ! -d app/src/main/java ] && error "Sources are not unpacked. Run './make.sh build <config>' first"
    [ ! -f "my-release-key.jks" ] && error "Keystore file not found. Run './make.sh keygen' first"

    read_generated_config

    local ext="$1" task dir
    case "$ext" in
        apk) task="assembleRelease"; dir="apk" ;;
        aab) task="bundleRelease";   dir="bundle" ;;
        *)   error "Unknown artifact type: $ext" ;;
    esac

    local output="app/build/outputs/$dir/release/app-release.$ext"
    local destination="$appid.$ext"
    local label="${ext^^}"

    rm -f "$output"

    info "Building $label..."
    try ./gradlew "$task" --no-daemon --quiet

    [ ! -f "$output" ] && error "$label build failed: output file not found"

    try cp "$output" "$destination"

    log "$label successfully built and signed"
    echo -e "${BOLD}----------------"
    echo -e "Final $label copied to: ${GREEN}${destination}${NC}"
    echo -e "Size: ${BLUE}$(du -h "$output" | cut -f1)${NC}"
    echo -e "Package: ${BLUE}com.${appid}.webtoapk${NC}"
    echo -e "App name: ${BLUE}$(grep -o 'app_name">[^<]*' app/src/main/res/values/app_name.xml | cut -d'>' -f2)${NC}"
    echo -e "Version: ${BLUE}$appVersionName ($appVersionCode)${NC}"
    echo -e "URL: ${BLUE}$(grep -h 'String mainURL =' "$SRC_JAVA"/*.java | head -1 | cut -d'"' -f2)${NC}"
    echo -e "${BOLD}----------------${NC}"
}

apk() { build_artifact apk; }
aab() { build_artifact aab; }

# Named test_apk because a function called `test` would shadow the shell builtin; ./make.sh test
# maps to it at the bottom.
test_apk() {
    info "Detected application id: $appid"
    try "adb install app/build/outputs/apk/release/app-release.apk"
    try "adb logcat -c" # clean logs
    try "adb shell am start -n com.$appid.webtoapk/$JAVA_PKG.MainActivity"
    echo "=========================="
    adb logcat | grep -oP "(?<=WebToApk: ).*"
}

keygen() {
    # Never replace an existing key. It signs every published build, and a lost key cannot be
    # replaced for updates: a new one means a new package identity. Delete it by hand if you really
    # want a new one.
    [ -f "my-release-key.jks" ] && error "my-release-key.jks already exists - delete it yourself if you really want a new one"

    info "Generating keystore..."
    try "keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my -storepass '123456' -keypass '123456' -dname '$INFO'"
    log "Keystore generated successfully"
}

clean() {
    info "Cleaning generated sources and build files..."
    try rm -rf app build config.gradle .gradle
    log "Clean completed"
}


chid() {
    [ -z "$1" ] && error "Please provide an application ID"

    if ! [[ $1 =~ ^[a-zA-Z][a-zA-Z0-9_]*$ ]]; then
        error "Invalid application ID. Use only letters, numbers and underscores, start with a letter"
    fi

    appid="$1"
    log "Application ID: com.$1.webtoapk"
}


# XML text escaping, for values that end up as text inside a resource
xml_escape() {
    sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

# aapt's own string-resource escapes, applied *after* xml_escape (never before: an `&` that has become
# `&amp;` must not be touched again). A lone backslash is eaten by aapt — `Back\slash` shipped as
# `Backslash` — an unescaped apostrophe is a hard aapt2 error (`Bob's App`), and a leading `@`/`?` is
# escaped defensively: aapt2 36 keeps `@Home` as text (measured), but the escape costs nothing and is
# what the docs ask for. No sed metacharacter can reach the output any more: the name is written as
# data, not through an `s|…|…|` replacement, so a `|` in it is just a character.
resource_escape() {
    sed -e 's/\\/\\\\/g' -e "s/'/\\\\'/g" -e 's/"/\\"/g'
}

# name = ... — the display name gets its own generated resource file instead of being sed-edited into
# the six strings.xml files: one value, one escaping pass, and the same name in every locale, which is
# what the old per-locale loop wrote anyway. sync_sources() unpacks a fresh res/ before this runs, so
# no stale app_name.xml can win. The old sed also failed *loudly* on `|` (unknown option to 's') and
# left the tree with the default name on half the files — three separate ways to lose the name.
rename() {
    local new_name="$*"
    local xml_file="app/src/main/res/values/app_name.xml"
    local escaped_name current_name tmp_file

    if [ -z "$new_name" ]; then
        error "Please provide a display name\nUsage: $0 display_name \"My App Name\""
    fi

    escaped_name=$(printf '%s' "$new_name" | xml_escape | resource_escape)
    case "$escaped_name" in
        # bash, not sed: `\@` through a sed replacement is not portable — GNU sed drops the backslash
        @*|\?*) escaped_name="\\$escaped_name" ;;
    esac

    # Not idempotency (the file is regenerated every run) but DX: without this the log claims a change
    # that did not happen on every build that leaves the name at its default
    current_name=$(grep -o 'app_name">[^<]*' "$xml_file" 2>/dev/null | cut -d'>' -f2)
    if [ "$current_name" = "$escaped_name" ]; then
        return 0
    fi

    tmp_file=$(mktemp)
    cat > "$tmp_file" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$escaped_name</string>
</resources>
EOF
    try mv "$tmp_file" "$xml_file"
    log "Display name changed to: $new_name"
}


# Set versionCode / versionName for the generated config.gradle. It is written once, at the end of
# apply_config: a partial write here used to survive a later error in the loop (D39).
set_version() {
    local key="$1" value="$2"

    case "$key" in
        "versionCode")
            [[ "$value" =~ ^[0-9]+$ ]] || error "versionCode must be an integer, got: '$value'"
            appVersionCode="$value"
            log "versionCode: $value"
            ;;
        "versionName")
            # The pair is stripped on the way in, by `apply_config` (set_var says why not here)
            appVersionName="$value"
            log "versionName: $value"
            ;;
        *)
            error "Unknown version field: $key"
            ;;
    esac
}


set_deep_link() {
    local manifest_file="app/src/main/AndroidManifest.xml"
    local hosts_input="$@"

    # Empty value: sync_sources() unpacks the template manifest, whose only intent-filter is the
    # launcher, so there is no old VIEW filter to strip and nothing to add
    [ -z "$hosts_input" ] && return 0

    # A host is letters, digits, dots and dashes, optionally behind a `*.` wildcard. Anything else
    # (a path, a quote) goes straight into AndroidManifest.xml, where it either breaks the XML or
    # quietly makes the filter unusable — an error here names the value instead.
    local host
    for host in $hosts_input; do
        [[ "$host" =~ ^(\*\.)?[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || error "Invalid deeplink host: '$host'"
    done

    local tmp_file
    tmp_file=$(mktemp)

    # Insert the VIEW/BROWSABLE filter right after the launcher intent-filter
    awk -v hosts="$hosts_input" '
        /<\/intent-filter>/ && !inserted {
            print
            print "            <intent-filter>"
            print "                <action android:name=\"android.intent.action.VIEW\" />"
            print "                <category android:name=\"android.intent.category.DEFAULT\" />"
            print "                <category android:name=\"android.intent.category.BROWSABLE\" />"
            print "                <data android:scheme=\"http\" />"
            print "                <data android:scheme=\"https\" />"

            n = split(hosts, h_array, " ")
            for (i = 1; i <= n; i++) {
                 if (h_array[i] != "") {
                    print "                <data android:host=\"" h_array[i] "\" />"
                 }
            }

            print "            </intent-filter>"
            inserted=1
            next
        }
        { print }
    ' "$manifest_file" > "$tmp_file"

    log "Setting deeplinks for: $hosts_input"
    try mv "$tmp_file" "$manifest_file"
}

# trustUserCA controls whether the device's user-installed CAs are trusted.
# android:networkSecurityConfig is hardcoded in AndroidManifest.xml, so CAs bundled into
# res/raw by set_ca() (referenced as <certificates src="@raw/name" />) work out of the box.
set_network_security_config() {
    local nsc_file="app/src/main/res/xml/network_security_config.xml"
    local enabled="$1"
    local tmp_file

    # false is the default and is exactly what the template ships: system CAs only
    [ "$enabled" = "true" ] || return 0

    tmp_file=$(mktemp)
    sed 's#^\([[:space:]]*\)<certificates src="system" />#\1<certificates src="system" />\n\1<certificates src="user" />#' "$nsc_file" > "$tmp_file"
    log "Trusting user-installed CAs"
    try mv "$tmp_file" "$nsc_file"
}


# Turn a config value into a list of paths, one per line. Entries are space-separated, a relative
# path is resolved against the config file's directory, and globs expand there too: the shell would
# otherwise expand them at the call site, against the project root, before CONFIG_DIR is known.
# A pattern that matches nothing is passed through as-is, so the caller can report it.
config_files() {
    local patterns pattern f
    read -ra patterns <<< "$1"
    for pattern in "${patterns[@]}"; do
        [ -n "${CONFIG_DIR:-}" ] && [[ "$pattern" != /* ]] && pattern="$CONFIG_DIR/$pattern"
        for f in $pattern; do printf '%s\n' "$f"; done
    done
}


# ca = my_root_ca.pem [another_ca.pem] — bundle certificates into res/raw and trust them
# from the network security config. Relative paths are resolved against the config file.
# Nothing is deleted here: sync_sources() unpacks a fresh res/ every run, so certificates
# dropped from the config disappear on their own.
set_ca() {
    local nsc_file="app/src/main/res/xml/network_security_config.xml"
    local raw_dir="app/src/main/res/raw"
    local src name tmp_file
    tmp_file=$(mktemp)
    cp "$nsc_file" "$tmp_file"

    while IFS= read -r src; do
        [ ! -f "$src" ] && error "CA file not found: $src"

        # The file name becomes the res/raw resource name, so it must be a valid one
        name=$(basename "$src")
        name="${name%.*}"
        [[ "$name" =~ ^[a-z][a-z0-9_]*$ ]] || error "CA file name must be lowercase letters, digits and underscores: $(basename "$src")"

        mkdir -p "$raw_dir"
        cp "$src" "$raw_dir/$name.pem"
        log "Bundled CA certificate: $name.pem"

        # Trust it, unless the template already references it
        grep -q "@raw/$name" "$tmp_file" && continue
        sed -i "s#^\([[:space:]]*\)<certificates src=\"system\" />#&\n\1<certificates src=\"@raw/$name\" />#" "$tmp_file"
    done < <(config_files "$1")

    # res/ is unpacked fresh, so the template NSC is the baseline every run: no diff to guard
    try mv "$tmp_file" "$nsc_file"
}


set_icon() {
    local icon_path="$@"
    local dest_file="app/src/main/res/mipmap/ic_launcher.png"

    # No icon in the config: sync_sources already unpacked the template icon
    if [ -z "$icon_path" ]; then
        return 0
    fi

    # If icon_path is not absolute, prepend CONFIG_DIR
    if [ -n "${CONFIG_DIR:-}" ] && [[ "$icon_path" != /* ]]; then
        icon_path="$CONFIG_DIR/$icon_path"
    fi

    # Validate icon
    [ ! -f "$icon_path" ] && error "Icon file not found: $icon_path"

    # Check if file is PNG
    file_type=$(file -b --mime-type "$icon_path")
    if [ "$file_type" != "image/png" ]; then
        error "Icon must be in PNG format, got: $file_type"
    fi

    # Not idempotency (res/ is unpacked fresh every run) but DX: skip the copy and the log when the
    # config's icon is already the one the template ships
    cmp -s "$icon_path" "$dest_file" && return 0

    try "cp \"$icon_path\" \"$dest_file\""
    log "Icon updated successfully"
}


set_userscripts() {
    local scripts_dir="app/src/main/assets/userscripts"
    mkdir -p "$scripts_dir"

    # Empty value: sync_sources() unpacks the template's assets (helpers.js only), so any script the
    # config dropped is already gone
    [ -z "${1:-}" ] && return 0

    # The destination starts empty for the same reason, so every configured script is a new one
    local file base_name
    while IFS= read -r file; do
        # Silently skipping a typo'd path shipped an app without the script it asked for; set_ca and
        # set_site both error on a path that is not there, so this one does too
        [ ! -f "$file" ] && error "Userscript file not found: $file"
        base_name=$(basename "$file")
        cp "$file" "$scripts_dir/$base_name"
        log "Added userscript: $base_name"
    done < <(config_files "$1")
}


set_site() {
    local src="$1"
    local dest="app/src/main/assets/site"

    # Empty value: sync_sources() unpacks the template's assets, which have no site/
    [ -z "$src" ] && return 0

    # Relative paths are resolved against the config file location
    if [ -n "${CONFIG_DIR:-}" ] && [[ "$src" != /* ]]; then
        src="$CONFIG_DIR/$src"
    fi

    [ ! -d "$src" ] && error "Site directory not found: $src"
    [ ! -f "$src/index.html" ] && error "Site directory must contain index.html: $src"

    rm -rf "$dest"
    mkdir -p "$dest"
    cp -a "$src/." "$dest/"
    log "Bundled local site: $src"
}


update_permission() {
    local permission_name="$1"
    local enabled="$2"
    local manifest_file="app/src/main/AndroidManifest.xml"

    # Construct the full permission tag
    local permission_tag="<uses-permission android:name=\"$permission_name\" />"

    # false is the default: the freshly unpacked manifest simply does not declare it
    [ "$enabled" = "true" ] || return 0

    # Nothing to do if the permission is already declared
    grep -q "$permission_name" "$manifest_file" && return 0

    local tmp_file
    tmp_file=$(mktemp)

    awk -v tag="$permission_tag" '
    {
        print $0
        # Insert permission right after the opening manifest tag
        if ($0 ~ /<manifest /) {
            print "    " tag
        }
    }' "$manifest_file" > "$tmp_file"

    log "Added permission: $permission_name"
    try mv "$tmp_file" "$manifest_file"
}


get_tools() {
    info "Downloading Android Command Line Tools..."

    case "$(uname -s)" in
        Linux*)     os_type="linux";;
        # Darwin*)    os_type="mac";;
        *)         error "Unsupported OS";;
    esac

    tmp_dir=$(mktemp -d)
    cd "$tmp_dir"

    try "wget -q --show-progress 'https://dl.google.com/android/repository/commandlinetools-${os_type}-11076708_latest.zip' -O cmdline-tools.zip"

    info "Extracting tools..."
    try "unzip -q cmdline-tools.zip"
    try "mkdir -p '$ANDROID_HOME/cmdline-tools/latest'"
    try "mv cmdline-tools/* '$ANDROID_HOME/cmdline-tools/latest/'"

    cd "$OLDPWD"
    rm -rf "$tmp_dir"

    info "Accepting licenses..."
    try "yes | '$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager' --sdk_root=$ANDROID_HOME --licenses"

    # platforms;android-36 is the compileSdk, and 35.0.0 is the build-tools revision AGP 8.10
    # defaults to; anything else just makes AGP download its own on top
    info "Installing necessary SDK components..."
    try "'$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager' --sdk_root=$ANDROID_HOME \
        'platform-tools' \
        'platforms;android-36' \
        'build-tools;35.0.0'"

    log "Android SDK successfully installed!"
}


regradle() {
    # The version is read from the wrapper properties that are part of the repository: hardcoding one
    # here is how this script ended up writing a 7.4 wrapper while AGP 8.10 needs 8.11.1 or newer.
    local version
    version=$(sed -n 's#.*gradle-\([0-9][0-9.]*\)-all\.zip.*#\1#p' gradle/wrapper/gradle-wrapper.properties 2>/dev/null)
    [ -n "$version" ] || error "No Gradle version in gradle/wrapper/gradle-wrapper.properties"

    info "Reinstalling Gradle $version..."
    try rm -rf gradle gradlew .gradle .gradle-cache
    try mkdir -p gradle/wrapper

    cat > gradle/wrapper/gradle-wrapper.properties << EOL
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-${version}-all.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOL

    try wget -q --show-progress "https://raw.githubusercontent.com/gradle/gradle/v${version}/gradle/wrapper/gradle-wrapper.jar" -O gradle/wrapper/gradle-wrapper.jar
    try wget -q --show-progress "https://raw.githubusercontent.com/gradle/gradle/v${version}/gradlew" -O gradlew
    try chmod +x gradlew

    log "Gradle reinstalled successfully"
}


get_java() {
    local install_dir="$PWD/jvm"
    local jdk_version="17.0.2"
    local jdk_hash="0022753d0cceecacdd3a795dd4cea2bd7ffdf9dc06e22ffd1be98411742fbb44"
    local jdk_url="https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_linux-x64_bin.tar.gz"

    if [ -d "$install_dir/jdk-${jdk_version}" ]; then
        info "OpenJDK ${jdk_version} already downloaded"
        export JAVA_HOME="$install_dir/jdk-${jdk_version}"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi

    local tmp_dir=$(mktemp -d)
    cd "$tmp_dir"

    info "Downloading OpenJDK ${jdk_version}..."
    try "wget -q --show-progress '$jdk_url' -O openjdk.tar.gz"

    info "Verifying checksum..."
    try "echo '${jdk_hash} openjdk.tar.gz' | sha256sum -c -"

    info "Unpacking to ${install_dir}..."
    try "mkdir -p '$install_dir'"
    try "tar xf openjdk.tar.gz"
    try "mv jdk-${jdk_version} '$install_dir/'"

    cd "$OLDPWD"
    rm -rf "$tmp_dir"

    export JAVA_HOME="$install_dir/jdk-${jdk_version}"
    export PATH="$JAVA_HOME/bin:$PATH"

    log "OpenJDK ${jdk_version} downloaded successfully!"
}


# Java 17 or newer, and a real JDK. AGP 8.x runs on any 17+ (measured: a JDK 21 produces the same
# classes.dex, resources.arsc and AndroidManifest.xml as the JDK 17 build), but a *JRE* passes a
# version test and then the build dies inside Gradle with "Toolchain ... does not provide the
# required capabilities: [JAVA_COMPILER]".
readonly MIN_JAVA=17

# "21.0.12.1" -> 21, "1.8.0_382" -> 8, empty when unparsable
java_major() {
    printf '%s' "$1" | sed 's/^1\.//; s/[^0-9].*$//'
}

# Is $1 a JDK (bin/javac) of version MIN_JAVA or newer?
java_ok() {
    [ -x "$1/bin/javac" ] || return 1
    local v
    v=$(java_major "$("$1/bin/java" -version 2>&1 | head -n 1 | cut -d'"' -f2)")
    [ -n "$v" ] && [ "$v" -ge "$MIN_JAVA" ]
}

# Check Java version and update JAVA_HOME if needed
check_and_find_java() {
    # First check existing JAVA_HOME
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        if java_ok "$JAVA_HOME"; then
            info "Using system JAVA_HOME: $JAVA_HOME"
            export PATH="$JAVA_HOME/bin:$PATH"
            return 0
        fi
        warn "Current JAVA_HOME is not a JDK $MIN_JAVA or newer: $JAVA_HOME"
    fi

    # Then check local installation
    if [ -x "$PWD/jvm/jdk-17.0.2/bin/javac" ]; then
        info "Using local Java installation"
        export JAVA_HOME="$PWD/jvm/jdk-17.0.2"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi

    # Finally check /usr/lib/jvm
    if [ -d "/usr/lib/jvm" ]; then
        while IFS= read -r java_path; do
            if java_ok "$java_path"; then
                info "Found system Java: $java_path"
                export JAVA_HOME="$java_path"
                export PATH="$JAVA_HOME/bin:$PATH"
                return 0
            fi
        done < <(find /usr/lib/jvm -maxdepth 1 -type d)
    fi

    # No suitable Java found
    return 1
}

build() {
    local config_arg="${1-}"

    if [ -n "$config_arg" ] && [ -d "$config_arg" ]; then
        config_arg="$config_arg/webapk.conf"
    fi

    apply_config "$config_arg"
    apk
}

###############################################################################

ORIGINAL_PWD="$PWD"

# Change directory to the directory where make.sh resides (project root)
try cd "$(dirname "$0")"

# The Java package is fixed: `namespace` in build.gradle owns R/BuildConfig and resolves the relative
# names in the manifest, while the application id (`ext.appId` in the generated config.gradle) is a
# separate thing. Because the package no longer follows the config, sync_sources() can copy src/java/
# in verbatim and the generated tree is the template plus the config's field values.
readonly JAVA_PKG="com.myexample.webtoapk"
readonly SRC_JAVA="app/src/main/java/com/myexample/webtoapk"

# Usage only needs the script itself, so answer it before the wget/Java/SDK checks, which can prompt
if [ $# -eq 0 ]; then
    echo -e "${BOLD}Usage:${NC}"
    echo -e "  ${BLUE}$0 keygen${NC}          - Generate signing key"
    echo -e "  ${BLUE}$0 build${NC} [config]  - Apply configuration and build"
    echo -e "  ${BLUE}$0 test${NC}            - Install and test APK via adb, show logs"
    echo -e "  ${BLUE}$0 clean${NC}          - Remove generated sources and build files"
    echo
    echo -e "  ${BLUE}$0 apk${NC}             - Build APK from already generated sources"
    echo -e "  ${BLUE}$0 aab${NC}             - Build AAB from already generated sources"
    echo -e "  ${BLUE}$0 apply_config${NC}    - Apply settings from config file"
	echo -e "  ${BLUE}$0 get_java${NC}        - Download OpenJDK 17 locally"
    echo -e "  ${BLUE}$0 regradle${NC}        - Reinstall gradle. You don't need it"
    exit 1
fi

export ANDROID_HOME=$PWD/cmdline-tools/
migrate_keystore
read_generated_config

# Set Gradle's cache directory to be local to the project
export GRADLE_USER_HOME=$PWD/.gradle-cache

command -v wget >/dev/null 2>&1 || error "wget not found. Please install wget"

# Try to find Java 17
if ! check_and_find_java; then
    warn "Java $MIN_JAVA or newer (a JDK) not found"
    read -p "Would you like to download OpenJDK 17 to ./jvm? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        get_java
        if ! command -v java >/dev/null 2>&1; then
            error "Java installation failed"
        fi
    else
        error "Java $MIN_JAVA or newer (a JDK) is required"
    fi
fi

# Final verification
java_version=$(java_major "$(java -version 2>&1 | head -n 1 | cut -d'"' -f2)")
if [ -z "$java_version" ] || [ "$java_version" -lt "$MIN_JAVA" ]; then
    error "Wrong Java version: ${java_version:-unknown}. Java $MIN_JAVA or newer is required"
fi

command -v adb >/dev/null 2>&1 || warn "adb not found. './make.sh test' will not work"

# The SDK is only needed to build. `keygen` runs keytool from the JDK and nothing else, and the
# README asks for the key before the first build: prompting for 460 MB of tools at that point is
# the worst place for it.
if [ "${1:-}" != "keygen" ] && [ ! -d "$ANDROID_HOME" ]; then
    warn "Android Command Line Tools not found: ./cmdline-tools"
    read -p "Do you want to download them now? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        get_tools
    else
        error "Cannot continue without Android Command Line Tools"
    fi
fi

# `test` is a shell builtin, so the function is named test_apk and mapped here
if [ "${1:-}" = "test" ]; then
    set -- test_apk "${@:2}"
fi

eval $@
