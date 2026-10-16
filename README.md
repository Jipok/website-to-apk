# Website-to-APK

A simple tool to convert any website into an Android APK without requiring Android Studio or Java programming knowledge. The app acts as a WebView wrapper around your chosen website.

## Features

- Simple command-line interface with colorful output
- Automatic Java 17 downloading option
- Automated Android SDK tools installation
- APK signing and building process
- Userscripts support

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
icon = my-icon.png                  # Path to your app icon (PNG format), empty = default icon

allowSubdomains = true              # Allow navigation between example.com and sub.example.com
requireDoubleBackToExit = true      # Require double back press to exit app

enableExternalLinks = true          # Allow external links
openExternalLinksInBrowser = true   # If allowed: open external links in browser or WebView
confirmOpenInBrowser = true         # Show confirmation before opening external browser

allowOpenMobileApp = false          # Block external app links/schemes
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

### YouTube Example

Pre-configured configuration files for YouTube are available in the <code>confs/youtube</code> directory. To build a YouTube APK, simply execute:

```bash
./make.sh build confs/youtube/webapk.conf
```

## Available Commands

- `./make.sh build [config]` - Apply configuration and build
- `./make.sh keygen` - Generate signing key
- `./make.sh test` - Install and test APK on connected device
- `./make.sh clean` - Delete generated sources and build files
-
- `./make.sh apk` - Build APK from already generated sources
- `./make.sh aab` - Build AAB from already generated sources
- `./make.sh apply_config` - Apply settings from configuration file
- `./make.sh get_java` - Download OpenJDK 17 locally

## Userscripts Support

The app supports userscripts (similar to Tampermonkey/Violentmonkey scripts) through the `scripts` configuration option:

```ini
scripts = scripts/*.js             # Load all .js files from scripts directory
# OR
scripts = site-*.js                # Load all files matching pattern
# OR
scripts = script1*.js script20.js  # Load specific script files
```

### How Userscripts Work

- Scripts can use Tampermonkey/Violentmonkey/etc [`@match`](https://violentmonkey.github.io/api/metadata-block/#match--exclude-match) and [`@run-at`](https://violentmonkey.github.io/api/metadata-block/#run-at) directives, other ignored
- If no `@match` is specified, the script will run on all pages
- The `GM_*` helpers below are injected by `helpers.js`
- There are `toast("short message")` function
- Script console output (console.log/alert/warn) can be monitored using:
```bash
./make.sh test
```

Common use cases include:
- Adding dark mode to websites
- Customizing website appearance
- Adding new functionality
- Fixing mobile compatibility issues

Example of some useful scripts:
- [dark-mode.js](https://gist.github.com/Jipok/01d12591491816625649a467db898518) - Universal dark theme that respects system preferences
- [instant.js](https://raw.githubusercontent.com/instantpage/instant.page/refs/heads/master/instantpage.js) - Speed up page loads by preloading pages when the user taps

## Additional WebView Options
The following advanced options can also be configured:
```ini
versionCode = 1                       # Android version code (integer, must increase on each release)
versionName = 1.0                     # Human-readable version shown to users
cookies = "key1=value1; key2=value2"  # Cookies for the configured site, never for a deeplink host
basicAuth = login:password            # HTTP Basic Auth credentials for the configured site
userAgent = "MyCustomUserAgent/1.0"   # Custom UserAgent header
JSEnabled = true                      # Enable JavaScript execution
JSCanOpenWindowsAutomatically = true  # Allow JS to open new windows/popups

DomStorageEnabled = true              # Enable HTML5 DOM storage
MediaPlaybackRequiresUserGesture = true  # Require a tap before media can play
AllowFileAccess = true
AllowFileAccessFromFileURLs = true
forceLandscapeMode = false            # Lock screen orientation to landscape (Android 15+ ignores it on large screens)
forceDarkTheme = false                # Tell the page the system is dark and theme the app dark (the site styles itself)

showDetailsOnErrorScreen = false      # Show connection error details for user
DebugWebView = false                  # Enable remote debugging via chrome://inspect
confirmOpenExternalApp = true         # Show confirmation before opening external app
blockLocalhostRequests = true         # Block requests to localhost
trustUserCA            = false        # Allow app to trust user-installed SSL certs
ca                     =              # Bundle custom CA certificates (see Advanced Topics)
geolocationEnabled     = false        # Allow access to device location (GPS) — asks for precise and approximate; the page gets a position if either is granted
cameraEnabled          = false        # Allow access to the camera for WebRTC or scanning
microphoneEnabled      = false        # Allow access to the microphone for audio recording or calls
allowMixedContent      = false        # Allow loading HTTP content on HTTPS sites
allowSslErrors         = false        # Offer to continue past a TLS error (main page only)
pullToRefresh          = false        # Enable native pull-to-refresh gesture
cacheMode              = default      # Or: "no_cache" (always network), "aggressive" (offline-first)
fadeInDuration         = 400          # Duration(in ms) of WebView fadeIn animation after spinner gone
```

## Advanced Topics

<details>
<summary><b>App Links / Deep Links</b></summary>

You can make your app handle links to the website by setting the `deeplink` option in your configuration file. When set, clicking links to your website on the device will open them in your app instead of a browser.

For example, if your website is `https://example.com`, set:
```ini
deeplink = example.com
# or multiple
deeplink = example.com www.example.com
```

A link that arrives this way becomes the app's current URL, so the linked host counts as the app's own (its links stay inside the app instead of opening a browser). It does **not** move the configured `cookies` or `basicAuth` credentials: those stay bound to the site the config describes, and a link naming some other host never gets them.

</details>

<details>
<summary><b>Edge-to-Edge Display</b></summary>

You can enable an immersive `edge-to-edge` mode where your web content draws behind the system bars (the status bar at the top and the navigation bar at the bottom). This is ideal for modern designs that mimic a native app feel.

```ini
edgeToEdge = true
```

**Important:** When this option is enabled, you **must** update your website's CSS to prevent important content from being obscured by the system bars.

The app injects the necessary safe area insets as CSS custom properties on the `<html>` element. You can use these to add padding to your content.

```css
body {
  padding-top: var(--safe-area-inset-top);
  padding-bottom: var(--safe-area-inset-bottom);
  padding-left: var(--safe-area-inset-left);
  padding-right: var(--safe-area-inset-right);
}
```

Additionally, once the inset variables are applied, the app dispatches a custom event `WebToApkInsetsApplied` on the `document` object.

</details>

<details>
<summary><b>Pull-to-Refresh</b></summary>

Set `pullToRefresh = true` to enable the native pull-down-to-refresh gesture.

By default the page is simply reloaded. If you want to handle the refresh yourself (e.g. fetch data and update the DOM without a full page reload), define a hook on the injected `WebToApk` object:

```js
WebToApk.onPullToRefresh = function () {
  // ... your refresh logic ...
  WebToApk.setPullToRefreshRefreshing(false); // stop the spinner when done
  return true; // true = the app does not reload the page
};
```

If the hook is missing or returns `false`, the app reloads the page. When the hook returns `true` the spinner is stopped after 10 seconds even if `setPullToRefreshRefreshing(false)` was never called.

`WebToApk.setPullToRefreshEnabled(false)` can be called from JS to toggle the gesture at runtime.

Note: the gesture is decided by the *document's* scroll position. On a page that scrolls an inner
element (a typical SPA) the document never scrolls, so it fires wherever you are on the page. That is
the site's to decide — keep it off and enable it from the scroller's own handler:

```js
WebToApk.setPullToRefreshEnabled(false);
scroller.addEventListener('scroll', () => WebToApk.setPullToRefreshEnabled(scroller.scrollTop <= 0));
```

See [`confs/ptr-demo/`](confs/ptr-demo/) for a ready-to-build example that switches between JS and native control on the fly.

</details>

<details>
<summary><b>JavaScript API</b></summary>

The app injects a `window.WebToApk` object into every page. The methods are available at any time (a few, such as notifications, additionally require the user's permission).

| Method | Description |
|---|---|
| `showShortToast(message)` | Show a short Android toast. |
| `showLongToast(message)` | Show a long Android toast. |
| `share(title, text, url)` | Open the system share sheet. |
| `showNotification(title, message)` | Post a local Android notification. |
| `requestNotificationPermission()` | Ask for the notification permission. |
| `hasNotificationPermission()` | `true` if notifications are allowed. |
| `getNotificationPermissionState()` | `"granted"`, `"denied"` or `"prompt"`. |
| `clearAppCache()` | Clear the WebView HTTP cache. |
| `setPullToRefreshEnabled(enabled)` | Toggle the pull-to-refresh gesture at runtime. |
| `setPullToRefreshRefreshing(refreshing)` | Show/hide the refresh spinner. |
| `getDownloads()` | The app's downloads as a JSON string: `[{id, url, filename, mime, bytes, total, status}]`. |
| `removeDownload(id)` | Delete one of the app's downloads — record and file — and return `true` if it went. |
| `openDownloadedFile(id)` | Open a finished download with the system viewer. |
| `shareDownloadedFile(id)` | Send a finished download to another app. |

See also the hooks `WebToApk.onPullToRefresh` (above), `WebToApk.onDownloadStarted(id, url)` and `WebToApk.onDownloadComplete(id)`.

Downloads are handed to `DownloadManager`, so they keep going while the app is backgrounded or even killed. `getDownloads()` is therefore the source of truth: it reports `bytes`/`total` for an in-flight download, so a page can draw its own progress bar, plus a `status` of `pending`, `running`, `paused`, `successful` or `failed` (`total` stays `-1` until the size is known). The two hooks are best-effort — a page-defined callback only exists while the app is alive — so use them as a nudge to re-check `getDownloads()`, and poll it at a sane rate rather than in a tight loop. Downloads are scoped to the app: `getDownloads()` and `removeDownload()` only ever see files this app downloaded, and removing one deletes the file from `Downloads/` — no permission and no dialog are involved. The ids are remembered across restarts, so a page can clean up after itself. Every entry also carries the `url` the download was started from, and `onDownloadStarted(id, url)` hands it over at start time — that is the only way to tell which id belongs to which request when several run at once, since the hook itself carries nothing but the id.

`navigator.mediaSession` (metadata, playback state, position, action handlers) is forwarded to the native media notification automatically — no direct calls needed.

`helpers.js` additionally polyfills a few web APIs:

| Global | Description |
|---|---|
| `toast(message)` | Alias of `showShortToast`. |
| `navigator.share(data)` | Web Share API on top of the native share sheet. |
| `Notification`, `Notification.requestPermission()`, `Notification.permission` | Web Notification API on top of local Android notifications. |
| `GM_addStyle(css)`, `GM_getValue`/`GM_setValue`/`GM_deleteValue`/`GM_listValues`, `GM_addValueChangeListener`, `GM_setClipboard`, `GM_addElement`, `GM_log`, `GM_notification`, `GM_openInTab`, `GM_info`, `GM_xmlhttpRequest` | The Greasemonkey helpers shipped in `helpers.js`; values live in `localStorage`. |

</details>

<details>
<summary><b>Bundling Custom CA Certificates</b></summary>

`trustUserCA = true` only trusts CAs the **user installed on the device**. To make the app trust a CA out of the box, bundle it:

```ini
ca = my_root_ca.pem     # Relative to the config file, space-separated list is allowed
```

`make.sh` copies each certificate into `res/raw/` and adds it to the trust anchors in
`network_security_config.xml` (which the manifest always references), so nothing else needs to be
edited. The name must be lowercase (`my_ca.pem`) because it becomes the resource name. The list is
rebuilt on every build, so removing a certificate from the config removes it from the app.

A certificate error on the main page is **cancelled** by default. `allowSslErrors = true` shows a
dialog instead, which lets the user through anyway — the same escape hatch a browser offers, but it
is off unless you ask for it, and it never applies to background requests. The dialog names the site
and what is wrong with its certificate (expired, not valid yet, issued for a different host, or not
trusted), because "continue anyway" is not a question a user can answer without knowing whom they
are trusting.

Basic Auth is answered in one of two ways: `basicAuth = login:password` signs in automatically (once
per host and realm, and only for `mainURL`'s host, or its subdomains with `allowSubdomains`), and
anything else — a different host, a second challenge, no `basicAuth` at all — shows the prompt, which
names the host and the realm it is asking for.

</details>

<details>
<summary><b>Offline / Bundled Local Site</b></summary>

Instead of a remote `mainURL`, you can bundle a whole folder of HTML/CSS/JS into the APK and have the app open it:

```ini
site = ./mysite     # Must contain index.html, relative paths are resolved from the config file
```

`make.sh` copies the folder into the APK and the app opens it instead of `mainURL`. The files are served over a virtual `https://app.local/` origin instead of `file://`, so everything that needs a real origin keeps working: `fetch`, `localStorage`, `sessionStorage`, IndexedDB, ES modules, Web Workers, cookies and the `WebToApk` JavaScript API. The app works with the network completely off.

Notes:
- `mainURL`, `deeplink`, `allowSubdomains` and `cookies` are ignored while `site` is set.
- See <code>confs/local-demo</code> for a working example.

</details>

<details>
<summary><b>Notifications & Push</b></summary>

**Local notifications work.** While your page is open it can show real Android notifications through the web `Notification` API (`helpers.js` polyfills `new Notification(...)`, `Notification.requestPermission()` and `Notification.permission` on top of the native bridge), and `navigator.mediaSession` drives the media notification and the lock-screen controls.

**Server push to a closed app does not work, and it is not something this project can fix.** Android WebView has no Service Worker and no Push API (Google marked Web Push in WebView as WONTFIX), so there is no way to wake the app from your server once it is closed.

The project used to bundle [UnifiedPush](https://unifiedpush.org/) to work around this, but it was removed. It required the user to install a separate *distributor* app, disable battery optimisation and often enable autostart (Xiaomi/Oppo/Realme and friends), and even then delivery to a fully closed app was unreliable — too much friction for a zero-config wrapper.

If you need real push, open an issue. An **optional** integration (FCM, HMS or another vendor push) can be added, where each fork registers its own token for its own app with the provider that works in its region (Google, Huawei, ...). That keeps the default build free of any push dependency.

</details>

## Technical Details

- Target Android API: 33 (Android 13)
- Minimum Android API: 26 (Android 8.0)
- Build tools version: 33.0.2
- Gradle version: 7.4
- Required Java version: 17 or newer (a JDK, not a JRE)

## Notes

- Web SQL is off and passwords are never saved, so those two settings are no longer config keys.
  `setSavePassword` has been an explicit no-op in the WebView implementation since at least WebView
  76 and there is no password store behind it. `setDatabaseEnabled` is a no-op on current WebView
  (Web SQL was removed from Chromium), but an old engine still honours it — so it is pinned to
  `false`, which makes an old device behave like a new one instead of exposing a capability that
  depends on the installed WebView. A config that still sets `SavePassword` or `DatabaseEnabled`
  fails to apply and the line has to be deleted
- All app data is stored in the app's private directory
- The keystore `my-release-key.jks` (in the repository root) password is set to "123456" by default
- Internet permission is required and automatically included
- `blockLocalhostRequests` covers loopback however it is spelled: `127.0.0.0/8`, `::1`, `0.0.0.0`, `localhost`
- `forceDarkTheme` reports a dark system to the page and themes the app dark, whatever the real system setting is: the WebView answers `prefers-color-scheme: dark` and the app uses its `values-night` colours. It does **not** restyle the page — a site without a dark theme stays as it is, so add a userscript if you want one
- If you need to support [different Android versions](https://apilevels.com/), edit `build.gradle` accordingly
- Based on the original work from: https://github.com/successtar/web-to-app  

## Media

The bridge has a media surface — `updateMediaMetadata`, `updateMediaPlaybackState`,
`updateMediaPositionState` and `setMediaActionHandlers` — which drives the foreground service that
owns the notification and the lock-screen controls. It is not meant to be called directly: a WebView
has no `navigator.mediaSession` integration at all, so `helpers.js` defines that standard object and
forwards it here. A page that uses `navigator.mediaSession` gets working system controls without
knowing this app exists.
