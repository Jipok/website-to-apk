package com.myexample.webtoapk;

import android.content.DialogInterface;
import android.net.http.SslError;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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


public class MainActivity extends AppCompatActivity {

    private WebView webview;
    private ProgressBar spinner;
    private Animation fadeInAnimation;
    String mainURL = "https://github.com/Jipok";
    boolean enableExternalLinks = true;
    boolean openExternalLinksInBrowser = true;
    boolean requireDoubleBackToExit = true;

    boolean JavaScriptEnabled = true;
    boolean JavaScriptCanOpenWindowsAutomatically = true;
    boolean DomStorageEnabled = true;
    boolean GeolocationEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        fadeInAnimation = AnimationUtils.loadAnimation(this, R.anim.fade_in);

        webview = findViewById(R.id.webView);
        spinner = findViewById(R.id.progressBar1);
        webview.setWebViewClient(new CustomWebViewClient());
        webview.setWebChromeClient(new CustomWebChrome());

        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(JavaScriptEnabled);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(JavaScriptCanOpenWindowsAutomatically);
        webSettings.setGeolocationEnabled(GeolocationEnabled);
        webSettings.setDomStorageEnabled(DomStorageEnabled);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webview.setOverScrollMode(WebView.OVER_SCROLL_NEVER);

        webview.loadUrl(mainURL);
    }

    // Remove "Confirm URL" title from js alert/dialog/confirm
    private class CustomWebChrome extends WebChromeClient {
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

        // Check for external link
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            String urlDomain = Uri.parse(url).getHost();
            String mainDomain = Uri.parse(mainURL).getHost();
            
            if (urlDomain == null || mainDomain == null || !urlDomain.equals(mainDomain)) {
                if (!enableExternalLinks) {
                    return true; // Block external links
                }
                if (openExternalLinksInBrowser) {
                    // Open in system browser
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    view.getContext().startActivity(intent);
                    return true;
                }
            }
            // Open in our WebView
            view.loadUrl(url);
            return false;
        }

        // Animation on app open
        @Override
        public void onPageFinished(WebView view, String url) {
            if (!view.isShown()) {
                spinner.setVisibility(View.GONE);
                view.startAnimation(fadeInAnimation);
                view.setVisibility(View.VISIBLE);
            }
            super.onPageFinished(view, url);
        }

        // Show custom error page
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            mainURL = view.getUrl();
            MainActivity.this.setContentView(R.layout.error);
            super.onReceivedError(view, errorCode, description, failingUrl);
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
        setContentView(R.layout.activity_main);
        webview = findViewById(R.id.webView);
        spinner = findViewById(R.id.progressBar1);
        webview.setWebViewClient(new CustomWebViewClient());

        webview.getSettings().setJavaScriptEnabled(true);
        webview.getSettings().setDomStorageEnabled(true);
        webview.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webview.loadUrl(mainURL);
    }
}
