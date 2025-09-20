if (!window.waitForBody) {
    window.waitForBody = function() {
        return new Promise(resolve => {
            function check() {
                if (document.body) {
                    resolve();
                } else {
                    requestAnimationFrame(check);
                }
            }
            check();
        });
    }
}

if (!window.runAtDocumentEnd) {
    // It handles the race condition where the script is injected after the DOMContentLoaded event has already fired.
    window.runAtDocumentEnd = function(callback) {
        if (document.readyState === 'interactive' || document.readyState === 'complete') {
            callback();
        } else {
            document.addEventListener('DOMContentLoaded', callback);
        }
    }
}

if (!window.GM_addStyle) {
    window.GM_addStyle = function(css) {
        const style = document.createElement('style');
        style.textContent = css;
        document.head.appendChild(style);
        return style;
    }
}

if (!window.toast) {
    window.toast = function(message) {
        WebToApk.showShortToast(message);
    }
}

if (!navigator.share) {
    // data: { title, text, url }
    navigator.share = function(data) {
        return new Promise(function(resolve, reject) {
            try {
                WebToApk.share(data.title || '', data.text  || '',  data.url   || '');
                resolve();
            } catch (err) {
                reject(err);
            }
        });
    }
}
