package com.myexample.webtoapk;

import android.content.DialogInterface;
import android.net.http.SslError;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
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
import java.io.ByteArrayInputStream;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.webkit.DownloadListener;
import android.app.DownloadManager;
import android.webkit.URLUtil;
import android.os.Environment;
import static android.content.Context.DOWNLOAD_SERVICE;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.unifiedpush.android.connector.UnifiedPush;
import static org.unifiedpush.android.connector.ConstantsKt.INSTANCE_DEFAULT;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.annotation.NonNull;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.graphics.Color;
import androidx.core.graphics.Insets;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

public class MainActivity extends AppCompatActivity {
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final String NOTIFICATION_CHANNEL_ID = "web_app_notifications";
    private static final String NOTIFICATION_CHANNEL_NAME = "Web App Notifications";

    private WebView webview;
    private UserScriptManager userScriptManager;
    private ProgressBar spinner;
    private Animation fadeInAnimation;
    private View mainLayout;
    private View errorLayout;
    private ViewGroup parentLayout;
    private boolean errorOccurred = false; // For WebView after tryAgain
    private ValueCallback<Uri[]> mFilePathCallback;       // Image upload
    private ActivityResultLauncher<Intent> fileChooserLauncher; // Image upload
    private WebAppInterface webAppInterface;
    private BroadcastReceiver unifiedPushEndpointReceiver;
    private BroadcastReceiver mediaActionReceiver;

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
    boolean DebugWebView = false;

    boolean geolocationEnabled = false;

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

        // Create the NotificationChannel, but only on API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, importance);
            channel.setDescription("Channel for web app notifications");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
            Log.d("WebToApk", "Notification channel created.");
        }

        if (forceLandscapeMode) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }

        setContentView(R.layout.activity_main);
        mainLayout = findViewById(android.R.id.content);
        parentLayout = (ViewGroup) mainLayout.getParent();
        userScriptManager = new UserScriptManager(this, mainURL);

        // Handle intent
        Intent intent = getIntent();
        String action = intent.getAction();
        Uri data = intent.getData();
        Log.d("WebToApk", "Action: " + action);
        Log.d("WebToApk", "Data: " + data);
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            mainURL = data.toString();
        }

        fadeInAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_in);

        webview = findViewById(R.id.webView);
        spinner = findViewById(R.id.progressBar1);
        webview.setWebViewClient(new CustomWebViewClient());
        webview.setWebChromeClient(new CustomWebChrome());
        webAppInterface = new WebAppInterface(this);
        webview.addJavascriptInterface(webAppInterface, "WebToApk");

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
        webview.setWebContentsDebuggingEnabled(DebugWebView);

        if (!userAgent.isEmpty()) {
            webSettings.setUserAgentString(userAgent);
        }

        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webview.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        CookieManager cookieManager = CookieManager.getInstance();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true);
        cookieManager.setCookie(mainURL, cookies);
        cookieManager.flush();

        // Request geo access only if have `android.permission.ACCESS_FINE_LOCATION`
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        }

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

        // File downloading support
        webview.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
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
                    downloadManager.enqueue(request);
                    Toast.makeText(getApplicationContext(), R.string.download_started, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), R.string.download_failed, Toast.LENGTH_LONG).show();
                    Log.e("WebToApk", "Failed to start download", e);
                }
            }
        });


        // Broadcast receiver to get the endpoint from the PushServiceImpl
        unifiedPushEndpointReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Now we receive all parts of the subscription
                String endpoint = intent.getStringExtra("endpoint");
                String p256dh = intent.getStringExtra("p256dh");
                String auth = intent.getStringExtra("auth");

                Log.d("WebToApk", "Received new UnifiedPush data. Endpoint: " + endpoint);

                // Instead of a simple function call, we now create the full subscription JSON
                // and pass it to a special function in our shim that will resolve the 'subscribe()' promise.
                if (endpoint != null && p256dh != null && auth != null && webview != null) {
                    try {
                        JSONObject keys = new JSONObject();
                        // Use the real keys received from the distributor
                        keys.put("p256dh", p256dh);
                        keys.put("auth", auth);

                        JSONObject subscription = new JSONObject();
                        subscription.put("endpoint", endpoint);
                        subscription.put("expirationTime", JSONObject.NULL);
                        subscription.put("keys", keys);

                        String subscriptionJson = subscription.toString();

                        webview.post(() -> {
                            // This JS function is defined in our new shim
                            String js = "if (typeof window.__shim_onNewEndpoint === 'function') { window.__shim_onNewEndpoint('" + subscriptionJson.replace("'", "\\'") + "'); }";
                            webview.evaluateJavascript(js, null);
                        });

                    } catch (JSONException e) {
                         Log.e("WebToApk", "Failed to create subscription JSON for shim", e);
                    }
                }
            }
        };
        // Register the receiver with compatibility for different Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(unifiedPushEndpointReceiver, new IntentFilter("com.myexample.webtoapk.NEW_ENDPOINT"), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unifiedPushEndpointReceiver, new IntentFilter("com.myexample.webtoapk.NEW_ENDPOINT"));
        }

        if (edgeToEdge) {
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

                return WindowInsetsCompat.CONSUMED;
            });
        }

        if (savedInstanceState != null) {
            // Restore the state of the WebView from the saved bundle.
            webview.restoreState(savedInstanceState);
        } else {
            // It's a fresh launch. Load the main URL.
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

    private void registerForUnifiedPush(final String vapidPublicKey) {
        if (vapidPublicKey == null || vapidPublicKey.isEmpty()) {
            Log.e("WebToApk", "VAPID public key is null or empty. Cannot register for push.");
            return;
        }

        UnifiedPush.tryUseCurrentOrDefaultDistributor(this, new Function1<Boolean, Unit>() {
            @Override
            public Unit invoke(Boolean success) {
                if (success) {
                    Log.d("WebToApk", "UnifiedPush distributor found, registering...");
                    UnifiedPush.register(
                        MainActivity.this,
                        INSTANCE_DEFAULT,
                        null,
                        vapidPublicKey
                    );
                } else {
                    Log.w("WebToApk", "No UnifiedPush distributor found or user cancelled.");

                    // We must run UI and WebView operations on the main thread
                    new Handler(Looper.getMainLooper()).post(() -> {
                        // Show an informative dialog to the user
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.push_distributor_required_title)
                            .setMessage(R.string.push_distributor_required_message)
                            .setPositiveButton(R.string.learn_more, (dialog, which) -> {
                                // Open the UnifiedPush website for users to find distributors
                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://unifiedpush.org/users/distributors/"));
                                startActivity(browserIntent);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    });
                }
                return Unit.INSTANCE;
            }
        });
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (unifiedPushEndpointReceiver != null) {
            unregisterReceiver(unifiedPushEndpointReceiver);
        }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediaActionReceiver);
        Intent intent = new Intent(this, MediaPlaybackService.class);
        stopService(intent);
    }

    // Save the state of the WebView (current URL, history, scroll position) during OOM kill
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webview.saveState(outState);
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
                src = src.substring(8);
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
            // Automatically grant permission for geolocation requests
            callback.invoke(origin, true, false);
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
            intent.setType("image/*");

            // Проверяем параметры файлового диалога
            String[] acceptTypes = fileChooserParams.getAcceptTypes();
            if (acceptTypes.length > 0 && acceptTypes[0] != null && !acceptTypes[0].isEmpty()) {
                if (acceptTypes[0].contains("image")) {
                    intent.setType("image/*");
                } else {
                    intent.setType("*/*");
                }
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

                    // Get main domain from mainURL to verify the host
                    String mainDomain = "";
                    try {
                        mainDomain = Uri.parse(MainActivity.this.mainURL).getHost();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    boolean domainIsValid = false;
                    if (mainDomain != null && !mainDomain.isEmpty() && host != null && !host.isEmpty()) {
                        if (MainActivity.this.allowSubdomains) {
                            // Allow if the host ends with mainDomain (covers subdomains)
                            domainIsValid = host.endsWith(mainDomain) || mainDomain.endsWith(host);
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
                isInternalLink = urlDomain.endsWith(mainDomain) || mainDomain.endsWith(urlDomain);
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

            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView webview, String url, Bitmap favicon) {
            super.onPageStarted(webview, url, favicon);
            userScriptManager.injectScripts(webview, url);
        }

        // Animation on app open
        @Override
        public void onPageFinished(WebView webview, String url) {
            // Без флага errorOccurred у нас будет видно ошибку webview пока идёт анимация после tryAgain
            if (!errorOccurred) {
                Log.d("WebToApk","Current page: " + url);
                spinner.setVisibility(View.GONE);
                if (!webview.isShown()) {
                    webview.startAnimation(fadeInAnimation);
                    webview.setVisibility(View.VISIBLE);
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
                        errorLayout = getLayoutInflater().inflate(R.layout.error, parentLayout, false);
                        parentLayout.removeView(mainLayout);
                        parentLayout.addView(errorLayout);
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

    }

    boolean doubleBackToExitPressedOnce = false;

    @Override
    public void onBackPressed() {
        if (webview.canGoBack()) {
            webview.goBack();
        } else {
            if (doubleBackToExitPressedOnce || !requireDoubleBackToExit) {
                finish();
                return;
            }
            this.doubleBackToExitPressedOnce = true;
            Toast.makeText(this, R.string.exit_app, Toast.LENGTH_SHORT).show();

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    doubleBackToExitPressedOnce = false;
                }
            }, 2000);
        }
    }

    /* Retry Loading the page */
    public void tryAgain(View v) {
        parentLayout.removeView(errorLayout);
        parentLayout.addView(mainLayout);
        webview.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        errorOccurred = false;
        webview.reload();
    }

    // JS API
    private class WebAppInterface {
        private Context context;

        WebAppInterface(Context context) {
            this.context = context;
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
         * Called by the JS shim to trigger the UnifiedPush registration flow.
         * @param vapidPublicKey The Base64 URL-encoded VAPID public key from the web app.
         */
        @JavascriptInterface
        public void unifiedPushSubscribe(String vapidPublicKey) {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.d("WebToApk", "JS shim triggered UnifiedPush registration.");
                MainActivity.this.registerForUnifiedPush(vapidPublicKey);
            });
        }

        /**
         * Called by the JS shim to unsubscribe from a push subscription.
         * The shim doesn't manage multiple instances, so we use the default.
         */
        @JavascriptInterface
        public void unifiedPushUnregister() {
            Log.d("WebToApk", "JS shim triggered UnifiedPush un-registration for default instance.");
            UnifiedPush.unregister(context, INSTANCE_DEFAULT);
        }


        /**
         * Returns the current subscription object as a JSON string for the shim.
         * This includes the endpoint and dummy keys expected by the Push API.
         * Returns an empty string if not subscribed.
         */
        @JavascriptInterface
        public String getUnifiedPushSubscriptionJson() {
            SharedPreferences prefs = context.getSharedPreferences("unifiedpush", Context.MODE_PRIVATE);
            String endpoint = prefs.getString("endpoint_" + INSTANCE_DEFAULT, null);
            String p256dh = prefs.getString("p256dh_" + INSTANCE_DEFAULT, null);
            String auth = prefs.getString("auth_" + INSTANCE_DEFAULT, null);

            if (endpoint == null || endpoint.isEmpty() || p256dh == null || auth == null) {
                return "";
            }

            try {
                // We construct a JSON object that mimics the standard PushSubscription.toJSON() output.
                JSONObject keys = new JSONObject();
                keys.put("p256dh", p256dh);
                keys.put("auth", auth);

                JSONObject subscription = new JSONObject();
                subscription.put("endpoint", endpoint);
                subscription.put("expirationTime", JSONObject.NULL);
                subscription.put("keys", keys);

                return subscription.toString();
            } catch (JSONException e) {
                Log.e("WebToApk", "Failed to create subscription JSON", e);
                return "";
            }
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

    }

}
