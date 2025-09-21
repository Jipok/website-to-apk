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
    
    # Извлекаем имя переменной и новое значение
    local var_name="${pattern%% =*}"
    local new_value="${pattern#*= }"

    # Проверяем существование переменной
    if ! grep -q "$var_name *= *.*;" "$java_file"; then
        error "Variable '$var_name' not found in MainActivity.java"
    fi

    # Добавляем кавычки если значение не true/false
    if [[ ! "$new_value" =~ ^(true|false)$ ]]; then
        new_value="\"$new_value\""
    fi
    
    local tmp_file=$(mktemp)
    
    awk -v var="$var_name" -v val="$new_value" '
    {
        if (!found && $0 ~ var " *= *.*;" ) {
            # Сохраняем начало строки до =
            match($0, "^.*" var " *=")
            before = substr($0, RSTART, RLENGTH)
            # Заменяем значение
            print before " " val ";"
            # Делаем замену только для первого найденного
            found = 1
        } else {
            print $0
        }
    }' "$java_file" > "$tmp_file"
    
    if ! diff -q "$java_file" "$tmp_file" >/dev/null; then
        mv "$tmp_file" "$java_file"
        log "Updated $var_name to $new_value"
        # Special handling for geolocationEnabled
        if [ "$var_name" = "geolocationEnabled" ]; then
            update_geolocation_permission ${new_value//\"/}
        fi
    else
        rm "$tmp_file"
    fi
}

merge_config_with_default() {
    local default_conf="app/default.conf"
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
    
    while IFS='=' read -r key value || [ -n "$key" ]; do
        # Skip empty lines and comments
        [[ -z "$key" || "$key" =~ ^[[:space:]]*# ]] && continue
        
        # Trim whitespace
        key=$(echo "$key" | xargs)
        value=$(echo "$value" | xargs)
        
        case "$key" in
            "id")
                chid "$value"
                ;;
            "name")
                rename "$value"
                ;;
            "deeplink")
                set_deep_link "$value"
                ;;
            "trustUserCA")
                set_network_security_config "$value"
                ;;
            "icon")
                set_icon "$value"
                ;;
            "scripts")
                set_userscripts $value
                ;;
            *)
                set_var "$key = $value"
                ;;
        esac
    done < <(sed -e '/^[[:space:]]*#/d' -e 's/[[:space:]]\+#.*//' "$config_file")
}


apk() {
    if [ ! -f "app/my-release-key.jks" ]; then
        error "Keystore file not found. Run './make.sh keygen' first"
    fi

    rm -f app/build/outputs/apk/release/app-release.apk

    info "Building APK..."
    try "./gradlew assembleRelease --no-daemon --quiet"

    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        log "APK successfully built and signed"
        try "cp app/build/outputs/apk/release/app-release.apk '$appname.apk'"
        echo -e "${BOLD}----------------"
        echo -e "Final APK copied to: ${GREEN}$appname.apk${NC}"
        echo -e "Size: ${BLUE}$(du -h app/build/outputs/apk/release/app-release.apk | cut -f1)${NC}"
        echo -e "Package: ${BLUE}com.${appname}.webtoapk${NC}"
        echo -e "App name: ${BLUE}$(grep -o 'app_name">[^<]*' app/src/main/res/values/strings.xml | cut -d'>' -f2)${NC}"
        echo -e "URL: ${BLUE}$(grep 'String mainURL' app/src/main/java/com/$appname/webtoapk/*.java | cut -d'"' -f2)${NC}"
        echo -e "${BOLD}----------------${NC}"
    else
        error "Build failed"
    fi
}

test() {
    info "Detected app name: $appname"
    try "adb install app/build/outputs/apk/release/app-release.apk"
    try "adb logcat -c" # clean logs
    try "adb shell am start -n com.$appname.webtoapk/.MainActivity"
    echo "=========================="
    adb logcat | grep -oP "(?<=WebToApk: ).*"

    # adb logcat *:I | grep com.$appname.webtoapk

	# https://stackoverflow.com/questions/29072501/how-to-unlock-android-phone-through-adb
	# adb shell input keyevent 26 #Pressing the lock button
	# sleep 1s
	# adb shell input touchscreen swipe 930 880 930 380 #Swipe UP
}

keygen() {
    if [ -f "app/my-release-key.jks" ]; then
        warn "Keystore already exists"
        read -p "Do you want to replace it? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            info "Cancelled"
            return 1
        fi
        rm app/my-release-key.jks
    fi

    info "Generating keystore..."
    try "keytool -genkey -v -keystore app/my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my -storepass '123456' -keypass '123456' -dname '$INFO'"
    log "Keystore generated successfully"
}

clean() {
    info "Cleaning build files..."
    try rm -rf app/build .gradle
    apply_config app/default.conf
    log "Clean completed"
}


chid() {
    [ -z "$1" ] && error "Please provide an application ID"

    if ! [[ $1 =~ ^[a-zA-Z][a-zA-Z0-9_]*$ ]]; then
        error "Invalid application ID. Use only letters, numbers and underscores, start with a letter"
    fi
   
    try "find . -type f \( -name '*.gradle' -o -name '*.java' -o -name '*.xml' \) -exec \
        sed -i 's/com\.\([a-zA-Z0-9_]*\)\.webtoapk/com.$1.webtoapk/g' {} +"

    if [ "$1" = "$appname" ]; then
        return 0
    fi

    info "Old name: com.$appname.webtoapk"
    info "Renaming to: com.$1.webtoapk"
    
    try "mv app/src/main/java/com/$appname app/src/main/java/com/$1"

    appname=$1
    
    log "Application ID changed successfully"
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
        
        escaped_name=$(echo "$new_name" | sed 's/[\/&]/\\&/g')
        try sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$escaped_name</string>|" "$xml_file"
        
        # Получаем код языка из пути файла
        lang_code=$(echo "$xml_file" | grep -o 'values-[^/]*' | cut -d'-' -f2)
        if [ -z "$lang_code" ]; then
            lang_code="default"
        fi
        
        log "Display name changed to: $new_name (${lang_code})"
    done
}


set_deep_link() {
    local manifest_file="app/src/main/AndroidManifest.xml"
    local host="$@"
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

    # If a host was provided, add the complete intent-filter block back in.
    if [ -n "$host" ]; then
        local new_tmp_file
        new_tmp_file=$(mktemp)
        # Use awk to insert the new block after the main launcher intent-filter.
        awk -v host="$host" '
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
                print "                <data android:host=\""host"\" />"
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
        if [ -z "$host" ]; then
            log "Removing deeplink"
        else
            log "Setting deeplink host to: $host"
        fi
        try mv "$tmp_file" "$manifest_file"
    else
        rm "$tmp_file"
    fi
}

set_network_security_config() {
    local manifest_file="app/src/main/AndroidManifest.xml"
    local config_attr='android:networkSecurityConfig="@xml/network_security_config"'
    local enabled="$1"

    local tmp_file
    tmp_file=$(mktemp)

    if [ "$enabled" = "true" ]; then
        # Add config to the <application> tag if not present
        if ! grep -q "networkSecurityConfig" "$manifest_file"; then
            awk -v attr=" $config_attr" '
            /<\s*application/ { in_app_tag = 1 }
            in_app_tag && />/ {
                sub(/>/, attr ">")
                in_app_tag = 0
            }
            { print }
            ' "$manifest_file" > "$tmp_file"

            log "Enabling user CA support in AndroidManifest.xml"
            try mv "$tmp_file" "$manifest_file"
        else
             rm -f "$tmp_file"
        fi
    else
        # Remove config from the <application> tag if present
        if grep -q "networkSecurityConfig" "$manifest_file"; then
            sed "s# ${config_attr}##" "$manifest_file" > "$tmp_file"
            log "Disabling user CA support in AndroidManifest.xml"
            try mv "$tmp_file" "$manifest_file"
        else
            rm -f "$tmp_file"
        fi
    fi
}


set_icon() {
    local icon_path="$@"
    local default_icon="$PWD/app/example.png"
    local dest_file="app/src/main/res/mipmap/ic_launcher.png"
    
    # If no icon provided, use default
    if [ -z "$icon_path" ]; then
        icon_path="$default_icon"
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

    # Create destination directory if needed
    mkdir -p "$(dirname "$dest_file")"
    
    # Check if icon needs to be updated
    if [ -f "$dest_file" ] && cmp -s "$icon_path" "$dest_file"; then
        return 0
    fi

    if [ -z "$@" ]; then
        warn "Using example.png for icon"
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


update_geolocation_permission() {
    local manifest_file="app/src/main/AndroidManifest.xml"
    local permission='<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />'
    local enabled="$1"

    local tmp_file=$(mktemp)

    if [ "$enabled" = "true" ]; then
        # Add permission if not already present
        if ! grep -q "android.permission.ACCESS_FINE_LOCATION" "$manifest_file"; then
            awk -v perm="$permission" '
            {
                print $0
                if ($0 ~ /<manifest /) {
                    print "    " perm
                }
            }' "$manifest_file" > "$tmp_file"

            log "Added geolocation permission to AndroidManifest.xml"
            try mv "$tmp_file" "$manifest_file"
        fi
    else
        # Remove permission if present
        if grep -q "android.permission.ACCESS_FINE_LOCATION" "$manifest_file"; then
            grep -v "android.permission.ACCESS_FINE_LOCATION" "$manifest_file" > "$tmp_file"

            log "Removed geolocation permission from AndroidManifest.xml"
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
    
    info "Installing necessary SDK components..."
    try "'$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager' --sdk_root=$ANDROID_HOME \
        'platform-tools' \
        'platforms;android-33' \
        'build-tools;33.0.2'" 

    log "Android SDK successfully installed!"
}


regradle() {
    info "Reinstalling Gradle..."
    try rm -rf gradle gradlew .gradle .gradle-cache
    try mkdir -p gradle/wrapper

    cat > gradle/wrapper/gradle-wrapper.properties << EOL
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-7.4-all.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOL

    try wget -q --show-progress https://raw.githubusercontent.com/gradle/gradle/v7.4.0/gradle/wrapper/gradle-wrapper.jar -O gradle/wrapper/gradle-wrapper.jar 
    try wget -q --show-progress https://raw.githubusercontent.com/gradle/gradle/v7.4.0/gradlew -O gradlew
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
    apply_config $@
    apk
}

###############################################################################

ORIGINAL_PWD="$PWD"

# Change directory to the directory where make.sh resides (project root)
try cd "$(dirname "$0")"

export ANDROID_HOME=$PWD/cmdline-tools/
appname=$(grep -Po '(?<=applicationId "com\.)[^.]*' app/build.gradle)

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
    echo -e "  ${BLUE}$0 clean${NC}           - Clean build files, reset settings"
    echo 
    echo -e "  ${BLUE}$0 apk${NC}             - Build APK without apply_config"
    echo -e "  ${BLUE}$0 apply_config${NC}    - Apply settings from config file"
	echo -e "  ${BLUE}$0 get_java${NC}        - Download OpenJDK 17 locally"
    echo -e "  ${BLUE}$0 regradle${NC}        - Reinstall gradle. You don't need it"
    exit 1
fi

eval $@
