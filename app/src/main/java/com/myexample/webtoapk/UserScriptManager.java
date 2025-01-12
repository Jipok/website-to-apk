package com.myexample.webtoapk;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
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

    public void injectScripts(WebView webview, String url) {
        // Объявляем функцию waitForBody и GM_addStyle только один раз
        String helperFunctions = 
        "if (!window.waitForBody) {\n" +
        "   window.waitForBody = function() {\n" +
        "       return new Promise(resolve => {\n" +
        "           function check() {\n" +
        "               if (document.body) {\n" +
        "                   resolve();\n" +
        "               } else {\n" +
        "                   requestAnimationFrame(check);\n" +
        "               }\n" +
        "           }\n" +
        "           check();\n" +
        "       });\n" +
        "   }\n" +
        "}\n" +
        
        "if (!window.GM_addStyle) {\n" +
        "   window.GM_addStyle = function(css) {\n" +
        "       const style = document.createElement('style');\n" +
        "       style.textContent = css;\n" +
        "       document.head.appendChild(style);\n" +
        "       return style;\n" +
        "   }\n" +
        "}";

    webview.evaluateJavascript(helperFunctions, null);
    
        // Используем её для каждого скрипта
        for (UserScript script : userScripts) {
            if (script.matchesUrl(url)) {
                String js = 
                    "waitForBody().then(() => { " +
                    script.code + "\n});\n" +
                    "//# sourceURL=" + script.name;
                    
                webview.evaluateJavascript(js, null);
            }
        }
    }
    
}
