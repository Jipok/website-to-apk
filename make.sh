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
    local java_file="app/src/main/java/com/$appname/webtoapk/MainActivity.java"
    [ ! -f "$java_file" ] && error "MainActivity.java not found"

    local pattern="$@"
    [ -z "$pattern" ] && error "Empty pattern. Usage: set_var \"varName = value\""

    # Extract variable name and new value
    local var_name="${pattern%% =*}"
    local new_value="${pattern#*= }"

    # Check if variable exists
    if ! grep -q "$var_name *= *.*;" "$java_file"; then
        error "Variable '$var_name' not found in MainActivity.java"
    fi

    # Remove surrounding quotes (double or single) if provided in the config
    new_value="${new_value%\"}"
    new_value="${new_value#\"}"
    new_value="${new_value%\'}"
    new_value="${new_value#\'}"

    # Add quotes if value is not boolean AND not a number/integer
    if [[ ! "$new_value" =~ ^(true|false)$ ]] && [[ ! "$new_value" =~ ^[0-9]+$ ]]; then
        new_value="\"$new_value\""
    fi

    local tmp_file=$(mktemp)

    awk -v var="$var_name" -v val="$new_value" '
    {
        if (!found && $0 ~ var " *= *.*;" ) {
            # Save start of line up to =
            match($0, "^.*" var " *=")
            before = substr($0, RSTART, RLENGTH)
            # Replace value
            print before " " val ";"
            # Only replace the first occurrence
            found = 1
        } else {
            print $0
        }
    }' "$java_file" > "$tmp_file"

    if ! diff -q "$java_file" "$tmp_file" >/dev/null; then
        mv "$tmp_file" "$java_file"
        log "Updated $var_name to $new_value"

        # Clean value from quotes for logic checks
        local val_clean="${new_value//\"/}"

        # Handle permissions based on specific variable names
        case "$var_name" in
            "geolocationEnabled")
                update_permission "android.permission.ACCESS_FINE_LOCATION" "$val_clean"
                ;;
            "cameraEnabled")
                update_permission "android.permission.CAMERA" "$val_clean"
                ;;
            "microphoneEnabled")
                update_permission "android.permission.RECORD_AUDIO" "$val_clean"
                update_permission "android.permission.MODIFY_AUDIO_SETTINGS" "$val_clean"
                ;;
        esac
    else
        rm "$tmp_file"
    fi
}


# The signing key used to live in app/; move it out of the generated tree for old checkouts
migrate_keystore() {
    if [ -f app/my-release-key.jks ] && [ ! -f my-release-key.jks ]; then
        try mv app/my-release-key.jks my-release-key.jks
        info "Moved the signing key to ./my-release-key.jks"
    fi
}


# Remembers what the tree was generated for, so that `./make.sh apk` works without a config
read_generated_config() {
    local file="config.gradle"
    appname=$(sed -n 's/^ext\.appId *= *//p' "$file" 2>/dev/null | tr -d "'\"" | cut -d. -f2 | head -n1)
    appVersionCode=$(sed -n 's/^ext\.appVersionCode *= *//p' "$file" 2>/dev/null | tr -dc '0-9')
    appVersionName=$(sed -n 's/^ext\.appVersionName *= *//p' "$file" 2>/dev/null | tr -d "'\"")
    appname="${appname:-myexample}"
    appVersionCode="${appVersionCode:-1}"
    appVersionName="${appVersionName:-1.0}"
}


# The application id and the version are read by build.gradle from this generated
# file, so the tracked build.gradle stays the same for every app.
write_config_gradle() {
    local file="config.gradle"
    local content="ext.appId = 'com.$appname.webtoapk'
ext.appVersionCode = $appVersionCode
ext.appVersionName = '$appVersionName'"

    if [ -f "$file" ] && [ "$content" = "$(cat "$file")" ]; then
        return 0
    fi

    printf '%s\n' "$content" > "$file"
}


# src/ is the source of truth; app/src is unpacked from it on every apply_config, so a
# configured tree never accumulates edits and `clean` only has to delete the result.
sync_sources() {
    # Java sources live in the package directory of the current application id
    local pkg="app/src/main/java/com/$appname/webtoapk"

    try rm -rf app/src/main/java app/src/main/res app/src/main/assets app/src/main/AndroidManifest.xml
    try mkdir -p "$pkg" app/src/main/assets

    try cp -a src/build.gradle src/proguard-rules.pro app/
    try cp -a src/java/. "$pkg/"
    try cp -a src/res app/src/main/
    try cp -a src/assets/. app/src/main/assets/
    try cp -a src/AndroidManifest.xml app/src/main/

    # The template declares the default package, the config decides the real one
    if ! grep -q "package com.$appname.webtoapk;" "$pkg/MainActivity.java"; then
        try "sed -i 's/com\\.[a-zA-Z0-9_]*\\.webtoapk/com.$appname.webtoapk/g' $pkg/*.java"
    fi
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

    # The application id is also the Java package, so it has to be known before the
    # template is unpacked into app/src
    local app_id
    app_id=$(sed -n 's/^[[:space:]]*id[[:space:]]*=[[:space:]]*//p' "$config_file" | head -n1 | tr -d '[:space:]')
    if [ -n "$app_id" ]; then
        chid "$app_id"
    else
        sync_sources
    fi

    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Skip empty lines and comments
        [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue

        # Remove spaces, tabs, and invisible unicode characters (zero-width space)
        key=$(echo "$key" | tr -cd '[:alnum:]_')
        # Trim whitespaces
        value=$(echo "$value" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

        case "$key" in
            "id")
                # Already applied above, before the sources were unpacked
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
                set_ca $value
                ;;
            "icon")
                set_icon "$value"
                ;;
            "scripts")
                set_userscripts $value
                ;;
            "site")
                set_site "$value"
                ;;
            *)
                set_var "$key = $value"
                ;;
        esac
    done < <(sed -e '/^[[:space:]]*#/d' -e 's/[[:space:]]\+#.*//' "$config_file")

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
    local destination="$appname.$ext"
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

    if [ "$1" != "$appname" ]; then
        info "Old name: com.$appname.webtoapk"
        info "Renaming to: com.$1.webtoapk"

        appname="$1"
        write_config_gradle

        log "Application ID changed successfully"
    fi

    # The template is unpacked into the package directory of the (possibly new) id
    sync_sources
}


# XML text escaping, for values that end up inside strings.xml
xml_escape() {
    sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

rename() {
    local new_name="$*"

    if [ -z "$new_name" ]; then
        error "Please provide a display name\nUsage: $0 display_name \"My App Name\""
    fi

    # Найти все файлы strings.xml в различных языковых директориях
    find app/src/main/res/values* -name "strings.xml" | while read xml_file; do
        current_name=$(grep -o 'app_name">[^<]*' "$xml_file" | cut -d'>' -f2)
        if [ "$current_name" = "$new_name" ]; then
            continue
        fi

        # The name lands in XML text, so escape it for XML first and for the sed replacement second
        escaped_name=$(printf '%s' "$new_name" | xml_escape | sed 's/[\/&]/\\&/g')
        try sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$escaped_name</string>|" "$xml_file"

        # Получаем код языка из пути файла
        lang_code=$(echo "$xml_file" | grep -o 'values-[^/]*' | cut -d'-' -f2)
        if [ -z "$lang_code" ]; then
            lang_code="default"
        fi

        log "Display name changed to: $new_name (${lang_code})"
    done
}


# Set versionCode / versionName in the generated app/config.gradle
set_version() {
    local key="$1" value="$2"

    case "$key" in
        "versionCode")
            [[ "$value" =~ ^[0-9]+$ ]] || error "versionCode must be an integer, got: '$value'"
            appVersionCode="$value"
            write_config_gradle
            log "Updated versionCode to $value"
            ;;
        "versionName")
            # Remove surrounding quotes (double or single) if provided in the config
            value="${value%\"}"; value="${value#\"}"
            value="${value%\'}"; value="${value#\'}"
            appVersionName="$value"
            write_config_gradle
            log "Updated versionName to '$value'"
            ;;
        *)
            error "Unknown version field: $key"
            ;;
    esac
}


set_deep_link() {
    local manifest_file="app/src/main/AndroidManifest.xml"
    local hosts_input="$@"
    local tmp_file
    tmp_file=$(mktemp)

    # First, create a version of the manifest without any VIEW/BROWSER intent-filter.
    # This prepares a clean slate.
    awk '
        # Find any intent-filter block
        /<intent-filter>/, /<\/intent-filter>/ {
            # Buffer the lines of the block
            buffer = buffer $0 ORS
            # When the block ends...
            if (/<\/intent-filter>/) {
                # ...check if it is NOT the browser/deeplink one (by looking for action.VIEW).
                if (buffer !~ /android.intent.action.VIEW/) {
                    # If it is the LAUNCHER filter, print it.
                    printf "%s", buffer
                }
                # Reset buffer for the next potential block.
                buffer = ""
            }
            # Do not print the line yet.
            next
        }
        # Print all other lines that are not in a VIEW intent-filter block.
        { print }
    ' "$manifest_file" > "$tmp_file"

    # If hosts were provided, add the complete intent-filter block back in.
    if [ -n "$hosts_input" ]; then
        local new_tmp_file
        new_tmp_file=$(mktemp)
        # Use awk to insert the new block after the main launcher intent-filter.
        awk -v hosts="$hosts_input" '
            # After the first (launcher) intent-filter is closed...
            /<\/intent-filter>/ && !inserted {
                # ...print the closing tag first.
                print
                # Then print the new block for our deeplink.
                print "            <intent-filter>"
                print "                <action android:name=\"android.intent.action.VIEW\" />"
                print "                <category android:name=\"android.intent.category.DEFAULT\" />"
                print "                <category android:name=\"android.intent.category.BROWSABLE\" />"
                print "                <data android:scheme=\"http\" />"
                print "                <data android:scheme=\"https\" />"

                # Split hosts by space and iterate to create data tags for each host
                n = split(hosts, h_array, " ")
                for (i = 1; i <= n; i++) {
                     if (h_array[i] != "") {
                        print "                <data android:host=\"" h_array[i] "\" />"
                     }
                }

                print "            </intent-filter>"
                # Set a flag to ensure we only do this once.
                inserted=1
                next
            }
            # Print all other lines as usual.
            { print }
        ' "$tmp_file" > "$new_tmp_file"

        # The final content is now in new_tmp_file.
        mv "$new_tmp_file" "$tmp_file"
    fi

    # Apply changes only if the file is actually different.
    if ! diff -q "$manifest_file" "$tmp_file" >/dev/null; then
        if [ -z "$hosts_input" ]; then
            log "Removing deeplink"
        else
            log "Setting deeplinks for: $hosts_input"
        fi
        try mv "$tmp_file" "$manifest_file"
    else
        rm "$tmp_file"
    fi
}

# trustUserCA controls whether the device's user-installed CAs are trusted.
# android:networkSecurityConfig is hardcoded in AndroidManifest.xml, so CAs bundled into
# res/raw by set_ca() (referenced as <certificates src="@raw/name" />) work out of the box.
set_network_security_config() {
    local nsc_file="app/src/main/res/xml/network_security_config.xml"
    local enabled="$1"
    local tmp_file
    tmp_file=$(mktemp)

    if [ "$enabled" = "true" ]; then
        if grep -qE '^[[:space:]]*<certificates src="user"' "$nsc_file"; then
            rm -f "$tmp_file"
            return 0
        fi
        sed 's#^\([[:space:]]*\)<certificates src="system" />#\1<certificates src="system" />\n\1<certificates src="user" />#' "$nsc_file" > "$tmp_file"
        log "Trusting user-installed CAs"
    else
        if ! grep -qE '^[[:space:]]*<certificates src="user"' "$nsc_file"; then
            rm -f "$tmp_file"
            return 0
        fi
        grep -vE '^[[:space:]]*<certificates src="user"' "$nsc_file" > "$tmp_file"
        log "Not trusting user-installed CAs"
    fi

    try mv "$tmp_file" "$nsc_file"
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

    for src in $@; do
        # Relative paths are resolved against the config file location
        if [ -n "${CONFIG_DIR:-}" ] && [[ "$src" != /* ]]; then
            src="$CONFIG_DIR/$src"
        fi
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
    done

    if ! diff -q "$nsc_file" "$tmp_file" >/dev/null; then
        try mv "$tmp_file" "$nsc_file"
    else
        rm -f "$tmp_file"
    fi
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

    # Check if icon needs to be updated
    if [ -f "$dest_file" ] && cmp -s "$icon_path" "$dest_file"; then
        return 0
    fi

    # Copy icon
    try "cp \"$icon_path\" \"$dest_file\""
    log "Icon updated successfully"
}


set_userscripts() {
    local scripts_dir="app/src/main/assets/userscripts"

    # Create destination directory if it doesn't exist
    mkdir -p "$scripts_dir"

    # If no arguments provided, clean destination and exit
    if [ $# -eq 0 ] || [ -z "$1" ]; then
        if [ -n "$(ls -A $scripts_dir 2>/dev/null)" ]; then
            find "$scripts_dir" -mindepth 1 -delete
            log "Userscripts directory cleared"
        fi
        return 0
    fi

    # Track changes for reporting
    local added=()
    local updated=()
    local removed=()

    # Get a list of currently existing script basenames in the destination
    local existing_scripts=()
    # Use find to properly handle filenames with spaces
    while IFS= read -r file; do
        existing_scripts+=("$(basename "$file")")
    done < <(find "$scripts_dir" -mindepth 1 -type f)

    # Build a list of all source files from arguments
    local source_files=()
    for pattern in "$@"; do
        # If CONFIG_DIR is defined and pattern is relative, prepend it
        if [ -n "${CONFIG_DIR:-}" ] && [[ "$pattern" != /* ]]; then
            pattern="$CONFIG_DIR/$pattern"
        fi

        # Use a nullglob to avoid errors if a pattern doesn't match any files
        shopt -s nullglob
        for file in $pattern; do
            if [ -f "$file" ]; then
                source_files+=("$file")
            fi
        done
        shopt -u nullglob # Revert glob option
    done

    # Process source files: copy new/updated files and track which scripts should exist
    local current_scripts=()
    for src_file in "${source_files[@]}"; do
        local base_name
        base_name=$(basename "$src_file")
        local dest_file="$scripts_dir/$base_name"

        # Add basename to a list of scripts that are currently in config
        current_scripts+=("$base_name")

        if [ ! -f "$dest_file" ]; then
            # New file
            cp "$src_file" "$dest_file"
            added+=("$base_name")
        elif ! cmp -s "$src_file" "$dest_file"; then
            # Changed file
            cp "$src_file" "$dest_file"
            updated+=("$base_name")
        fi
    done

    # Determine which scripts to remove by comparing old and new lists
    for script in "${existing_scripts[@]}"; do
        is_current=false
        for current in "${current_scripts[@]}"; do
            if [[ "$script" == "$current" ]]; then
                is_current=true
                break
            fi
        done
        # If a script existed before but is not in the new list, remove it
        if ! $is_current; then
            rm -f "$scripts_dir/$script"
            removed+=("$script")
        fi
    done

    # Report all changes
    if [ ${#removed[@]} -gt 0 ]; then
        for script in "${removed[@]}"; do
            log "Removed userscript: $script"
        done
    fi

    if [ ${#added[@]} -gt 0 ]; then
        for script in "${added[@]}"; do
            log "Added userscript: $script"
        done
    fi

    if [ ${#updated[@]} -gt 0 ]; then
        for script in "${updated[@]}"; do
            log "Updated userscript: $script"
        done
    fi

    # If no changes were made, stay silent
    if [ ${#removed[@]} -eq 0 ] && [ ${#added[@]} -eq 0 ] && [ ${#updated[@]} -eq 0 ]; then
        return 0
    fi
}


set_site() {
    local src="$1"
    local dest="app/src/main/assets/site"

    # Empty value: drop a previously bundled site
    if [ -z "$src" ]; then
        if [ -d "$dest" ]; then
            rm -rf "$dest"
            log "Bundled site removed"
        fi
        return 0
    fi

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
    local tmp_file=$(mktemp)

    if [ "$enabled" = "true" ]; then
        # Add permission if not already present
        if ! grep -q "$permission_name" "$manifest_file"; then
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
        else
            rm "$tmp_file"
        fi
    else
        # Remove permission if present
        if grep -q "$permission_name" "$manifest_file"; then
            # Filter out lines containing the permission name
            grep -v "$permission_name" "$manifest_file" > "$tmp_file"

            log "Removed permission: $permission_name"
            try mv "$tmp_file" "$manifest_file"
        else
            rm "$tmp_file"
        fi
    fi
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


# Check Java version and update JAVA_HOME if needed
check_and_find_java() {
    # First check existing JAVA_HOME
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        version=$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
        if [ "$version" = "17" ]; then
            info "Using system JAVA_HOME: $JAVA_HOME"
            export PATH="$JAVA_HOME/bin:$PATH"
            return 0
        else
            warn "Current JAVA_HOME points to wrong version: $version"
        fi
    fi

    # Then check local installation
    if [ -d "$PWD/jvm/jdk-17.0.2" ]; then
        info "Using local Java installation"
        export JAVA_HOME="$PWD/jvm/jdk-17.0.2"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi

    # Finally check /usr/lib/jvm
    if [ -d "/usr/lib/jvm" ]; then
        while IFS= read -r java_path; do
            if [ -x "$java_path/bin/java" ]; then
                version=$("$java_path/bin/java" -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
                if [ "$version" = "17" ]; then
                    info "Found system Java 17: $java_path"
                    export JAVA_HOME="$java_path"
                    export PATH="$JAVA_HOME/bin:$PATH"
                    return 0
                fi
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

export ANDROID_HOME=$PWD/cmdline-tools/
migrate_keystore
read_generated_config

# Set Gradle's cache directory to be local to the project
export GRADLE_USER_HOME=$PWD/.gradle-cache

command -v wget >/dev/null 2>&1 || error "wget not found. Please install wget"

# Try to find Java 17
if ! check_and_find_java; then
    warn "Java 17 not found"
    read -p "Would you like to download OpenJDK 17 to ./jvm? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        get_java
        if ! command -v java >/dev/null 2>&1; then
            error "Java installation failed"
        fi
    else
        error "Java 17 is required"
    fi
fi

# Final verification
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$java_version" != "17" ]; then
    error "Wrong Java version: $java_version. Java 17 is required"
fi

command -v adb >/dev/null 2>&1 || warn "adb not found. './make.sh try' will not work"

if [ ! -d "$ANDROID_HOME" ]; then
    warn "Android Command Line Tools not found: ./cmdline-tools"
    read -p "Do you want to download them now? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        get_tools
    else
        error "Cannot continue without Android Command Line Tools"
    fi
fi

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

eval $@
