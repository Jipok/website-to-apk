# Website-to-APK Converter

A simple tool to convert any website into an Android APK without requiring Android Studio or Java programming knowledge. The app acts as a WebView wrapper around your chosen website.

## Features

- Simple command-line interface with colorful output
- Automatic Java 11 installation option
- Automated Android SDK tools installation
- APK signing and building process

## Quick Start

1. Clone this repository:
```bash
git clone https://github.com/Jipok/website-to-apk
cd website-to-apk
```

2. Create a configuration file `webapk.conf`:
```ini
id = myapp                          # Application ID (will be com.myapp.webtoapk)
name = My App Name                  # Display name of the app
mainURL = https://example.com       # Target website URL

allowSubdomains = true              # Allow navigation between example.com and sub.example.com
requireDoubleBackToExit = true      # Require double back press to exit app

enableExternalLinks = true          # Allow/block external links
openExternalLinksInBrowser = true   # If allowed: open external links in browser or WebView
confirmOpenInBrowser = true         # Show confirmation before opening external browser
```

3. Generate signing key (only needed once, keep the generated file safe):
```bash
./make.sh keygen
```

4. Apply configuration and build:
```bash
./make.sh build
```

The final APK will be created in the current directory.

## Available Commands

- `./make.sh build` - Apply configuration and build
- `./make.sh keygen` - Generate signing key
- `./make.sh test` - Install and test APK on connected device
- `./make.sh clean` - Clean build files
- `./make.sh apply_config` - Apply settings from configuration file
- `./make.sh apk` - Build signed APK
- `./make.sh get_tools` - Download Android command-line tools
- `./make.sh get_java` - Download OpenJDK 17 locally


## Custom Icon
To change the app icon replace file:
- `app/src/main/res/mipmap/ic_launcher.png`

## Additional WebView Options
The following advanced options can also be configured:
```toml
cookies = "key1=value1; key2=value2"  # Cookies for mainURL
JSEnabled = true                      # Enable JavaScript execution
JSCanOpenWindowsAutomatically = true  # Allow JS to open new windows/popups

DomStorageEnabled = true              # Enable HTML5 DOM storage
DatabaseEnabled = true                # Enable HTML5 Web SQL Database
SavePassword = true                   # Allow saving passwords in WebView

MediaPlaybackRequiresUserGesture = false # Disable autoplay of media files
```

## Technical Details

- Target Android API: 33 (Android 13)
- Minimum Android API: 21 (Android 5.0)
- Build tools version: 33.0.2
- Gradle version: 7.4
- Required Java version: 11

## Notes

- All app data is stored in the app's private directory
- The keystore password is set to "123456" by default
- Internet permission is required and automatically included
- Based on the original work from: https://github.com/successtar/web-to-app  
