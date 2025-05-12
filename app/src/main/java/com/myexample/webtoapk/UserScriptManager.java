package com.myexample.webtoapk;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.util.Log;
import org.json.JSONObject;


public class UserScriptManager {
    private List<UserScript> userScripts = new ArrayList<>();
    private Context context;

    private static class UserScript {
        String name;
        String code;
        List<String> matches = new ArrayList<>();
        String runAt = "document-end"; 

        private static final List<String> VALID_RUN_AT = Arrays.asList(
            "document-start",
            "document-body", 
            "document-end",
            "document-idle"
        );

        boolean matchesUrl(String url) {
            // Если matches пустой - скрипт применяется ко всем URL
            if (matches.isEmpty()) {
                return true;
            }
            
            for (String pattern : matches) {
                String regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*");
                if (url.matches(regex)) {
                    return true;
                }
            }
            return false;
        }
    }

    public UserScriptManager(Context context, String mailURL) {
        this.context = context;
        loadUserScripts(mailURL + "/");
    }

    private void loadUserScripts(String mainUrl) {
        try {
            String[] files = context.getAssets().list("userscripts");
            if (files == null) return;
            
            for (String filename : files) {
                if (!filename.endsWith(".js")) continue;
                
                UserScript script = new UserScript();
                script.name = filename;
                
                InputStream input = context.getAssets().open("userscripts/" + filename);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input));
                StringBuilder code = new StringBuilder();
                String line;
                
                boolean inMetadata = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("==UserScript==")) {
                        inMetadata = true;
                    }
                    if (inMetadata) {
                        code.append("//"+line+"\n");
                    } else {
                        code.append(line).append("\n");
                    }
                    if (line.contains("==/UserScript==")) {
                        inMetadata = false;
                    }                    
                    if (inMetadata) {
                        if (line.trim().startsWith("// @match")) {
                            String match = line.substring(line.indexOf("@match") + 6).trim();
                            script.matches.add(match);
                        }
                        if (line.trim().startsWith("// @run-at")) {
                            String runAt = line.substring(line.indexOf("@run-at") + 7).trim();
                            if (UserScript.VALID_RUN_AT.contains(runAt)) {
                                script.runAt = runAt;
                            } else {
                                Log.d("WebToApk", "\033[1;33mWarning: Script '" + script.name + 
                                    "' has invalid @run-at value: '" + runAt + 
                                    "'. Valid values are: " + String.join(", ", UserScript.VALID_RUN_AT) +
                                    ". Using default 'document-end'.\033[0m");
                            }
                        }
                    }
                }

                // Выводим предупреждение если нет @match
                if (script.matches.isEmpty()) {
                    Log.d("WebToApk", "\033[1;33mWarning: Script '" + script.name + "' has no @match patterns. It will be applied to all URLs.\033[0m");
                } else if (!script.matchesUrl(mainUrl)) {
                    Log.d("WebToApk", "\033[1;33mWarning: Script '" + script.name + "' does not match main URL: " + mainUrl + "\033[0m");
                }
                
                script.code = code.toString();
                userScripts.add(script);
                
                reader.close();
                input.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private String readAssetFile(String assetPath) {
        try (InputStream input = context.getAssets().open(assetPath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }


    public void injectScripts(WebView webview, String url) {
        String helpersJs = readAssetFile("helpers.js");
        webview.evaluateJavascript(helpersJs, null);

        for (UserScript script : userScripts) {
            if (script.matchesUrl(url)) {
                String js;
                switch (script.runAt) {
                    case "document-start":
                        js = script.code + "\n//# sourceURL=" + script.name;
                        webview.evaluateJavascript(js, null);
                        break;
                        
                    case "document-body":
                        js = "waitForBody().then(() => { " + 
                                script.code + "\n});\n" +
                                "//# sourceURL=" + script.name;
                        webview.evaluateJavascript(js, null);
                        break;
                        
                    case "document-end":
                        js = "document.addEventListener('DOMContentLoaded', function() { " +
                                script.code + "\n" +
                                "});\n" +
                                "//# sourceURL=" + script.name;
                        webview.evaluateJavascript(js, null);
                        break;
                        
                    case "document-idle":
                        js = "document.addEventListener('DOMContentLoaded', function() {" +
                                "    setTimeout(() => {" + 
                                "        " + script.code + "\n" +
                                "    }, 5);\n" +
                                "});\n" +
                                "//# sourceURL=" + script.name;
                        webview.evaluateJavascript(js, null);
                        break;
                }
            }
        }
    }
    
}
