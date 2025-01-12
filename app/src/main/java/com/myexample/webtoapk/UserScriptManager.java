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

    public UserScriptManager(Context context) {
        this.context = context;
        loadUserScripts();
    }

    private void loadUserScripts() {
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
        for (UserScript script : userScripts) {
            if (script.matchesUrl(url)) {
                // Создаем изолированный контекст для каждого скрипта
                String escapedCode = JSONObject.quote(script.code);
                String finalJs = 
                    "(function() {\n" +
                    "   const scriptName = '" + script.name + "';\n" +
                    "   const originalConsole = {\n" +
                    "       log: console.log.bind(console),\n" +
                    "       error: console.error.bind(console),\n" +
                    "       warn: console.warn.bind(console)\n" +
                    "   };\n" +
                    "   const customConsole = {\n" +
                    "       log: function(...args) {\n" +
                    "           originalConsole.log('\033[0;34m[' + scriptName + ']\033[0m', ...args);\n" +
                    "       },\n" +
                    "       error: function(...args) {\n" +
                    "           originalConsole.error('\033[0;31m[' + scriptName + ']\033[0m', ...args);\n" +
                    "       },\n" +
                    "       warn: function(...args) {\n" +
                    "           originalConsole.warn('\033[1;33m[' + scriptName + ']\033[0m', ...args);\n" +
                    "       }\n" +
                    "   };\n" +
                    "   function tryExecuteScript() {\n" +
                    "       if (document.body) {\n" +
                    "           try {\n" +
                    "               try {\n" +
                    "                   new Function('return ' + " + escapedCode + " + '\\n//# sourceURL=ozon.js');\n" +
                    "               } catch(syntaxError) {\n" +
                    "                   customConsole.error('Syntax error:', syntaxError.message);\n" +
                    "                   return;\n" +
                    "               }\n" +
                    "               try {\n" +
                    "                   (function(console) {\n" +
                    "                       eval(" + escapedCode + ");\n" +
                    "                   })(customConsole);\n" +
                    "               } catch(runtimeError) {\n" +
                    "                   customConsole.error('Runtime error:', runtimeError);\n" +
                    "               }\n" +
                    "           } catch(error) {\n" +
                    "               customConsole.error('Unknown error:', error.message);\n" +
                    "           }\n" +
                    "       } else {\n" +
                    "           requestAnimationFrame(tryExecuteScript);\n" +
                    "       }\n" +
                    "   }\n" +
                    "   tryExecuteScript();\n" +
                    "})();";

                webview.evaluateJavascript(finalJs, null);
            }
        }
    }
}
