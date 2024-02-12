## Website-to-apk

Here is an easy and quick way to make an Android “app” for any website. You don't need Android Studio or Java programming skills. The only file that will have to be edited is the xml with the display name of your application.
The application is just a wrapper around a WebView.

#### Instruction:
1) Go to https://developer.android.com/studio#command-line-tools-only and download last cmdline tools. Ex:
```
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip
```
2) Set correct env:

`export ANDROID_HOME=$PWD/cmdline-tools/`
3) For SDK need JDK17. Ex:
```
export JAVA_HOME=/usr/lib/jvm/openjdk17/
$JAVA_HOME/bin/java --version
```
4) Accept licenses for SDK:
`$ANDROID_HOME/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses`
5) Choose internal name. Should be non-space latin seq. Ex:
```
./make.sh rename myexample
# Will be: com.myexample.webtoapk
```

6) Set main URL for app:
`./make.sh url https://github.com/Jipok`
7) Edit `app/src/main/res/values/strings.xml` and set good displayed app name
8) You also can change icon, look at: `app/src/main/res/drawable`
9) Now yo need JDK11 for gradle, check `java --version`
10) Run `./make.sh apk`
It can take time for first run and fail. Then just try again
11) You can try debug version: 
```
adb install app/build/outputs/apk/debug/app-debug.apk
# Or
./make.sh try
```
12) Create keys(ONCE) for signing apk:
`./make.sh keygen`
13) Sign:
`./make.sh sign`
14) Share your app

Thank to: https://github.com/successtar/web-to-app