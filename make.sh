#!/usr/bin/env bash
set -e

# Color definitions
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color
readonly BOLD='\033[1m'

# Info for keystore generation
INFO="CN=Developer, OU=Organization, O=Company, L=City, S=State, C=US"

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

# Progress bar function
show_progress() {
    local pid=$1
    local delay=0.1
    local spinstr='|/-\'
    while [ "$(ps a | awk '{print $1}' | grep $pid)" ]; do
        local temp=${spinstr#?}
        printf " [%c]  " "$spinstr"
        local spinstr=$temp${spinstr%"$temp"}
        sleep $delay
        printf "\b\b\b\b\b\b"
    done
    printf "    \b\b\b\b"
}

get_tools() {
    log_info "Downloading Android Command Line Tools..."
    
    case "$(uname -s)" in
        Linux*)     os_type="linux";;
        Darwin*)    os_type="mac";;
        *)         log_error "Unsupported OS";;
    esac
    
    tmp_dir=$(mktemp -d)
    cd "$tmp_dir"
    
    wget -q --show-progress "https://dl.google.com/android/repository/commandlinetools-${os_type}-11076708_latest.zip" -O cmdline-tools.zip || log_error "Failed to download command line tools"
    
    log_info "Extracting tools..."
    unzip -q cmdline-tools.zip || log_error "Failed to extract tools"
    mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
    mv cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/" || log_error "Failed to move tools"
    
    cd "$OLDPWD"
    rm -rf "$tmp_dir"

    log_info "Accepting licenses..."
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root=$ANDROID_HOME --licenses > /dev/null 2>&1
    
    log_info "Installing necessary SDK components..."
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root=$ANDROID_HOME \
        "platform-tools" \
        "platforms;android-33" \
        "build-tools;33.0.2" > /dev/null 2>&1 &
    show_progress $!
    
    log_success "Android SDK successfully installed!"
}

apk() {
    if [ ! -f "app/my-release-key.jks" ]; then
        log_error "Keystore file not found. Run './make.sh keygen' first"
    fi

	rm -f app/build/outputs/apk/release/app-release.apk

    log_info "Building APK..."
    ./gradlew assembleRelease --no-daemon --quiet &
    show_progress $!

    if [ -f "app/build/outputs/apk/release/app-release.apk" ]; then
        log_success "APK successfully built and signed"
        cp app/build/outputs/apk/release/app-release.apk "$appname.apk"
        echo -e "${BOLD}----------------"
        echo -e "Final APK copied to: ${GREEN}$appname.apk${NC}"
        echo -e "Size: ${BLUE}$(du -h app/build/outputs/apk/release/app-release.apk | cut -f1)${NC}"
        echo -e "Package: ${BLUE}com.${appname}.webtoapk${NC}"
        echo -e "App name: ${BLUE}$(grep -o 'app_name">[^<]*' app/src/main/res/values/strings.xml | cut -d'>' -f2)${NC}"
        echo -e "URL: ${BLUE}$(grep 'String mainURL' app/src/main/java/com/$appname/webtoapk/*.java | cut -d'"' -f2)${NC}"
        echo -e "${BOLD}----------------${NC}"
    else
        log_error "Build failed"
    fi
}

try() {
    log_info "Detected app name: $appname"
    adb install app/build/outputs/apk/release/app-release.apk || log_error "Failed to install APK"
    adb shell am start -n com.$appname.webtoapk/.MainActivity || log_error "Failed to start app"

	# https://stackoverflow.com/questions/29072501/how-to-unlock-android-phone-through-adb
	# adb shell input keyevent 26 #Pressing the lock button
	# sleep 1s
	# adb shell input touchscreen swipe 930 880 930 380 #Swipe UP
}

keygen() {
    if [ -f "app/my-release-key.jks" ]; then
        log_warning "Keystore already exists"
        read -p "Do you want to replace it? (y/N) " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            log_info "Cancelled"
            return 1
        fi
		rm app/my-release-key.jks
    fi

    log_info "Generating keystore..."
    keytool -genkey -v -keystore app/my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my -storepass "123456" -keypass "123456" -dname "$INFO" &> /dev/null \
		|| log_error "Failed to generate keystore"
    log_success "Keystore generated successfully"
}

clean() {
    log_info "Cleaning build files..."
    rm -rf app/build .gradle
    log_success "Clean completed"
}

chid() {
    [ -z "$1" ] && log_error "Please provide an application ID"
    
    log_info "Old name: com.$appname.webtoapk"
    log_info "Renaming to: com.$1.webtoapk"
    
    find . -type f \( -name "*.gradle" -o -name "*.java" -o -name "*.xml" \) -exec \
        sed -i "s/com\.\([a-zA-Z0-9_]*\)\.webtoapk/com.$1.webtoapk/g" {} + || log_error "Failed to update files"
    mv app/src/main/java/com/$appname app/src/main/java/com/$1 || log_error "Failed to rename directory"
    
    log_success "Application ID changed successfully"
}

rename() {
    local new_name="$*"
    
    if [ -z "$new_name" ]; then
        log_error "Please provide a display name\nUsage: $0 display_name \"My App Name\""
    fi
    
    xml_file="app/src/main/res/values/strings.xml"
    [ ! -f "$xml_file" ] && log_error "strings.xml not found"
    
    escaped_name=$(echo "$new_name" | sed 's/[\/&]/\\&/g')
    sed -i "s|<string name=\"app_name\">[^<]*</string>|<string name=\"app_name\">$escaped_name</string>|" "$xml_file" || log_error "Failed to update app name"
    
    log_success "Display name changed to: $new_name"
}

url() {
    [ -z "$1" ] && log_error "Please provide a URL"
    sed -i "s|String mainURL = \"[^\"]*\";|String mainURL = \"${1//\//\\/}\";|" app/src/main/java/com/$appname/webtoapk/*.java
    log_success "URL updated successfully"
}

external_links() {
    local state="$1"
    if [ -z "$state" ]; then
        echo -e "\n${BOLD}External links handling:${NC}"
        echo -e "  This setting controls how links to other websites are opened:"
        echo -e "  ${BLUE}ON${NC}  - External links open in system browser"
        echo -e "  ${BLUE}OFF${NC} - External links open in app's WebView"
        echo -e "\n${BOLD}Current state:${NC} ${BLUE}$(grep "openExternalLinksInBrowser = " app/src/main/java/com/$appname/webtoapk/*.java | grep -o "true\|false")${NC}"
        echo -e "\n${BOLD}Usage:${NC}"
        echo -e "  ${BLUE}$0 external_links on${NC}"
        echo -e "  ${BLUE}$0 external_links off${NC}"
        exit 1
    fi
    
    if [ "$state" = "on" ]; then
        sed -i 's/boolean openExternalLinksInBrowser = false;/boolean openExternalLinksInBrowser = true;/' app/src/main/java/com/$appname/webtoapk/*.java
        log_success "External links will open in system browser"
    elif [ "$state" = "off" ]; then
        sed -i 's/boolean openExternalLinksInBrowser = true;/boolean openExternalLinksInBrowser = false;/' app/src/main/java/com/$appname/webtoapk/*.java
        log_success "External links will open in WebView"
    else
        log_error "Invalid option. Use 'on' or 'off'"
    fi
}

double_back() {
    local state="$1"
    if [ -z "$state" ]; then
        echo -e "\n${BOLD}Double back to exit:${NC}"
        echo -e "  This setting controls how the back button behaves:"
        echo -e "  ${BLUE}ON${NC}  - Requires pressing back twice to exit"
        echo -e "  ${BLUE}OFF${NC} - Exits immediately on back press when can't go back"
        echo -e "\n${BOLD}Current state:${NC} ${BLUE}$(grep "requireDoubleBackToExit = " app/src/main/java/com/$appname/webtoapk/*.java | grep -o "true\|false")${NC}"
        echo -e "\n${BOLD}Usage:${NC}"
        echo -e "  ${BLUE}$0 double_back on${NC}"
        echo -e "  ${BLUE}$0 double_back off${NC}"
        exit 1
    fi
    
    if [ "$state" = "on" ]; then
        sed -i 's/boolean requireDoubleBackToExit = false;/boolean requireDoubleBackToExit = true;/' app/src/main/java/com/$appname/webtoapk/*.java
        log_success "Double back to exit enabled"
    elif [ "$state" = "off" ]; then
        sed -i 's/boolean requireDoubleBackToExit = true;/boolean requireDoubleBackToExit = false;/' app/src/main/java/com/$appname/webtoapk/*.java
        log_success "Double back to exit disabled"
    else
        log_error "Invalid option. Use 'on' or 'off'"
    fi
}

reinstall_gradle() {
    log_info "Reinstalling Gradle..."
    rm -rf gradle gradlew .gradle
    mkdir -p gradle/wrapper

    cat > gradle/wrapper/gradle-wrapper.properties << EOL
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-7.4-all.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOL

    wget -q --show-progress https://raw.githubusercontent.com/gradle/gradle/v7.4.0/gradle/wrapper/gradle-wrapper.jar \
        -O gradle/wrapper/gradle-wrapper.jar || log_error "Failed to download gradle-wrapper.jar"
    wget -q --show-progress https://raw.githubusercontent.com/gradle/gradle/v7.4.0/gradlew -O gradlew || log_error "Failed to download gradlew"
    chmod +x gradlew
    
    log_success "Gradle reinstalled successfully"
}

get_java() {
	local install_dir="$PWD/jvm"
	local jdk_version="11.0.2"
    local jdk_hash="99be79935354f5c0df1ad293620ea36d13f48ec3ea870c838f20c504c9668b57"
    local jdk_url="https://download.java.net/java/GA/jdk11/9/GPL/openjdk-${jdk_version}_linux-x64_bin.tar.gz"

	if [ -d "$install_dir/jdk-${jdk_version}" ]; then
        log_info "OpenJDK ${jdk_version} already downloaded"
        export JAVA_HOME="$install_dir/jdk-${jdk_version}"
        export PATH="$JAVA_HOME/bin:$PATH"
        return 0
    fi

    local tmp_dir=$(mktemp -d)
    cd "$tmp_dir"
    
    log_info "Downloading OpenJDK ${jdk_version}..."
    wget -q --show-progress "$jdk_url" -O openjdk.tar.gz || log_error "Failed to download OpenJDK"
    
    log_info "Verifying checksum..."
    echo "${jdk_hash} openjdk.tar.gz" | sha256sum -c - || log_error "Checksum verification failed"
    
    log_info "Unpacking to ${install_dir}..."
    mkdir -p "$install_dir"
    tar xf openjdk.tar.gz || log_error "Failed to extract OpenJDK"
    mv "jdk-${jdk_version}" "$install_dir/"
    
    # Clean up
    cd "$OLDPWD"
    rm -rf "$tmp_dir"

	export JAVA_HOME="$install_dir/jdk-${jdk_version}"
    export PATH="$JAVA_HOME/bin:$PATH"
    
    log_success "OpenJDK ${jdk_version} downloaded successfully!"
}

###############################################################################

export ANDROID_HOME=$PWD/cmdline-tools/
appname=$(grep -Po '(?<=applicationId "com\.)[^.]*' app/build.gradle)

command -v wget >/dev/null 2>&1 || log_error "wget not found. Please install wget"

# First check if we have local JDK installation
if [ -d "$PWD/jvm/jdk-11.0.2" ]; then
	log_info "Using local Java installation"
	export JAVA_HOME="$PWD/jvm/jdk-11.0.2"
	export PATH="$JAVA_HOME/bin:$PATH"
fi

# Then check system Java
if ! command -v java >/dev/null 2>&1; then
	log_warning "Java not found"
	read -p "Would you like to download OpenJDK 11 to ./jvm? (y/N) " -n 1 -r
	echo
	if [[ $REPLY =~ ^[Yy]$ ]]; then
		get_java
		# Re-check java after installation
		if ! command -v java >/dev/null 2>&1; then
			log_error "Java installation failed"
		fi
	else
		log_error "Java is required to continue"
	fi
fi

# Verify Java version
java_version=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$java_version" != "11" ]; then
    log_warning "Java 11 is REQUIRED. Current version: $java_version"
    echo -e "You can:"
    echo -e "1. Run ${BLUE}'./make.sh get_java'${NC} to install Java 11 locally"
    echo -e "2. Install system-wide Java 11"
    echo -e "3. Continue anyway (not recommended)"
    read -p "Continue anyway? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        log_error "Java 11 is required"
    fi
fi

command -v adb >/dev/null 2>&1 || log_warning "adb not found. './make.sh try' will not work"

if [ ! -d "$ANDROID_HOME" ]; then
    log_warning "Android Command Line Tools not found: ./cmdline-tools"
    read -p "Do you want to download them now? (y/n) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        get_tools
    else
        log_error "Cannot continue without Android Command Line Tools"
    fi
fi

if [ $# -eq 0 ]; then
    echo -e "${BOLD}Usage:${NC}"
    echo -e "  ${BLUE}$0 chid NAME${NC}       - Set application ID name"
    echo -e "  ${BLUE}$0 rename NAME${NC}     - Set display name of the app"
    echo -e "  ${BLUE}$0 url URL${NC}         - Set website URL"
    echo -e "  ${BLUE}$0 keygen${NC}          - Generate signing key"
    echo -e "  ${BLUE}$0 apk${NC}             - Build APK"
	echo -e "  ${BLUE}$0 external_links${NC}  - Toggle external links handling"
	echo -e "  ${BLUE}$0 double_back${NC}     - Toggle double back to exit"
    echo -e "  ${BLUE}$0 try${NC}             - Install and test APK"
    echo -e "  ${BLUE}$0 clean${NC}           - Clean build files"
    echo -e "  ${BLUE}$0 get_tools${NC}       - Download ./cmdline-tools"
	echo -e "  ${BLUE}$0 get_java${NC}        - Download OpenJDK 11 locally"
    exit 1
fi

eval $@
