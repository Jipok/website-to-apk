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

2. Set the application ID (internal name):
```bash
./make.sh chid myapp
# This will set package name to com.myapp.webtoapk
```

3. Set the website URL:
```bash
./make.sh url https://example.com
```

4. Set the display name:
```bash
./make.sh rename "My App Name"
```

5. Generate signing key:
```bash
./make.sh keygen
```

6. Build the APK:
```bash
./make.sh apk
```

The final APK will be created in the current directory.

## Available Commands

- `./make.sh chid NAME` - Set application ID (package name)
- `./make.sh rename NAME` - Set display name of the app
- `./make.sh url URL` - Set website URL
- `./make.sh keygen` - Generate signing key
- `./make.sh apk` - Build signed APK
- `./make.sh external_links` - Configure external links handling (on/off)
- `./make.sh double_back` - Configure double-back-to-exit behavior (on/off)
- `./make.sh try` - Install and test APK on connected device
- `./make.sh clean` - Clean build files
- `./make.sh get_tools` - Download Android command-line tools
- `./make.sh get_java` - Download OpenJDK 11 locally

## Configuration Options

### Custom Icon
To change the app icon, replace the following files:
- `app/src/main/res/drawable/icon.png`
- `app/src/main/res/drawable/icon_round.png`

### External Links
Control how external links are handled:
```bash
./make.sh external_links on   # Opens links in system browser
./make.sh external_links off  # Opens links in WebView
```

### Back Button Behavior
Configure the back button behavior:
```bash
./make.sh double_back on   # Requires double-press to exit
./make.sh double_back off  # Exits immediately when can't go back
```

### Clean Build
If you encounter build issues, try cleaning:
```bash
./make.sh clean
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
