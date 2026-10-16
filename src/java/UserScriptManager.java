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
import android.util.Log;


public class UserScriptManager {
    private List<UserScript> userScripts = new ArrayList<>();
    private Context context;
    private final String helpersJs;

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
                if (url.matches(globToRegex(pattern))) {
                    return true;
                }
            }
            return false;
        }

        /* `@match` is a glob, not a regex: only `*` is special. Everything else was handed to
           `String.matches` as-is, so a `?` or `+` (or a stray `(`) in a pattern was read as a regex
           quantifier — matching URLs the pattern never named, or throwing PatternSyntaxException out
           of onPageStarted. The `.` that was escaped by hand before comes through here too. */
        private static String globToRegex(String pattern) {
            StringBuilder regex = new StringBuilder();
            for (char c : pattern.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else if ("\\^$.|?+()[]{}".indexOf(c) >= 0) {
                    regex.append('\\').append(c);
                } else {
                    regex.append(c);
                }
            }
            return regex.toString();
        }
    }

    public UserScriptManager(Context context, String mainURL) {
        this.context = context;
        this.helpersJs = readAssetFile("helpers.js");
        loadUserScripts(mainURL + "/");
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
                    // The metablock lines are already '//' comments, so re-prefixing them only
                    // doubled the slashes and made the injected source differ from the file.
                    code.append(line).append("\n");
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
        // The shim is read once in the constructor: it never changes at runtime
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
                        js = "runAtDocumentEnd(function() { " +
                                script.code + "\n" +
                                "});\n" +
                                "//# sourceURL=" + script.name;
                        webview.evaluateJavascript(js, null);
                        break;
                        
                    case "document-idle":
                        js = "runAtDocumentEnd(function() {" +
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
