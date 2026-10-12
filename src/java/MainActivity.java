package com.myexample.webtoapk;

import android.content.DialogInterface;
import android.net.http.SslError;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.webkit.WebViewAssetLoader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.SslErrorHandler;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.os.Handler;
import android.webkit.WebSettings;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLConnection;
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
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import android.graphics.Color;
import androidx.core.graphics.Insets;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.webkit.PermissionRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;


public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int MEDIA_PERMISSION_REQUEST_CODE = 1001;
    private static final int DOWNLOAD_PERMISSION_REQUEST_CODE = 3;
    // A download that waits for WRITE_EXTERNAL_STORAGE: {url, userAgent, contentDisposition, mimetype}
    private String[] pendingDownload;
    private static final String NOTIFICATION_CHANNEL_ID = "web_app_notifications";
    private static final String NOTIFICATION_CHANNEL_NAME = "Web App Notifications";

    private WebView webview;
    private UserScriptManager userScriptManager;
    private ProgressBar spinner;
    private SwipeRefreshLayout swipeRefresh;
    private View mainLayout;
    private View errorLayout;
    private ViewGroup parentLayout;
    private boolean errorOccurred = false; // For WebView after tryAgain
    private ValueCallback<Uri[]> mFilePathCallback;       // Image upload
    private ActivityResultLauncher<Intent> fileChooserLauncher; // Image upload
    private ActivityResultLauncher<Intent> saveTarget; // Blob export destination (SAF)
    private OutputStream saveStream;
    private BroadcastReceiver downloadCompleteReceiver; // Reports finished downloads to the page
    private WebAppInterface webAppInterface;
    private BroadcastReceiver mediaActionReceiver;
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
    String userAgent = "";
    boolean blockLocalhostRequests = true;
    boolean JSEnabled = true;
    boolean JSCanOpenWindowsAutomatically = true;
    boolean DomStorageEnabled = true;
    boolean DatabaseEnabled = true;
    boolean MediaPlaybackRequiresUserGesture = true;
    boolean SavePassword = true;
    boolean AllowFileAccess = true;
    boolean AllowFileAccessFromFileURLs = true;
    boolean showDetailsOnErrorScreen = false;
    boolean forceLandscapeMode = false;
    boolean edgeToEdge = false;
    boolean forceDarkTheme = false;
    boolean allowMixedContent = false;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (forceDarkTheme) {
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

        // Create the NotificationChannel (minSdk 26)
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, importance);
        channel.setDescription("Channel for web app notifications");
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
        Log.d("WebToApk", "Notification channel created.");

        if (forceLandscapeMode) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        setContentView(R.layout.activity_main);
        mainLayout = findViewById(android.R.id.content);
        parentLayout = (ViewGroup) mainLayout.getParent();
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
        webview.setWebChromeClient(new CustomWebChrome());
        webAppInterface = new WebAppInterface(this);
        webview.addJavascriptInterface(webAppInterface, "WebToApk");
        registerBackCallback();

        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(JSEnabled);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(JSCanOpenWindowsAutomatically);
        webSettings.setGeolocationEnabled(geolocationEnabled);
        webSettings.setDomStorageEnabled(DomStorageEnabled);
        webSettings.setDatabaseEnabled(DatabaseEnabled);
        webSettings.setMediaPlaybackRequiresUserGesture(MediaPlaybackRequiresUserGesture);
        webSettings.setSavePassword(SavePassword);
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
                    Intent intentData = result.getData(); // Переименовали с data на intentData

                    // Обработка множественного выбора
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
                    saveStream = getContentResolver().openOutputStream(result.getData().getData());
                    webview.evaluateJavascript("__webtoapkSendBlob()", null);
                } catch (Exception e) {
                    Log.e("WebToApk", "Cannot open save target", e);
                    Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show();
                }
            }
        );

        // File downloading support
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

        // API 35+ forces every app edge-to-edge, so the listener is installed even for a config
        // that never asked for it: that config keeps its old look by padding the content out of
        // the bars itself. Below 35 the decor already consumed the insets, so they arrive as zero.
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout, (v, windowInsets) -> {
            // Get the insets for system bars in hardware pixels.
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());

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
            String js = String.format(java.util.Locale.US,
                "document.documentElement.style.setProperty('--safe-area-inset-top', '%.2fpx');" +
                "document.documentElement.style.setProperty('--safe-area-inset-bottom', '%.2fpx');" +
                "document.documentElement.style.setProperty('--safe-area-inset-left', '%.2fpx');" +
                "document.documentElement.style.setProperty('--safe-area-inset-right', '%.2fpx');" +
                "document.dispatchEvent(new CustomEvent('WebToApkInsetsApplied'));",
                top, bottom, left, right
            );
            webview.evaluateJavascript(js, null);
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
                return WindowInsetsCompat.CONSUMED;
            }
            // Leave the insets unconsumed so the IME keeps resizing the window as before.
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            return windowInsets;
        });

        // TODO check https://stackoverflow.com/questions/18479519/how-to-save-restore-webview-state
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

        mediaActionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && MediaPlaybackService.BROADCAST_MEDIA_ACTION.equals(intent.getAction())) {
                    String action = intent.getStringExtra(MediaPlaybackService.EXTRA_MEDIA_ACTION);
                    if (action != null) {
                        executeMediaActionInWebView(action);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(mediaActionReceiver, new IntentFilter(MediaPlaybackService.BROADCAST_MEDIA_ACTION));
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

    /* Report a download event to the page, if it defines the matching WebToApk hook */
    private void notifyPage(String hook, String argument) {
        if (webview == null) return;
        webview.evaluateJavascript(
            "(function(){try{var f=window.WebToApk&&window.WebToApk." + hook + ";" +
            "if(typeof f==='function')f(" + argument + ");}catch(e){}})()", null);
    }

    /* Hand a finished download over to another app. getUriForDownloadedFile only ever grants
       access to downloads of ours, so no storage permission is involved */
    private void handOffDownload(String id, String action) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                long downloadId = Long.parseLong(id);
                Uri uri = manager.getUriForDownloadedFile(downloadId);
                if (uri == null) throw new IllegalStateException("download is not finished: " + id);
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
        super.onDestroy();
        unregisterReceiver(downloadCompleteReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediaActionReceiver);
        Intent intent = new Intent(this, MediaPlaybackService.class);
        stopService(intent);
    }

    // The URL carried by a VIEW intent, or null when there is none
    private String urlFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri data = intent.getData();
        return data == null ? null : data.toString();
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

    // TODO idk what do with this. rly need?
    // @Override
    // protected void onPause() {
    //     super.onPause();
    //     Log.d("WebToApk", "onPause");
    //     if (webview != null) {
    //         Log.d("WebToApk", "onPause 2");
    //         webview.onPause();
    //     }
    // }
    // @Override
    // protected void onResume() {
    //     super.onResume();
    //     Log.d("WebToApk", "onResume");
    //     if (webview != null) {
    //         Log.d("WebToApk", "onResume 2");
    //         webview.onResume();
    //     }
    // }

    /* DownloadManager writes into the public Downloads directory, so before API 29 the enqueue is
       preceded by a WRITE_EXTERNAL_STORAGE round-trip (see onRequestPermissionsResult) */
    private void startDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        DownloadManager downloadManager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

        // Create a request for the download
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimetype);

        // Set cookies for the download request, it's important for authorized downloads
        String cookies = CookieManager.getInstance().getCookie(url);
        request.addRequestHeader("cookie", cookies);
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
            Toast.makeText(getApplicationContext(), R.string.download_started, Toast.LENGTH_LONG).show();
            // Tell the page which download this is, so it can ask about its progress
            notifyPage("onDownloadStarted", Long.toString(id));
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), R.string.download_failed, Toast.LENGTH_LONG).show();
            Log.e("WebToApk", "Failed to start download", e);
        }
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
                if (isGranted) {
                    currentPermissionRequest.grant(currentPermissionRequest.getResources());
                } else {
                    currentPermissionRequest.deny();
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
                if (isGranted) {
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
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.confirm();
                    }
                })
                .setCancelable(false)
                .create()
                .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.confirm();
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.cancel();
                    }
                })
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
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.confirm(input.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        result.cancel();
                    }
                })
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

            if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // Permission missing. Save callback and ask User.
                geoCallback = callback;
                geoOrigin = origin;
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
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

        @Override
        public void onHideCustomView() {
            ((FrameLayout)getWindow().getDecorView()).removeView(mCustomView);
            mCustomView = null;
            getWindow().getDecorView().setSystemUiVisibility(mOriginalSystemUiVisibility);
            setRequestedOrientation(mOriginalOrientation);
            mCustomViewCallback.onCustomViewHidden();
            mCustomViewCallback = null;
        }

        @Override
        public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
            if (mCustomView != null) {
                onHideCustomView();
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

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // A page that does not say what it accepts must not be limited to images
            intent.setType("*/*");

            // Проверяем параметры файлового диалога
            String[] acceptTypes = fileChooserParams.getAcceptTypes();
            if (acceptTypes.length > 0 && acceptTypes[0] != null && acceptTypes[0].contains("image")) {
                intent.setType("image/*");
            }

            // Поддержка множественного выбора
            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }

            Intent chooserIntent = Intent.createChooser(intent, "Выберите файл");

            try {
                fileChooserLauncher.launch(chooserIntent);
            } catch (ActivityNotFoundException e) {
                mFilePathCallback = null;
                Toast.makeText(MainActivity.this, "Невозможно открыть файловый менеджер", Toast.LENGTH_LONG).show();
                return false;
            }

            return true;
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            // Check if feature is enabled in config
            // If the site asks for video but cameraEnabled is false -> deny immediately
            for (String resource : request.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !cameraEnabled) {
                    request.deny();
                    return;
                }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !microphoneEnabled) {
                    request.deny();
                    return;
                }
            }

            // Calculate which Android permissions correspond to the requested WebView resources
            List<String> permissionsNeeded = new ArrayList<>();
            for (String resource : request.getResources()) {
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
                request.grant(request.getResources());
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
            String failingUrl = error.getUrl();
            String currentUrl = view.getUrl();
            boolean isMainPage = (currentUrl != null && failingUrl != null && failingUrl.equals(currentUrl));
            if (!isMainPage) {
                handler.cancel();
                return;
            }

            final AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setMessage(R.string.notification_error_ssl_cert_invalid);

            builder.setPositiveButton("continue", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    handler.proceed();
                }
            });

            builder.setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    handler.cancel();
                }
            });
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

                    if (domainIsValid) {
                        // Credentials and domain are valid; proceed automatically
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

            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Authentication Required")
                .setView(dialogView)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Retrieve user input and proceed with HTTP authentication
                        String user = usernameInput.getText().toString();
                        String pass = passwordInput.getText().toString();
                        handler.proceed(user, pass);
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        handler.cancel();
                    }
                })
                .show();
        }

        // Check for external link
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();

            // Check for non-standard URL scheme (external app)
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                if (allowOpenMobileApp) {
                    if (confirmOpenExternalApp) {
                        // Show confirmation dialog before opening external app
                        new AlertDialog.Builder(view.getContext())
                            .setTitle(R.string.external_link)
                            .setMessage(R.string.open_in_external_app)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        // Try to launch an external Intent for the custom scheme
                                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                        view.getContext().startActivity(intent);
                                    } catch (ActivityNotFoundException e) {
                                        Log.e("WebToApk", "\033[0;31mNo application can handle this URL:\033[0m " + url, e);
                                    }
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    } else {
                        // Open directly without confirmation
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            view.getContext().startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Log.e("WebToApk", "\033[0;31mNo application can handle this URL:\033[0m " + url);
                        }
                    }
                } else {
                    Log.d("WebToApk", "Opening URLs in external app is disabled: " + url);
                }
                return true; // Consume the event so that the WebView does not load this URL
            }

            // Check if the URL is internal by comparing the host/domain
            String urlDomain = request.getUrl().getHost();
            String mainDomain = Uri.parse(mainURL).getHost();

            // Safety check for malformed URLs
            if (urlDomain == null || mainDomain == null) {
                return handleExternalLink(url, view);
            }

            // Check if domains match (including subdomains if enabled)
            boolean isInternalLink;
            if (allowSubdomains) {
                // Allow:
                //   youtube.com -> m.youtube.com
                //   m.youtube.com -> youtube.com
                isInternalLink = sameSite(urlDomain, mainDomain);
            } else {
                isInternalLink = urlDomain.equals(mainDomain);
            }

            if (isInternalLink) {
                // Internal link: let the WebView load it normally
                return false;
            }

            return handleExternalLink(url, view);
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
                        .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                view.getContext().startActivity(intent);
                            }
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .show();
                    return true;
                } else {
                    Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    view.getContext().startActivity(intent);
                    return true;
                }
            }
            // Open in WebView
            Log.d("WebToApk", "\033[1;34mExternal link:\033[0m '" + url);
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String host = request.getUrl().getHost();

            if (blockLocalhostRequests && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host) || "0:0:0:0:0:0:0:1".equals(host))) {
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
                        errorOccurred = true;
                        // Reuse the screen: a second failure used to stack another copy on top of it
                        if (errorLayout == null) {
                            errorLayout = getLayoutInflater().inflate(R.layout.error, parentLayout, false);
                            parentLayout.removeView(mainLayout);
                            parentLayout.addView(errorLayout);
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
            finish();
            startActivity(getIntent());
            return true;
        }

    }

    boolean doubleBackToExitPressedOnce = false;

    // Android 16 does not call onBackPressed for apps targeting 36 (predictive back is on by
    // default), so the same logic lives in a back callback, which every release dispatches.
    private void registerBackCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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

                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            doubleBackToExitPressedOnce = false;
                        }
                    }, 2000);
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

    private static String mimeTypeOf(String path) {
        String mime = URLConnection.guessContentTypeFromName(path);
        return mime != null ? mime : "text/plain";
    }

    /* Retry Loading the page */
    public void tryAgain(View v) {
        parentLayout.removeView(errorLayout);
        errorLayout = null;
        parentLayout.addView(mainLayout);
        webview.setAlpha(0f);
        spinner.setVisibility(View.VISIBLE);
        errorOccurred = false;
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
            new Handler(Looper.getMainLooper()).post(() -> saveTarget.launch(
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime.isEmpty() ? "application/octet-stream" : mime)
                    .putExtra(Intent.EXTRA_TITLE, filename)
            ));
        }

        @JavascriptInterface
        public void appendChunk(String base64) {
            try {
                saveStream.write(Base64.decode(base64, Base64.DEFAULT));
            } catch (Exception e) {
                Log.e("WebToApk", "Chunk write failed", e);
            }
        }

        @JavascriptInterface
        public void finishSave(boolean ok) {
            try {
                saveStream.close();
            } catch (Exception ignored) {}
            saveStream = null;
            if (!ok) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.download_failed, Toast.LENGTH_LONG).show());
            }
        }

        /* Downloads this app has started. DownloadManager carries them on its own, so this
           stays the source of truth even when the events above are missed */
        @JavascriptInterface
        public String getDownloads() {
            JSONArray downloads = new JSONArray();
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            try (Cursor cursor = manager.query(new DownloadManager.Query())) {
                while (cursor != null && cursor.moveToNext()) {
                    downloads.put(new JSONObject()
                        .put("id", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)))
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

        @JavascriptInterface
        public void showShortToast(String message) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void showLongToast(String message) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                }
            });
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
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Notification permission is required", Toast.LENGTH_LONG).show());
                    return;
                }
            }

            // Create an intent to open the app when the notification is tapped
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher) // Use a default launcher icon
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
            context.startActivity(
                Intent.createChooser(shareIntent, title == null ? "Share" : title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            );
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
                } else {
                    // If we should show a rationale, it means the user has denied it once but can be asked again.
                    // This is the "prompt" state from the web API's perspective.
                    if (ActivityCompat.shouldShowRequestPermissionRationale((Activity) context, Manifest.permission.POST_NOTIFICATIONS)) {
                         return "prompt";
                    }
                    // If no rationale, it's either the first time ("prompt") or permanently denied ("denied").
                    // For simplicity, we can't easily distinguish "denied" from "first-time-prompt" without more state tracking.
                    // Returning "prompt" is a safe default for the shim.
                    return "prompt";
                }
            }
            // On older versions, permission is granted at install time.
            return "granted";
        }

        @JavascriptInterface
        public void updateMediaMetadata(String title, String artist, String album, @Nullable String artworkUrl) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_METADATA);
            intent.putExtra("title", title);
            intent.putExtra("artist", artist);
            intent.putExtra("album", album);
            intent.putExtra("artworkUrl", artworkUrl);
            context.startService(intent);
        }

        @JavascriptInterface
        public void updateMediaPlaybackState(String state) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_STATE);
            intent.putExtra("state", state);
            context.startService(intent);
        }

        @JavascriptInterface
        public void setMediaActionHandlers(String[] actions) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_SET_HANDLERS);
            intent.putExtra("actions", actions);
            context.startService(intent);
        }

        @JavascriptInterface
        public void updateMediaPositionState(double duration, double playbackRate, double position) {
            Intent intent = new Intent(context, MediaPlaybackService.class);
            intent.setAction(MediaPlaybackService.ACTION_UPDATE_POSITION);
            intent.putExtra("duration", duration);
            intent.putExtra("playbackRate", playbackRate);
            intent.putExtra("position", position);
            context.startService(intent);
        }

        @JavascriptInterface
        public void clearAppCache() {
            new Handler(Looper.getMainLooper()).post(() -> {
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
