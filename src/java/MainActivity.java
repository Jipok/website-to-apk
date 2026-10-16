package com.myexample.webtoapk;

import android.net.http.SslError;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.webkit.WebViewAssetLoader;
import android.os.Bundle;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.os.Handler;
import android.webkit.WebSettings;
import android.content.Intent;
import android.net.Uri;
import android.content.res.Configuration;
import android.widget.EditText;
import android.webkit.JsResult;
import android.webkit.JsPromptResult;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.graphics.Bitmap;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.graphics.Color;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceError;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import android.webkit.JavascriptInterface;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.widget.TextView;
import android.app.Activity;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient.FileChooserParams;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.webkit.DownloadListener;
import android.app.DownloadManager;
import android.database.Cursor;
import android.webkit.URLUtil;
import android.os.Environment;
import static android.content.Context.DOWNLOAD_SERVICE;
import android.app.PendingIntent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.graphics.Insets;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.webkit.PermissionRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int MEDIA_PERMISSION_REQUEST_CODE = 1001;
    private static final int DOWNLOAD_PERMISSION_REQUEST_CODE = 3;
    private static final String EXTRA_RENDERER_RESTARTED = "webtoapk_renderer_restarted";
    // A download that waits for WRITE_EXTERNAL_STORAGE: {url, userAgent, contentDisposition, mimetype}
    private String[] pendingDownload;
    // Downloads are only ever this app's: from API 29 the platform scopes DownloadManager per app,
    // but below that a query returns every download on the device. The ids this app started are
    // remembered (and persisted), so getDownloads() and removeDownload() cannot see anyone else's.
    private static final String DOWNLOADS_PREFS = "webtoapk_downloads";
    private static final String DOWNLOADS_PREFS_KEY = "ids";
    private static final String NOTIFICATION_PREFS = "webtoapk_notifications";
    private static final String NOTIFICATION_ASKED_KEY = "asked";
    // Read from the JavaBridge thread (getDownloads/removeDownload), written from the main one
    private final Set<Long> ownDownloads = ConcurrentHashMap.newKeySet();
    // In-flight bridge requests, so httpAbort can cancel one
    private final Map<String, HttpURLConnection> httpRequests = new ConcurrentHashMap<>();

    private WebView webview;
    private CustomWebChrome chrome;
    private UserScriptManager userScriptManager;
    private ProgressBar spinner;
    private SwipeRefreshLayout swipeRefresh;
    private ViewGroup mainLayout;   // android.R.id.content: the error screen is stacked onto it
    private View errorLayout;
    private View appLayout;         // R.id.appRoot, hidden while the error screen is up
    private boolean downloadReceiverRegistered;
    private ValueCallback<Uri[]> mFilePathCallback;       // Image upload
    private ActivityResultLauncher<Intent> fileChooserLauncher; // Image upload
    private ActivityResultLauncher<Intent> saveTarget; // Blob export destination (SAF)
    // Written on main (the SAF picker) and used from the JavaBridge thread (appendChunk)
    private volatile OutputStream saveStream;
    // Set by appendChunk (JavaBridge thread), cleared when a target is picked and when finishSave
    // reports it, so the page's own `ok` is not the only word on whether the bytes landed
    private volatile boolean saveFailed;
    private BroadcastReceiver downloadCompleteReceiver; // Reports finished downloads to the page
    private MediaPlaybackService.MediaActionListener mediaActionListener;
    private PermissionRequest currentPermissionRequest;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private String insetsJs; // the last insets script: a new document starts without the properties
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTimeout = () -> {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    };

    // The URL from the config (or the bundled site), kept before a deeplink may replace mainURL:
    // the cookies and the Basic Auth credentials belong to the site the config describes.
    private String configuredMainURL;

    // Config surface: ./make.sh writes only between these two markers, so a same-named local
    // elsewhere in the class cannot be patched by mistake
    // <webtoapk:config>
    String mainURL = "https://github.com/Jipok";
    boolean requireDoubleBackToExit = true;
    boolean allowSubdomains = true;

    boolean enableExternalLinks = true;
    boolean openExternalLinksInBrowser = true;
    boolean confirmOpenInBrowser = true;

    boolean allowOpenMobileApp = false;
    boolean confirmOpenExternalApp = true;

    String cookies = "";
    String basicAuth = "";
    /* A wrong basicAuth password would be replayed on every 401: the WebView calls this again after
       each proceed, so one automatic attempt per host+realm and the dialog after that. */
    final Set<String> authTried = new HashSet<>();
    String userAgent = "";
    boolean blockLocalhostRequests = true;
    boolean JSEnabled = true;
    boolean JSCanOpenWindowsAutomatically = true;
    boolean DomStorageEnabled = true;
    boolean MediaPlaybackRequiresUserGesture = true;
    boolean AllowFileAccess = true;
    boolean AllowFileAccessFromFileURLs = true;
    boolean showDetailsOnErrorScreen = false;
    boolean forceLandscapeMode = false;
    boolean edgeToEdge = false;
    boolean forceDarkTheme = false;
    boolean allowMixedContent = false;
    // The dialog below lets the user through a broken certificate, so it is opt-in
    boolean allowSslErrors = false;
    boolean pullToRefresh = false;
    String cacheMode = "default";
    int fadeInDuration = 400;
    boolean DebugWebView = false;

    boolean geolocationEnabled = false;
    boolean cameraEnabled = false;
    boolean microphoneEnabled = false;
    // </webtoapk:config>

    // Local site (config key `site`), served from assets over a virtual https origin
    private static final String LOCAL_SITE_DOMAIN = "app.local";
    private static final String LOCAL_SITE_URL = "https://" + LOCAL_SITE_DOMAIN + "/";
    private static final String LOCAL_SITE_ASSET_DIR = "site";
    private WebViewAssetLoader assetLoader;

    /* forceDarkTheme has to reach the WebView, not just the app's own resources: the WebView takes
       prefers-color-scheme from the configuration of the context it is created with, and AppCompat's
       night mode does not carry that far. Overriding the base context does, and the app's own
       values-night resources follow from the same configuration. */
    @Override
    protected void attachBaseContext(Context base) {
        if (forceDarkTheme) {
            Configuration night = new Configuration(base.getResources().getConfiguration());
            night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
            base = base.createConfigurationContext(night);
        }
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (forceDarkTheme) {
            // The base-context override above is what the WebView reads; this is what makes the
            // app's own resources (and every values-night colour) go dark
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        }
        if (edgeToEdge) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        }

        super.onCreate(savedInstanceState);

        if (edgeToEdge) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        // Both channels (web + media) are created in one place, see MediaPlaybackService
        MediaPlaybackService.createNotificationChannels(this);
        Log.d("WebToApk", "Notification channels created.");

        if (forceLandscapeMode) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        setContentView(R.layout.activity_main);
        // The content view, not its parent: the error screen goes on top of it with addContentView()
        // (a public Activity API). Reaching for the parent meant assuming the DecorView's own layout
        // — and it left the error screen outside the inset padding applied below.
        mainLayout = findViewById(android.R.id.content);
        appLayout = findViewById(R.id.appRoot);
        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain(LOCAL_SITE_DOMAIN)
            .addPathHandler("/", this::handleLocalSiteRequest)
            .build();
        if (hasBundledSite()) {
            mainURL = LOCAL_SITE_URL;
        }
        // Everything below can have mainURL replaced by a deeplink; the config's own URL has to
        // survive that, or the cookies and the credentials follow whatever host the deeplink named.
        configuredMainURL = mainURL;
        userScriptManager = new UserScriptManager(this, mainURL);

        // Handle intent
        String deeplinkUrl = urlFromIntent(getIntent());
        Log.d("WebToApk", "Action: " + getIntent().getAction());
        Log.d("WebToApk", "Data: " + getIntent().getData());
        if (deeplinkUrl != null) {
            mainURL = deeplinkUrl;
        }

        webview = findViewById(R.id.webView);
        webview.setAlpha(0f);
        spinner = findViewById(R.id.progressBar1);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setEnabled(pullToRefresh);
        swipeRefresh.setOnRefreshListener(this::onPullToRefresh);
        webview.setWebViewClient(new CustomWebViewClient());
        chrome = new CustomWebChrome();
        webview.setWebChromeClient(chrome);
        WebAppInterface webAppInterface = new WebAppInterface(this);
        webview.addJavascriptInterface(webAppInterface, "WebToApk");
        registerBackCallback();

        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(JSEnabled);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(JSCanOpenWindowsAutomatically);
        webSettings.setGeolocationEnabled(geolocationEnabled);
        webSettings.setDomStorageEnabled(DomStorageEnabled);
        // Web SQL: removed from current WebView, where this is an explicit no-op ("Intentional
        // no-op" in ContentSettingsAdapter), but still honoured by WebView 76 — whose setting
        // defaults to *off*. Pinned to false so an old engine behaves like a new one instead of
        // exposing a capability that depends on the installed WebView.
        webSettings.setDatabaseEnabled(false);
        webSettings.setMediaPlaybackRequiresUserGesture(MediaPlaybackRequiresUserGesture);
        // setSavePassword is deliberately not called: it has been an explicit no-op in the WebView
        // implementation since at least WebView 76, and there is no password store behind it.
        webSettings.setAllowFileAccess(AllowFileAccess);
        webSettings.setAllowFileAccessFromFileURLs(AllowFileAccessFromFileURLs);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webview.setWebContentsDebuggingEnabled(DebugWebView);

        if (allowMixedContent) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        if (!userAgent.isEmpty()) {
            webSettings.setUserAgentString(userAgent);
        }

        switch (cacheMode) {
            case "aggressive":
                // Offline-first: Use cache if content is there, otherwise load from network.
                webSettings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
                break;
            case "no_cache":
                // Always load from the network, do not use the cache
                webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
                webview.clearCache(true);
                break;
            default:
                // Uses cache based on server's "Cache-Control" headers
                webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
                break;
        }

        webview.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        CookieManager cookieManager = CookieManager.getInstance();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true);
        // The config lists cookies as "key1=value1; key2=value2": setCookie takes one cookie, so
        // passing the whole list set only the first one and treated the rest as attributes. They
        // are set for the configured URL, never for the host a deeplink may point at.
        for (String cookie : cookies.split(";")) {
            if (!cookie.trim().isEmpty()) cookieManager.setCookie(configuredMainURL, cookie.trim());
        }
        cookieManager.flush();

        // Image upload support
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Uri[] results = null;

                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Intent intentData = result.getData();

                    // Not FileChooserParams.parseResult: even current WebView returns only
                    // Intent.getData() there and drops the ClipData, so <input multiple> would
                    // silently become single-select. Это единственная причина, почему разбор свой.
                    if (intentData.getClipData() != null) {
                        int count = intentData.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = intentData.getClipData().getItemAt(i).getUri();
                        }
                    } else if (intentData.getData() != null) {
                        // Один файл
                        results = new Uri[]{intentData.getData()};
                    }
                }

                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(results);
                    mFilePathCallback = null;
                }
            }
        );

        // Blob/data: export. DownloadManager cannot fetch those, so the page streams the bytes to
        // a destination the user picks with the system file picker (SAF)
        saveTarget = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                    return; // Cancelled, nothing to do
                }
                try {
                    closeSaveStream();   // a second save must not leak the first stream
                    saveStream = getContentResolver().openOutputStream(result.getData().getData());
                    saveFailed = false;
                    if (webview == null) {   // renderer gone: nothing will send the blob
                        // The stream would sit open until the next save or onDestroy: fail the save
                        // now instead of leaking it.
                        closeSaveStream();
                        Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
                        return;
                    }
                    webview.evaluateJavascript("__webtoapkSendBlob()", null);
                } catch (Exception e) {
                    Log.e("WebToApk", "Cannot open save target", e);
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
                }
            }
        );

        // File downloading support
        loadOwnDownloads();
        webview.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                // DownloadManager only accepts HTTP(S), constructing a Request with anything else throws
                String scheme = Uri.parse(url).getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    webview.evaluateJavascript("__webtoapkSaveBlob(" + JSONObject.quote(url)
                        + ", " + JSONObject.quote(mimetype) + ")", null);
                    return;
                }

                // The public Downloads directory needs WRITE_EXTERNAL_STORAGE before API 29
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingDownload = new String[]{url, userAgent, contentDisposition, mimetype};
                    ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, DOWNLOAD_PERMISSION_REQUEST_CODE);
                    return;
                }

                startDownload(url, userAgent, contentDisposition, mimetype);
            }
        });

        // DownloadManager keeps running without us, so the download may outlive this process:
        // the event only reaches the page while the app is still alive
        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyPage("onDownloadComplete",
                    Long.toString(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)));
            }
        };
        // ACTION_DOWNLOAD_COMPLETE is neither a protected broadcast nor sent by the system uid
        // (DownloadProvider is its own app), so from targetSdk 34 it needs RECEIVER_EXPORTED:
        // NOT_EXPORTED would silently stop the callback. A forged id is harmless, the page is
        // documented to re-read getDownloads(), which only ever lists our own downloads.
        ContextCompat.registerReceiver(this, downloadCompleteReceiver,
            new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED);
        downloadReceiverRegistered = true;

        // API 35+ forces every app edge-to-edge, so the listener is installed even for a config
        // that never asked for it: that config keeps its old look by padding the content out of
        // the bars itself. Below 35 the decor already consumed the insets, so they arrive as zero.
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, windowInsets) -> {
            // Get the insets for system bars in hardware pixels. A display cutout is *not* part of
            // `systemBars()`: in landscape it sits on the left or right edge, where the system bars
            // contribute nothing — measured on a Pixel 9 Pro XL (API 36, punch hole) the insets stayed
            // L:0.00 R:0.00 in landscape, so the page ran under the hole. The union is what the CSS
            // vars and the padding below are computed from.
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // The keyboard is not a bar either, and `adjustResize` no longer resizes the window from
            // API 35 on (every window is edge-to-edge there, asked for or not). Where the insets are
            // consumed the WebView never learns that the IME is up at all — measured on API 36: with
            // the keyboard shown, `innerHeight` and `visualViewport.height` were both unchanged and the
            // bottom inset was still the navigation bar's, so an input at the bottom of the page stayed
            // behind the keyboard (screenshot). Padding the content by the IME is the `adjustResize` of
            // an edge-to-edge window. It is applied *only* in the edgeToEdge branch: with the insets
            // left unconsumed the WebView handles the keyboard itself — the same measurement with
            // edgeToEdge = false had `visualViewport.height` fall 2013 -> 776 and the input visible —
            // and padding there too would shrink the page twice.
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            // Get the device's screen density factor.
            float density = v.getResources().getDisplayMetrics().density;

            // Convert hardware pixels to density-independent CSS pixels.
            float top = insets.top / density;
            float bottom = insets.bottom / density;
            float left = insets.left / density;
            float right = insets.right / density;

            Log.d("WebToApk", String.format(java.util.Locale.US,
                "Insets (CSS px) -> T:%.2f, B:%.2f, L:%.2f, R:%.2f",
                top, bottom, left, right
            ));

            // Pass insets to WebView via CSS custom properties.
            // These names are chosen to be close to the standard CSS env() variables.
            // The web content can then use var(--safe-area-inset-top).
            // The first dispatch arrives while the document is still being parsed, and then there is
            // no documentElement yet: the unguarded version threw "Cannot read properties of null
            // (reading 'style')" in the page's console on every load.
            String js = String.format(java.util.Locale.US,
                "var root = document.documentElement; if (root) {" +
                "root.style.setProperty('--safe-area-inset-top', '%.2fpx');" +
                "root.style.setProperty('--safe-area-inset-bottom', '%.2fpx');" +
                "root.style.setProperty('--safe-area-inset-left', '%.2fpx');" +
                "root.style.setProperty('--safe-area-inset-right', '%.2fpx');" +
                "document.dispatchEvent(new CustomEvent('WebToApkInsetsApplied')); }",
                top, bottom, left, right
            );
            // The renderer may be gone (onRenderProcessGone nulls webview while the activity is
            // finishing) and an insets dispatch can still arrive — the IME closing, say.
            if (webview != null) webview.evaluateJavascript(js, null);
            insetsJs = js;   // onPageFinished replays it for every new document

            // The icons have to contrast with whatever shows through the bars. With edgeToEdge that
            // is the page; without it the window background (API 35+ forces the window edge-to-edge
            // even for a config that never asked for it, and the padding below keeps the content out
            // of the bars). The system night mode is what the page itself sees through
            // prefers-color-scheme, so the two agree — except for a site that hard-codes its own
            // theme, which is exactly the wrapped-page case.
            if (insets.top > 0 || insets.bottom > 0) {
                boolean night = (getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
                WindowInsetsControllerCompat controller =
                        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                controller.setAppearanceLightStatusBars(!night);
                controller.setAppearanceLightNavigationBars(!night);
            }
            if (edgeToEdge) {
                // The keyboard would otherwise cover the bottom of the page: the page is drawn under
                // the bars on purpose, but an input the user cannot see is not part of that bargain.
                // Bottom only, and only the IME's own inset — the bars stay unpadded here.
                v.setPadding(ime.left, 0, ime.right, ime.bottom);
                return WindowInsetsCompat.CONSUMED;
            }
            // Leave the insets unconsumed so the IME keeps resizing the window as before, and keep the
            // content out of the bars (and out of the cutout).
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        boolean stateRestored = false;
        if (savedInstanceState != null) {
            // Restore the state of the WebView from the saved bundle.
            stateRestored = webview.restoreState(savedInstanceState) != null;
            if (stateRestored) Log.d("WebToApk", "Restored WebView state");
        }

        if (!stateRestored) {
            // It's a fresh launch. Or broken old. Load the main URL.
            webview.loadUrl(mainURL);
        }

        mediaActionListener = action -> {
            if (action != null) {
                executeMediaActionInWebView(action);
            }
        };
        MediaPlaybackService.addMediaActionListener(mediaActionListener);
    }

    private void executeMediaActionInWebView(String action) {
        Log.d("WebToApk", "Executing JS for media action: " + action);
        if (webview != null) {
            webview.post(() -> {
                String js = "if (typeof window.__runMediaAction === 'function') { window.__runMediaAction('" + action + "'); }";
                webview.evaluateJavascript(js, null);
            });
        }
    }

    /* Report a download event to the page, if it defines the matching WebToApk hook. The arguments
       are already JS literals: a string is quoted by the caller with JSONObject.quote. */
    private void notifyPage(String hook, String... arguments) {
        if (webview == null) return;
        StringBuilder list = new StringBuilder();
        for (String argument : arguments) {
            if (list.length() > 0) list.append(',');
            list.append(argument);
        }
        webview.evaluateJavascript(
            "(function(){try{var f=window.WebToApk&&window.WebToApk." + hook + ";" +
            "if(typeof f==='function')f(" + list + ");}catch(e){}})()", null);
    }

    /* Hand a finished download over to another app. getUriForDownloadedFile only ever grants
       access to downloads of ours, so no storage permission is involved */
    private void handOffDownload(String id, String action) {
        uiHandler.post(() -> {
            try {
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                long downloadId = Long.parseLong(id);
                Uri uri = manager.getUriForDownloadedFile(downloadId);
                if (uri == null) throw new IllegalStateException("download is not finished: " + id);
                // The scheme is the story of this handoff (content:// on every API this app supports;
                // see "getUriForDownloadedFile returns file://" in notes/review-claims.md, refuted) and
                // it is what the device suite asserts on — the VIEW intent may find no viewer, this hit.
                Log.d("WebToApk", "Handing off download " + id + " to " + action + " as " + uri.getScheme());
                String mime = manager.getMimeTypeForDownloadedFile(downloadId);
                Intent intent = new Intent(action);
                if (Intent.ACTION_SEND.equals(action)) {
                    intent.setType(mime == null ? "*/*" : mime).putExtra(Intent.EXTRA_STREAM, uri);
                } else {
                    intent.setDataAndType(uri, mime == null ? "*/*" : mime);
                }
                startActivity(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot hand off download " + id, e);
                Toast.makeText(MainActivity.this, R.string.open_download_failed, Toast.LENGTH_LONG).show();
            }
        });
    }

    /* A page could ask the bridge for gigabytes: read at most this much and say so */
    private static final int MAX_HTTP_BODY_CHARS = 10 * 1024 * 1024;

    /* The addresses blockLocalhostRequests refuses — in shouldInterceptRequest and in the bridge.
       A name is not resolved here: shouldInterceptRequest sits in the request path of every
       subresource, so a DNS lookup would delay page loads. Literals, "localhost" and
       "*.localhost" cover the spellings a page reaches for; README documents the limit. */
    private static boolean isLocalhost(String host) {
        if (host == null || host.isEmpty()) return false;
        String bare = host;
        if (bare.charAt(0) == '[' && bare.charAt(bare.length() - 1) == ']') {
            bare = bare.substring(1, bare.length() - 1);   // Uri.getHost() keeps an IPv6 literal bracketed
        }
        if ("localhost".equalsIgnoreCase(bare) || bare.toLowerCase(Locale.US).endsWith(".localhost")) {
            return true;
        }
        for (int i = 0; i < bare.length(); i++) {
            char c = bare.charAt(i);
            if ((c < '0' || c > '9') && c != '.' && c != ':') return false;   // a name, not a literal
        }
        try {
            InetAddress address = InetAddress.getByName(bare);
            return address.isLoopbackAddress() || address.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;   // not an address after all; the request itself reports the failure
        }
    }

    /* Uri.getHost() is null for a bracketed IPv6 literal ("https://[::1]:9/"), so fall back to the
       authority; both the WebView's request path and the bridge have to read the host the same way */
    private static String hostOf(Uri uri) {
        String host = uri == null ? null : uri.getHost();
        // Measured: "https://[::1]:9/" gives getHost() == "[" (and "[0" for a full IPv6 address),
        // so anything with a bracket in it is re-read from the authority below
        if (host != null && !host.isEmpty() && host.indexOf('[') < 0) return host;
        String authority = uri == null ? null : uri.getAuthority();
        if (authority == null) return null;
        int at = authority.lastIndexOf('@');                 // strip userinfo
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) {                     // [::1]:9 -> ::1
            int end = authority.indexOf(']');
            return end > 0 ? authority.substring(1, end) : authority;
        }
        int colon = authority.lastIndexOf(':');              // host:port -> host
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    /* The bridge can afford what the WebView's request path cannot: a DNS lookup, so a name that
       resolves to loopback (localtest.me) is refused too. The request resolves it anyway. */
    private static boolean isLocalhostTarget(String url) {
        String host = hostOf(Uri.parse(url == null ? "" : url));
        if (host == null || host.isEmpty()) return false;
        if (isLocalhost(host)) return true;
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isAnyLocalAddress()) return true;
            }
        } catch (Exception ignored) {
            // an unresolvable host is not a loopback address
        }
        return false;
    }

    /* The bridge answers with a JSON object: a failure is an `error` field, not an exception */
    private static String errorJson(String message) {
        try {
            return new JSONObject().put("error", message).toString();
        } catch (Exception e) {
            return "{\"error\":\"request failed\"}";
        }
    }

    /* DownloadManager statuses are numbers, which the page cannot read */
    private static String downloadStatus(int status) {
        switch (status) {
            case DownloadManager.STATUS_PENDING: return "pending";
            case DownloadManager.STATUS_RUNNING: return "running";
            case DownloadManager.STATUS_PAUSED: return "paused";
            case DownloadManager.STATUS_SUCCESSFUL: return "successful";
            case DownloadManager.STATUS_FAILED: return "failed";
            default: return "unknown";
        }
    }

    /* The same site, or a subdomain of it in either direction. The dot matters: a bare
       endsWith() lets evilexample.com pass for example.com, which would hand it the configured
       Basic Auth credentials. */
    private static boolean sameSite(String host, String domain) {
        return host.equals(domain)
                || host.endsWith("." + domain)
                || domain.endsWith("." + host);
    }

    @Override
    protected void onDestroy() {
        // Nothing in here may throw: this runs on the way out, and after a crash inside onCreate a
        // second exception would hide the first. `unregisterReceiver` throws for a receiver that was
        // never registered (registration happens late in onCreate).
        if (downloadReceiverRegistered) unregisterReceiver(downloadCompleteReceiver);
        MediaPlaybackService.removeMediaActionListener(mediaActionListener);
        Intent intent = new Intent(this, MediaPlaybackService.class);
        stopService(intent);

        // A blob export in flight would otherwise keep the file descriptor until the process dies
        OutputStream stream = saveStream;   // read once: finishSave may clear it meanwhile
        saveStream = null;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                Log.w("WebToApk", "Cannot close the save stream", e);
            }
        }
        if (webview != null) {
            ViewGroup parent = (ViewGroup) webview.getParent();
            if (parent != null) parent.removeView(webview);
            webview.stopLoading();
            webview.destroy();
            webview = null;
        }
        super.onDestroy();
    }

    // The URL carried by a VIEW intent, or null when there is none
    private String urlFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        if (data == null) return null;
        /* Anything installed on the device can send this activity an explicit VIEW intent — it is
           exported, being the launcher — so the URL has to be checked here, not by the manifest
           filter, which an explicit intent bypasses. Two refusals:

             - a scheme other than http(s). `javascript:` used to run in the current document's
               origin, i.e. on the logged-in site (that is the probe technique in notes/traps.md),
               and `file://` was accepted too — a local page carrying the whole JS bridge.
             - a host that is not ours. It used to become mainURL, so it counted as internal and got
               the bridge on an attacker's page. sameSite() is deliberately the same check the Basic
               Auth credentials use, and it is bidirectional on purpose: `deeplink = youtube.com`
               with `mainURL = https://m.youtube.com` has to pass. */
        String scheme = data.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            Log.w("WebToApk", "Ignoring deeplink with scheme: " + scheme);
            return null;
        }
        String host = data.getHost(), configured = Uri.parse(configuredMainURL).getHost();
        if (host == null || configured == null
                || (allowSubdomains ? !sameSite(host, configured) : !host.equals(configured))) {
            Log.w("WebToApk", "Ignoring deeplink for a foreign host: " + host);
            return null;
        }
        return data.toString();
    }

    // The activity is singleTop: a deeplink that arrives while it is alive lands here
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = urlFromIntent(intent);
        if (url != null && webview != null) {
            Log.d("WebToApk", "Deeplink while running: " + url);
            // Same as onCreate: the deeplinked host has to count as this app's own, or every link
            // on the page it loads is treated as external
            mainURL = url;
            webview.loadUrl(url);
        }
    }

    // Save the state of the WebView (current URL, history, scroll position) during OOM kill
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // The renderer may be gone: onRenderProcessGone nulls webview while restarting the activity
        if (webview != null) webview.saveState(outState);
    }

    // The WebView is deliberately not paused in onPause / resumed in onResume: see TODO.md
    /* The ids of the downloads this app started, so a list query can be filtered by them and a foreign
       id refused. Persisted: the Downloads rows outlive the process. */
    private void loadOwnDownloads() {
        ownDownloads.clear();
        String stored = getSharedPreferences(DOWNLOADS_PREFS, MODE_PRIVATE)
                .getString(DOWNLOADS_PREFS_KEY, "");
        for (String id : stored.split(",")) {
            try {
                if (!id.isEmpty()) ownDownloads.add(Long.parseLong(id));
            } catch (NumberFormatException ignored) {
                // a corrupt entry must not take the whole list down
            }
        }
    }

    // The blob export writes into a document the user picked: close whatever a previous save left
    // open before taking a new one, so a second save cannot leak the first stream. The reference is
    // taken and cleared *before* closing: the SAF callback (main thread) and the bridge's finishSave
    // (JavaBridge thread) both call this, and reading the field twice would let one thread null it
    // between the check and the call. It catches everything for the same reason it exists — a save
    // must not fail on the way out.
    private void closeSaveStream() {
        OutputStream stream = saveStream;
        saveStream = null;
        try {
            if (stream != null) stream.close();
        } catch (Exception e) {
            Log.e("WebToApk", "Cannot close the save stream", e);
        }
    }

    private void saveOwnDownloads() {
        StringBuilder ids = new StringBuilder();
        for (long id : ownDownloads) {
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
        }
        getSharedPreferences(DOWNLOADS_PREFS, MODE_PRIVATE).edit()
                .putString(DOWNLOADS_PREFS_KEY, ids.toString()).apply();
    }

    /* Writes into the public Downloads directory: before API 29 that needs WRITE_EXTERNAL_STORAGE, so
       the caller goes through the permission round-trip first and resumes here from the callback. */
    private void startDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // Create a request for the download
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimetype);

        // Set cookies for the download request, it's important for authorized downloads. The header
        // is skipped when there is nothing to send: addRequestHeader turns a null value into "" on
        // every API level (26 through 36 — it throws only for a null *name*), which would put an
        // empty `cookie:` header on the wire. A test of the name, not a guard against a crash.
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null && !cookies.isEmpty()) request.addRequestHeader("cookie", cookies);
        request.addRequestHeader("User-Agent", userAgent);

        // Set download description and title using string resources
        request.setDescription(getString(R.string.download_description)); // Use string resource
        request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));

        // Show notification during and after download
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);

        // Set the destination directory for the downloaded file
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimetype));

        try {
            long id = downloadManager.enqueue(request);
            ownDownloads.add(id);
            saveOwnDownloads();
            Toast.makeText(getApplicationContext(), R.string.download_started, Toast.LENGTH_LONG).show();
            // Tell the page which download this is and where it came from, so it can ask about
            // its progress and tie the id back to the request it started
            notifyPage("onDownloadStarted", Long.toString(id), JSONObject.quote(url));
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), R.string.download_failed, Toast.LENGTH_LONG).show();
            Log.e("WebToApk", "Failed to start download", e);
        }
    }

    /* The WebView resources this app understands. Anything else — MIDI sysex, a resource a future
       WebView adds — is denied rather than granted blind: hardware access stays opt-in per config. */
    private List<String> grantableResources(String[] requested) {
        List<String> allowed = new ArrayList<>();
        for (String resource : requested) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                if (cameraEnabled) allowed.add(resource);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                if (microphoneEnabled) allowed.add(resource);
            } else if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                allowed.add(resource);   // DRM playback, no OS permission behind it
            }
        }
        return allowed;
    }

    // FINE or COARSE is enough — see the geolocation prompt in CustomWebChrome
    private boolean locationGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Check if ALL permissions in the array are granted
        boolean isGranted = true;
        if (grantResults.length == 0) {
            isGranted = false;
        } else {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    isGranted = false;
                    break;
                }
            }
        }

        // Handle Camera/Mic
        if (requestCode == MEDIA_PERMISSION_REQUEST_CODE) {
        if (currentPermissionRequest != null) {
            // One denied OS permission must not deny the whole request: the page asked for the
            // camera and/or the microphone, so grant whichever the user allowed and drop the
            // rest. The OS state is read again rather than correlated with grantResults.
            List<String> allowed = new ArrayList<>();
            for (String resource : grantableResources(currentPermissionRequest.getResources())) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) continue;
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) continue;
                allowed.add(resource);
            }
                if (allowed.isEmpty()) {
                    currentPermissionRequest.deny();
                } else {
                    currentPermissionRequest.grant(allowed.toArray(new String[0]));
                }
                currentPermissionRequest = null;
            }
        }
        // Handle the download that waited for WRITE_EXTERNAL_STORAGE
        else if (requestCode == DOWNLOAD_PERMISSION_REQUEST_CODE) {
            String[] download = pendingDownload;
            pendingDownload = null;
            if (isGranted && download != null) {
                startDownload(download[0], download[1], download[2], download[3]);
            } else {
                Toast.makeText(getApplicationContext(), R.string.download_failed, Toast.LENGTH_LONG).show();
            }
        }
        // Handle Location (Re-using the ID or creating a new one)
        else if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (geoCallback != null) {
                // Not `isGranted`: that means "all of them", and the approximate answer grants COARSE
                // only. Read the OS state, the way the camera/mic branch does.
                if (locationGranted()) {
                    // Tell WebView that permission is granted
                    geoCallback.invoke(geoOrigin, true, false);
                } else {
                    // Tell WebView that permission is denied
                    geoCallback.invoke(geoOrigin, false, false);
                }
                geoCallback = null;
                geoOrigin = null;
            }
        }
    }


    /* This allows:
        Remove "Confirm URL" title from js log/alert/dialog/confirm
        Open HTML5 video in fullscreen
    */
    private class CustomWebChrome extends WebChromeClient {

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            String src = consoleMessage.sourceId();
            Integer line = consoleMessage.lineNumber();
            String msg = consoleMessage.message();

            if (src.startsWith("http://") || src.startsWith("https://")) {
                src = src.replaceFirst("^https?://", "");
                Log.e("WebToApk", "[" + src + ":" + line + "] " + msg);
            } else {
                // User scripts colorful
                switch (consoleMessage.messageLevel()) {
                    case ERROR:
                        Log.e("WebToApk", "\033[0;31m[" + src + ":" + line  +"] " + msg + "\033[0m");
                        break;
                    case WARNING:
                        Log.w("WebToApk", "\033[1;33m[" + src + ":" +  line +"]\033[0m " + msg);
                        break;
                    case LOG:
                    case DEBUG:
                    case TIP:
                        Log.d("WebToApk", "\033[0;34m[" + src + ":" +  line +"]\033[0m " + msg);
                        break;
                }
            }
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final android.webkit.JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .setCancelable(false)
                .create()
                .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .setCancelable(false)
                .create()
                .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, final JsPromptResult result) {
            final EditText input = new EditText(MainActivity.this);
            input.setText(defaultValue);

            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> result.confirm(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> result.cancel())
                .setCancelable(false)
                .create()
                .show();
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            if (!geolocationEnabled) {
                callback.invoke(origin, false, false);
                return;
            }

            // Either permission is a yes: Android 12+ asks "precise or approximate" and grants
            // ACCESS_COARSE_LOCATION alone when the user picks approximate, so declaring and
            // requesting FINE by itself ends in a deny for a page that did nothing wrong (R43).
            if (!locationGranted()) {
                // Permission missing. Save callback and ask User.
                geoCallback = callback;
                geoOrigin = origin;
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        LOCATION_PERMISSION_REQUEST_CODE);
            } else {
                // Permission already granted by OS. Proceed.
                callback.invoke(origin, true, false);
            }
        }


        //////////////////////
        private View mCustomView;
        private WebChromeClient.CustomViewCallback mCustomViewCallback;
        private int mOriginalOrientation;
        private int mOriginalSystemUiVisibility;

        boolean isFullscreen() {
            return mCustomView != null;
        }

        @Override
        public void onHideCustomView() {
            // The WebView hides its own fullscreen in response to onCustomViewHidden, so it may call
            // this a second time — and back calls it directly. Without the guard that second call NPEs
            // on the callback cleared below.
            if (mCustomView == null) return;
            ((FrameLayout)getWindow().getDecorView()).removeView(mCustomView);
            mCustomView = null;
            getWindow().getDecorView().setSystemUiVisibility(mOriginalSystemUiVisibility);
            setRequestedOrientation(mOriginalOrientation);
            mCustomViewCallback.onCustomViewHidden();
            mCustomViewCallback = null;
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            // A second fullscreen request while one is showing is rejected, so the view on screen stays
            // the page's current one (the AOSP sample's rule) — but the new callback still has to be
            // ended, or the WebView waits forever for a custom view that nobody will hide.
            if (mCustomView != null) {
                callback.onCustomViewHidden();
                return;
            }
            mCustomView = view;
            mOriginalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            mOriginalOrientation = getRequestedOrientation();
            mCustomViewCallback = callback;
            ((FrameLayout)getWindow().getDecorView()).addView(mCustomView,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_IMMERSIVE);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            // Закрываем предыдущий callback если есть
            if (mFilePathCallback != null) {
                mFilePathCallback.onReceiveValue(null);
            }
            mFilePathCallback = filePathCallback;

            // Let the provider build the intent: it maps every accept entry (".pdf" ->
            // "application/pdf") and puts the whole list into EXTRA_MIME_TYPES, which the hand-built
            // version never did (it recognised "image" and nothing else). Two things it is not
            // uniform about, both in WebView's AwContentsClient.FileChooserParamsImpl: WebView 76
            // does not set EXTRA_ALLOW_MULTIPLE and passes an extension through as the MIME type,
            // and it keeps only the first entry of a multi-entry accept list.
            Intent intent = fileChooserParams.createIntent();

            // Поддержка множественного выбора
            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            String[] accepts = fileChooserParams.getAcceptTypes();
            String acceptType = intent.getType();
            // A ".pdf" type matches no file at all, and a truncated list would hide what the page
            // accepts, so in those cases stay permissive — EXTRA_MIME_TYPES, where the provider set
            // it, still does the filtering.
            if (accepts.length != 1 || acceptType == null || acceptType.startsWith(".")) {
                intent.setType("*/*");
            }

            Intent chooserIntent = Intent.createChooser(intent, getString(R.string.choose_file));

            try {
                fileChooserLauncher.launch(chooserIntent);
            } catch (ActivityNotFoundException e) {
                // The page is waiting for an answer: a callback dropped without onReceiveValue(null)
                // leaves its <input type=file> pending forever. Returning true says "handled, nothing
                // chosen", which is what happened.
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = null;
                Toast.makeText(MainActivity.this, R.string.no_file_manager, Toast.LENGTH_LONG).show();
                return true;
            }

            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // A resource the config turns off is dropped, but the rest of the request still is: a
            // page asking for camera and microphone keeps the microphone when the camera is off.
            List<String> wanted = grantableResources(request.getResources());
            if (wanted.isEmpty()) {
                request.deny();
                return;
            }

            // Calculate which Android permissions correspond to the requested WebView resources
            List<String> permissionsNeeded = new ArrayList<>();
            for (String resource : wanted) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.CAMERA);
                    }
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
            }

            if (permissionsNeeded.isEmpty()) {
                // We already have all OS permissions, grant Web permission immediately
                request.grant(wanted.toArray(new String[0]));
            } else {
                // We are missing OS permissions. Save the request and ask the user.
                currentPermissionRequest = request;
                ActivityCompat.requestPermissions(MainActivity.this, permissionsNeeded.toArray(new String[0]), MEDIA_PERMISSION_REQUEST_CODE);
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            super.onPermissionRequestCanceled(request);
            currentPermissionRequest = null;
        }
    }


    /**
     * This allows for a splash screen
     * Hide elements once the page loads
     * Show custom error page
     * Resolve issue with SSL certificate
     **/
    private class CustomWebViewClient extends WebViewClient {
        // Handle SSL issue
        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
            // Cancelling is the only safe default: the dialog below walks the user through a broken
            // certificate, so it has to be opted into with allowSslErrors.
            if (!allowSslErrors) {
                handler.cancel();
                return;
            }

            String failingUrl = error.getUrl();
            String currentUrl = view.getUrl();
            boolean isMainPage = (currentUrl != null && failingUrl != null && failingUrl.equals(currentUrl));
            if (!isMainPage) {
                handler.cancel();
                return;
            }

            final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle(R.string.notification_error_ssl_cert_invalid);
            // Which site, and what is wrong with it: "the certificate is bad" is not a question a
            // user can answer, and this dialog is the only place the opt-in is asked for.
            builder.setMessage(getString(R.string.ssl_error_message, failingUrl,
                    getString(sslErrorReason(error.getPrimaryError()))));
            // Back or an outside tap must answer the handler too, or the load waits forever
            builder.setOnCancelListener(dialog -> handler.cancel());

            builder.setPositiveButton(R.string.ssl_error_continue, (dialog, which) -> handler.proceed());

            builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> handler.cancel());
            final AlertDialog dialog = builder.create();
            dialog.show();
        }

        // Handle HTTP Basic Auth
        @Override
        public void onReceivedHttpAuthRequest(final WebView view, final android.webkit.HttpAuthHandler handler, String host, String realm) {
            // If basicAuth is set and valid, try to parse it
            if (MainActivity.this.basicAuth != null && !MainActivity.this.basicAuth.isEmpty()) {
                String[] parts = MainActivity.this.basicAuth.split(":", 2);
                if (parts.length == 2) {
                    String login = parts[0];
                    String password = parts[1];

                    // The credentials belong to the configured site: mainURL follows a deeplink, and
                    // answering a challenge from whatever host that named would hand them over
                    String mainDomain = "";
                    try {
                        mainDomain = Uri.parse(MainActivity.this.configuredMainURL).getHost();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    boolean domainIsValid = false;
                    if (mainDomain != null && !mainDomain.isEmpty() && host != null && !host.isEmpty()) {
                        if (MainActivity.this.allowSubdomains) {
                            // Subdomains count in either direction, with a dot boundary
                            domainIsValid = sameSite(host, mainDomain);
                        } else {
                            domainIsValid = host.equals(mainDomain);
                        }
                    }

                    if (domainIsValid && authTried.add(host + "\n" + realm)) {
                        // Credentials and domain are valid; proceed automatically, once
                        handler.proceed(login, password);
                        return;
                    }
                }
            }

            // Otherwise, show a custom dialog to prompt for credentials
            // Inflate a custom layout with two EditText fields for username and password
            final View dialogView = getLayoutInflater().inflate(R.layout.auth_dialog, null);
            final EditText usernameInput = dialogView.findViewById(R.id.username);
            final EditText passwordInput = dialogView.findViewById(R.id.password);

            // The host and realm are what tells the user who is asking — a bare "Authentication
            // Required" teaches them to type their password into anything that asks.
            String where = host == null ? "" : host;
            if (realm != null && !realm.isEmpty()) where = getString(R.string.auth_message, where, realm);

            new AlertDialog.Builder(MainActivity.this)
                .setTitle(R.string.auth_title)
                .setMessage(where)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    // Retrieve user input and proceed with HTTP authentication
                    String user = usernameInput.getText().toString();
                    String pass = passwordInput.getText().toString();
                    handler.proceed(user, pass);
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> handler.cancel())
                .setOnCancelListener(dialog -> handler.cancel())
                .show();
        }

        // Check for external link
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // A subframe navigating is not the user leaving the app: embeds, captchas and payment
            // frames cross domains all the time and must not raise the "open in browser" dialog
            if (!request.isForMainFrame()) return false;

            String url = request.getUrl().toString();

            // Check for non-standard URL scheme (external app)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (allowOpenMobileApp) {
                    if (confirmOpenExternalApp) {
                        // Show confirmation dialog before opening external app
                        new AlertDialog.Builder(view.getContext())
                            .setTitle(R.string.external_link)
                            .setMessage(R.string.open_in_external_app)
                            .setPositiveButton(android.R.string.yes, (dialog, which) -> openExternally(url, view))
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    } else {
                        // Open directly without confirmation
                        openExternally(url, view);
                    }
                } else {
                    Log.d("WebToApk", "Opening URLs in external app is disabled: " + url);
                }
                return true; // Consume the event so that the WebView does not load this URL
            }

            // Check if the URL is internal by comparing the host/domain
            if (isInternalUrl(url)) {
                // Internal link: let the WebView load it normally
                return false;
            }

            return handleExternalLink(url, view);
        }

        /* Is this URL one of ours? http(s) only — a `javascript:` URL handed to loadUrl runs in this
           WebView, and this WebView carries the bridge (`file://` counts the same way, which is why
           urlFromIntent refuses it too). The host has to be the page's own; `allowSubdomains` keeps
           youtube.com -> m.youtube.com working. Main-frame navigation and the `intent://` fallback
           both go through here, so a page cannot name a URL it is not allowed to navigate to. */
        private boolean isInternalUrl(String url) {
            Uri parsed = Uri.parse(url);
            String scheme = parsed.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            String urlDomain = parsed.getHost();
            String mainDomain = Uri.parse(mainURL).getHost();
            if (urlDomain == null || mainDomain == null) {
                return false;   // malformed URL
            }
            return allowSubdomains ? sameSite(urlDomain, mainDomain) : urlDomain.equals(mainDomain);
        }

        /* One place for "hand this URL to another app". An `intent://` URL is how an Android site
           names the app to open (Maps, Play, UPI); a missing handler used to crash the app, and a
           page must not get to name the component it starts, hence the nulls. */
        private void openExternally(String url, WebView view) {
            Intent intent;
            try {
                if (url.startsWith("intent:")) {
                    intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    intent.addCategory(Intent.CATEGORY_BROWSABLE);
                    intent.setComponent(null);
                    intent.setSelector(null);
                } else {
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                }
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot parse " + url, e);
                return;
            }
            try {
                view.getContext().startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e("WebToApk", "\033[0;31mNo application can handle this URL:\033[0m " + url, e);
                // intent://…;S.browser_fallback_url=<url>;end — where the site wants us to go instead.
                // The page wrote that string, so it may be anything: `javascript:` would run in this
                // WebView and a foreign host would inherit the bridge. The same gate main-frame
                // navigation goes through decides, and what fails it is dropped, not loaded.
                String fallback = intent.getStringExtra("browser_fallback_url");
                if (fallback == null || fallback.isEmpty()) return;
                if (isInternalUrl(fallback)) {
                    view.loadUrl(fallback);
                } else {
                    Log.w("WebToApk", "Ignoring browser_fallback_url: " + fallback);
                }
            }
        }

        private boolean handleExternalLink(String url, WebView view) {
            if (!enableExternalLinks) {
                return true; // Block external links
            }
            if (openExternalLinksInBrowser) {
                if (confirmOpenInBrowser) {
                    new AlertDialog.Builder(view.getContext())
                        .setTitle(R.string.external_link)
                        .setMessage(R.string.open_in_browser)
                        .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                            Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
                            openExternally(url, view);
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
                    return true;
                } else {
                    Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
                    openExternally(url, view);
                    return true;
                }
            }
            // Open in WebView
            Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String host = hostOf(request.getUrl());

            if (blockLocalhostRequests && isLocalhost(host)) {
                Log.d("WebToApk", "Blocked access to localhost resource: " + request.getUrl().toString());
                // Empty answer
                return new WebResourceResponse("text/plain", "UTF-8", null);
            }

            // Serve the bundled local site (`site = ...`) from assets
            WebResourceResponse assetResponse = assetLoader.shouldInterceptRequest(request.getUrl());
            if (assetResponse != null) {
                return assetResponse;
            }

            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView webview, String url, Bitmap favicon) {
            super.onPageStarted(webview, url, favicon);
            // A navigation that starts while the error screen is up has to take it down: Back from an
            // error, a deeplink or a reload would otherwise leave the screen over a page that did
            // load, with onPageFinished skipping its fade and its inset replay because the screen is
            // still up. Only when it is actually up — an unconditional reset would put the spinner
            // back and zero the alpha on every ordinary navigation.
            if (errorLayout != null) {
                hideErrorScreen();
            }
            userScriptManager.injectScripts(webview, url);
        }

        // Animation on app open
        @Override
        public void onPageFinished(WebView webview, String url) {
            if (swipeRefresh != null) {
                swipeRefresh.setRefreshing(false);
                uiHandler.removeCallbacks(refreshTimeout);
            }
            // While the error screen is up the fade is skipped: without this the WebView's own error
            // page shows through the animation that follows tryAgain.
            if (errorLayout == null) {
                Log.d("WebToApk","Current page: " + url);
                // The renderer survived a whole document, so the one-restart guard is over: the flag
                // rides on the intent, which the restarted instance keeps as its own, so leaving it
                // set would make the *next* unrelated crash close the app instead of restarting it.
                getIntent().removeExtra(EXTRA_RENDERER_RESTARTED);
                // The properties live on documentElement, so every navigation starts without them
                if (insetsJs != null) webview.evaluateJavascript(insetsJs, null);
                spinner.setVisibility(View.GONE);
                if (webview.getAlpha() == 0f) {
                    webview.animate().alpha(1f).setDuration(fadeInDuration).start();
                }
            }
            super.onPageFinished(webview, url);
        }

        // Show custom error page with `tryAgain` button
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            String errorDescription = error.getDescription().toString();
            int errorCode = error.getErrorCode();

            if (request.isForMainFrame()) {
                switch (errorCode) {
                    case ERROR_AUTHENTICATION:
                    case ERROR_BAD_URL:
                    case ERROR_CONNECT:
                    case ERROR_FAILED_SSL_HANDSHAKE:
                    case ERROR_FILE:
                    case ERROR_FILE_NOT_FOUND:
                    case ERROR_HOST_LOOKUP:
                    case ERROR_IO:
                    case ERROR_PROXY_AUTHENTICATION:
                    case ERROR_TIMEOUT:
                    case ERROR_TOO_MANY_REQUESTS:
                    case ERROR_UNKNOWN:
                    case ERROR_UNSUPPORTED_AUTH_SCHEME:
                    case ERROR_UNSUPPORTED_SCHEME:
                        Log.e("WebToApk", "Major error: " + errorCode + " - " + errorDescription + " url: " + request.getUrl());
                        // Reuse the screen: a second failure must not stack another copy on top of it
                        if (errorLayout == null) {
                            errorLayout = getLayoutInflater().inflate(R.layout.error, mainLayout, false);
                            appLayout.setVisibility(View.GONE);
                            addContentView(errorLayout, new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                        }
                        if (showDetailsOnErrorScreen) {
                            TextView errorTextView = errorLayout.findViewById(R.id.errorText);
                            if (errorTextView != null) {
                                errorTextView.setText("Error " + errorCode + ":\n" + errorDescription + "\nURL: " + request.getUrl().toString());
                            }
                        }
                        break;
                    default:
                        Log.w("WebToApk", "Minor error: " + errorCode + " - " + errorDescription + " url: " + request.getUrl());
                        break;
                }
            } else {
                Log.d("WebToApk", "Resource error: " + errorCode + " - " + errorDescription + " url: " + request.getUrl());
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
            Log.e("WebToApk", "WebView render process gone! Did crash: " + detail.didCrash());
            if (webview != null) {
                // The parent can be gone already — the error screen detaches nothing, but the
                // activity being destroyed does — and a null cast would NPE inside the handler that
                // is supposed to recover from a crash.
                ViewGroup parent = (ViewGroup) webview.getParent();
                if (parent != null) parent.removeView(webview);
                webview.destroy();
                webview = null;
            }
            Toast.makeText(MainActivity.this, "Recovering from memory kill...", Toast.LENGTH_SHORT).show();
            // A renderer that dies deterministically would restart the activity forever, so the
            // restart is only allowed once: the flag rides on the intent, which is what the new
            // instance receives.
            if (getIntent().getBooleanExtra(EXTRA_RENDERER_RESTARTED, false)) {
                Log.e("WebToApk", "Renderer died again, not restarting");
                finish();
                return true;
            }
            Intent restart = getIntent();
            restart.putExtra(EXTRA_RENDERER_RESTARTED, true);
            finish();
            startActivity(restart);
            return true;
        }

    }

    boolean doubleBackToExitPressedOnce = false;

    /* SslError's codes as something a user can act on. SSL_DATE_INVALID covers both "expired" and
       "not valid yet" — the platform does not say which — and everything else is "not trusted"
       except a name mismatch, which is the case a user most needs told apart: it usually means a
       wrong-host certificate, not an attacker. */
    private static int sslErrorReason(int primaryError) {
        switch (primaryError) {
            case SslError.SSL_IDMISMATCH:
                return R.string.ssl_error_host;
            case SslError.SSL_EXPIRED:
            case SslError.SSL_NOTYETVALID:
            case SslError.SSL_DATE_INVALID:
                return R.string.ssl_error_date;
            default:
                return R.string.ssl_error_untrusted;
        }
    }

    // Android 16 does not call onBackPressed for apps targeting 36 (predictive back is on by
    // default), so the same logic lives in a back callback, which every release dispatches.
    private void registerBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (chrome != null && chrome.isFullscreen()) {
                    chrome.onHideCustomView();   // back leaves fullscreen before it leaves the page
                    return;
                }
                if (webview == null) {   // renderer gone, the activity is already restarting
                    finish();
                    return;
                }
                if (webview.canGoBack()) {
                    webview.goBack();
                } else {
                    if (doubleBackToExitPressedOnce || !requireDoubleBackToExit) {
                        finish();
                        return;
                    }
                    doubleBackToExitPressedOnce = true;
                    Toast.makeText(MainActivity.this, R.string.exit_app, Toast.LENGTH_SHORT).show();

                    uiHandler.postDelayed(() -> doubleBackToExitPressedOnce = false, 2000);
                }
            }
        });
    }

    /* True when a local site was bundled by `make.sh` (config key `site`) */
    private boolean hasBundledSite() {
        try {
            String[] files = getAssets().list(LOCAL_SITE_ASSET_DIR);
            return files != null && files.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /* Serves assets/site/** at the root of the virtual origin, `/` maps to index.html */
    private WebResourceResponse handleLocalSiteRequest(String path) {
        // The loader strips the registered prefix, so `path` comes without the leading slash
        if (path.contains("..")) {
            return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", null, null);
        }
        if (path.isEmpty() || path.endsWith("/")) {
            path += "index.html";
        }
        try {
            return new WebResourceResponse(mimeTypeOf(path), null,
                getAssets().open(LOCAL_SITE_ASSET_DIR + "/" + path));
        } catch (IOException e) {
            // A null stream would make the WebView report a network error, so send an empty body
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0]));
        }
    }

    /* The platform table is not enough where the MIME type is checked strictly: a module served as
       text/plain is refused outright, and WebAssembly.instantiateStreaming needs application/wasm. */
    private static String mimeTypeOf(String path) {
        String name = path.toLowerCase(Locale.US);
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "text/javascript";
        if (name.endsWith(".wasm")) return "application/wasm";
        String mime = URLConnection.guessContentTypeFromName(path);
        return mime != null ? mime : "text/plain";
    }

    /* The one way out of the error screen: Try Again and a navigation that starts while it is up both
       go through here. `errorLayout != null` *is* the error state — the boolean it replaced was set
       and cleared in exactly the same places. */
    private void hideErrorScreen() {
        mainLayout.removeView(errorLayout);
        errorLayout = null;
        appLayout.setVisibility(View.VISIBLE);
        if (webview != null) {   // renderer gone: nothing left to fade in
            webview.setAlpha(0f);
            spinner.setVisibility(View.VISIBLE);
        }
    }

    /* Retry Loading the page */
    public void tryAgain(View v) {
        if (webview == null) {   // renderer gone, nothing to reload
            return;
        }
        hideErrorScreen();
        webview.reload();
    }

    /* Pull-to-refresh: let the page handle it via WebToApk.onPullToRefresh, else reload */
    private void onPullToRefresh() {
        if (webview == null) {
            swipeRefresh.setRefreshing(false);
            return;
        }
        webview.evaluateJavascript(
            "(function(){try{var f=window.WebToApk&&window.WebToApk.onPullToRefresh;" +
            "return (typeof f==='function')?!!f():false;}catch(e){return false;}})()",
            value -> {
                if ("true".equals(value)) {
                    Log.d("WebToApk", "Page handles pull-to-refresh");
                    uiHandler.removeCallbacks(refreshTimeout);
                    uiHandler.postDelayed(refreshTimeout, 10000);
                } else {
                    Log.d("WebToApk", "Reloading page on pull-to-refresh");
                    webview.reload();
                }
            });
    }

    // JS API
    private class WebAppInterface {
        private Context context;

        WebAppInterface(Context context) {
            this.context = context;
        }

        // Blob export: ask for a destination, then receive the bytes in chunks
        @JavascriptInterface
        public void pickSaveTarget(String filename, String mime) {
            uiHandler.post(() -> saveTarget.launch(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime)
                    .putExtra(Intent.EXTRA_TITLE, filename)
            ));
        }

        // Returns false when the bytes did not reach the destination. The write is the only place
        // that knows — the page's `fetch` succeeded and its blob is complete — so swallowing that
        // here made a full disk or a revoked document look like a successful save.
        @JavascriptInterface
        public boolean appendChunk(String base64) {
            OutputStream stream = saveStream;   // read once: finishSave may clear it meanwhile
            if (stream == null) {
                saveFailed = true;
                return false;
            }
            try {
                stream.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                saveFailed = true;
                Log.e("WebToApk", "Chunk write failed", e);
                return false;
            }
        }

        @JavascriptInterface
        public void finishSave(boolean ok) {
            boolean failed = !ok || saveFailed;   // a page that ignored the answers above is told too
            saveFailed = false;
            closeSaveStream();
            if (failed) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_LONG).show());
            }
        }

        /* Downloads this app has started. DownloadManager carries them on its own, so this
           stays the source of truth even when the events above are missed */
        @JavascriptInterface
        public String getDownloads() {
            JSONArray downloads = new JSONArray();
            // The filter belongs in the query: on API < 29 an unfiltered one returns every download
            // on the device, and setFilterById is exact as well as faster than the scan.
            // One atomic snapshot: ownDownloads is a concurrent set that the completion receiver
            // (main thread) and this bridge method (JavaBridge thread) both add to, so a size read
            // followed by iteration could fill an array that had grown in between.
            long[] ids = ownDownloads.stream().mapToLong(Long::longValue).toArray();
            if (ids.length == 0) return downloads.toString();
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(ids))) {
                while (cursor != null && cursor.moveToNext()) {
                    long downloadId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                    downloads.put(new JSONObject()
                        .put("id", downloadId)
                        .put("url", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)))
                        .put("filename", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)))
                        .put("mime", cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE)))
                        .put("bytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                        .put("total", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                        .put("status", downloadStatus(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))));
                }
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot query downloads", e);
            }
            return downloads.toString();
        }

        @JavascriptInterface
        public void openDownloadedFile(String id) {
            handOffDownload(id, Intent.ACTION_VIEW);
        }

        @JavascriptInterface
        public void shareDownloadedFile(String id) {
            handOffDownload(id, Intent.ACTION_SEND);
        }

        /**
         * Deletes a download this app started, together with the file it wrote into Downloads/.
         * No permission and no dialog are involved, which is why the id has to be one of ours: on
         * API < 29 DownloadManager is not scoped per app, so an arbitrary id could be someone
         * else's download.
         */
        @JavascriptInterface
        public boolean removeDownload(String id) {
            long downloadId;
            try {
                downloadId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                return false;
            }
            if (!ownDownloads.contains(downloadId)) {
                Log.w("WebToApk", "Not removing a download this app did not start: " + id);
                return false;
            }
            try {
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                boolean removed = manager.remove(downloadId) > 0;
                if (removed) {
                    ownDownloads.remove(downloadId);
                    saveOwnDownloads();
                }
                return removed;
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot remove download " + id, e);
                return false;
            }
        }

        /**
         * A request for the page, off the JavaBridge thread: the answer arrives later as
         * window.__webtoapkHttpDone(id, json). HttpURLConnection never touches the WebView's cookie
         * jar, so this is cookie-less by construction — that is the point: it is the CORS bypass
         * behind GM_xmlhttpRequest. localhost is refused here exactly like in shouldInterceptRequest.
         */
        @JavascriptInterface
        public void httpRequest(final String id, final String method, final String url,
                                final String headersJson, final String body) {
            Uri target = Uri.parse(url == null ? "" : url);
            String scheme = target.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                answer(id, errorJson("only http and https are allowed"));
                return;
            }
            new Thread(() -> {
                try {
                    // The lookup this may need is one the request itself would do anyway
                    if (blockLocalhostRequests && isLocalhostTarget(target.toString())) {
                        answer(id, errorJson("localhost is blocked"));
                        return;
                    }
                    answer(id, performRequest(id, method, target.toString(), headersJson, body));
                } catch (Exception e) {
                    Log.e("WebToApk", "httpRequest failed: " + url, e);
                    answer(id, errorJson(String.valueOf(e)));
                }
            }, "webtoapk-http").start();
        }

        /** Cancels a request; a late answer is dropped by the page's own bookkeeping */
        @JavascriptInterface
        public void httpAbort(String id) {
            HttpURLConnection connection = httpRequests.remove(id);
            if (connection != null) connection.disconnect();
        }

        /* Hand the answer back on the main thread: evaluateJavascript may not run on another one */
        private void answer(String id, String json) {
            uiHandler.post(() -> {
                if (webview != null) {
                    webview.evaluateJavascript("window.__webtoapkHttpDone&&window.__webtoapkHttpDone("
                        + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")", null);
                }
            });
        }

        /* The blocking part, on the request's own thread */
        private String performRequest(String id, String method, String url, String headersJson, String body) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            httpRequests.put(id, connection);
            try {
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod(method == null || method.isEmpty() ? "GET" : method);
                if (headersJson != null && !headersJson.isEmpty()) {
                    JSONObject headers = new JSONObject(headersJson);
                    for (Iterator<String> names = headers.keys(); names.hasNext(); ) {
                        String name = names.next();
                        connection.setRequestProperty(name, headers.getString(name));
                    }
                }
                if (body != null && !body.isEmpty()) {
                    connection.setDoOutput(true);
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(body.getBytes("UTF-8"));
                    }
                }

                int status = connection.getResponseCode();
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        headers.put(entry.getKey(), String.join(", ", entry.getValue()));
                    }
                }
                // 4xx/5xx throw on getInputStream: their body lives on the error stream
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                StringBuilder text = new StringBuilder();
                if (stream != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"))) {
                        char[] buffer = new char[8192];
                        int read;
                        while ((read = reader.read(buffer)) > 0) {
                            if (text.length() + read > MAX_HTTP_BODY_CHARS) {
                                text.append(buffer, 0, Math.max(0, MAX_HTTP_BODY_CHARS - text.length()));
                                text.append("\n[body truncated]");
                                break;
                            }
                            text.append(buffer, 0, read);
                        }
                    }
                }
                return new JSONObject()
                    .put("status", status)
                    .put("headers", headers)
                    .put("body", text.toString())
                    .toString();
            } finally {
                httpRequests.remove(id);
                connection.disconnect();
            }
        }

        @JavascriptInterface
        public void showShortToast(String message) {
            uiHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void showLongToast(String message) {
            uiHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public boolean hasNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            // On older versions, permission is implicitly granted at install time.
            return true;
        }

        @JavascriptInterface
        public void requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // We need to run this on the main thread
                uiHandler.post(() -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        // Remember the ask: "never asked" and "denied for good" both report no
                        // rationale, so this flag is the only way to tell them apart
                        context.getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE).edit()
                                .putBoolean(NOTIFICATION_ASKED_KEY, true).apply();
                        ActivityCompat.requestPermissions((Activity) context, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
                        Log.d("WebToApk", "Requesting notification permission.");
                    } else {
                        Log.d("WebToApk", "Notification permission already granted.");
                    }
                });
            }
        }

        @JavascriptInterface
        public void showNotification(String title, String message) {
            // This check is crucial for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("WebToApk", "Notification permission not granted. Cannot show notification.");
                    // Optionally, inform the user via a Toast that permission is needed.
                    uiHandler.post(() -> Toast.makeText(context, "Notification permission is required", Toast.LENGTH_LONG).show());
                    return;
                }
            }

            // Create an intent to open the app when the notification is tapped
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, MediaPlaybackService.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat) // monochrome: the platform masks it (ic_stat.xml)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent) // Set the intent that will fire when the user taps the notification
                .setAutoCancel(true); // Automatically removes the notification when the user taps it

            NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

            // Using system time is a simple way to get a unique ID for each notification
            notificationManager.notify((int) System.currentTimeMillis(), builder.build());
            Log.d("WebToApk", "Showing notification: " + title);
        }

        @JavascriptInterface
        public void share(String title, String text, String url) {
            Log.d("WebToApk", "Share: " + title + " :: " +  text +" " + url);
            // Make Intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            String shareBody = (text != null ? text : "")
                                + ((url != null && !url.isEmpty()) ? "\n" + url : "");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, title);
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareBody);

            // Show chooser
            // `context` is the activity, so a chooser needs no FLAG_ACTIVITY_NEW_TASK
            context.startActivity(Intent.createChooser(shareIntent, title == null ? "Share" : title));
        }


        /**
         * Returns the state of the notification permission for the shim.
         * "granted", "denied", or "prompt".
         */
        @JavascriptInterface
        public String getNotificationPermissionState() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    return "granted";
                }
                if (ActivityCompat.shouldShowRequestPermissionRationale((Activity) context, Manifest.permission.POST_NOTIFICATIONS)) {
                    // Denied once, but the system will still show the dialog: the web "prompt"
                    return "prompt";
                }
                // No rationale means either never asked (the web "prompt") or asked and refused for
                // good. Reporting "prompt" for both made the page ask forever; the flag set in
                // requestNotificationPermission() tells the two apart.
                boolean asked = context.getSharedPreferences(NOTIFICATION_PREFS, MODE_PRIVATE)
                        .getBoolean(NOTIFICATION_ASKED_KEY, false);
                return asked ? "denied" : "prompt";
            }
            // On older versions, permission is granted at install time.
            return "granted";
        }

        /* The service stops itself when playback ends, and Android 8+ refuses to start a background
           service from the background: a throw on this thread would take the whole process down */
        private void sendToMediaService(Intent intent) {
            try {
                context.startService(intent);
            } catch (Exception e) {
                Log.e("WebToApk", "Cannot reach the media service", e);
            }
        }

        @JavascriptInterface
        public void updateMediaMetadata(String title, String artist, String album, @Nullable String artworkUrl) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_METADATA);
            intent.putExtra("title", title);
            intent.putExtra("artist", artist);
            intent.putExtra("album", album);
            intent.putExtra("artworkUrl", artworkUrl);
            sendToMediaService(intent);
        }

        @JavascriptInterface
        public void updateMediaPlaybackState(String state) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_STATE);
            intent.putExtra("state", state);
            sendToMediaService(intent);
        }

        @JavascriptInterface
        public void setMediaActionHandlers(String[] actions) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_SET_HANDLERS);
            intent.putExtra("actions", actions);
            sendToMediaService(intent);
        }

        @JavascriptInterface
        public void updateMediaPositionState(double duration, double playbackRate, double position) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_POSITION);
            intent.putExtra("duration", duration);
            intent.putExtra("playbackRate", playbackRate);
            intent.putExtra("position", position);
            sendToMediaService(intent);
        }

        @JavascriptInterface
        public void clearAppCache() {
            uiHandler.post(() -> {
                if (webview != null) {
                    webview.clearCache(true);
                    Log.d("WebToApk", "Cache cleared via WebToAPK.clearAppCache() from js");
                }
            });
        }

        @JavascriptInterface
        public void setPullToRefreshEnabled(final boolean enabled) {
            uiHandler.post(() -> {
                if (swipeRefresh != null) {
                    swipeRefresh.setEnabled(enabled);
                    if (!enabled) {
                        swipeRefresh.setRefreshing(false);
                        uiHandler.removeCallbacks(refreshTimeout);
                    }
                }
            });
        }

        @JavascriptInterface
        public void setPullToRefreshRefreshing(final boolean refreshing) {
            uiHandler.post(() -> {
                if (swipeRefresh != null) {
                    uiHandler.removeCallbacks(refreshTimeout);
                    swipeRefresh.setRefreshing(refreshing);
                }
            });
        }
    }

}
