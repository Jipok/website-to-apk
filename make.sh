#!/usr/bin/env bash
set -e

export ANDROID_HOME=$PWD/cmdline-tools/
appname=$(grep -Po '(?<=applicationId "com\.)[^.]*' app/build.gradle)

apk() {
	./gradlew build
}

try() {
	echo "Detected app name: $appname"
	adb install app/build//outputs//apk//debug//app-debug.apk
	adb shell am start -n com.$appname.webtoapk/.MainActivity
	# https://stackoverflow.com/questions/29072501/how-to-unlock-android-phone-through-adb
	adb shell input keyevent 26 #Pressing the lock button
	sleep 1s
	adb shell input touchscreen swipe 930 880 930 380 #Swipe UP
}

keygen() {
	keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my
}

sign() {
	ver=$(ls ${ANDROID_HOME}/build-tools/ | head -n1)
	echo "Detected build-tools: $ver"
	echo "Detected app name: $appname"
	echo "Zipalign..."
	$ANDROID_HOME/build-tools/$ver/zipalign -p 4 app/build/outputs/apk/release/app-release-unsigned.apk $appname.apk
	echo "Sign..."
	$ANDROID_HOME/build-tools/$ver/apksigner sign --ks-key-alias my --ks ./my-release-key.jks $appname.apk
	echo "Result: $appname.apk"
}

clean() {
	rm -rf app/build .gradle
}

rename() {
	echo "Old name: com.$appname.webtoapk"
	echo "Renames to: com.$1.webtoapk"
	mv app/src/main/java/com/$appname app/src/main/java/com/$1
	find . -type f \( -name "*.gradle" -o -name "*.java" -o -name "*.xml" \) -exec \
		sed -i "s/com\.\([a-zA-Z0-9_]*\)\.webtoapk/com.$1.webtoapk/g" {} +
}

url() {
	sed -i "s|String mainURL = \"[^\"]*\";|String mainURL = \"${1//\//\\/}\";|" app/src/main/java/com/$appname/webtoapk/*.java
}

eval $@