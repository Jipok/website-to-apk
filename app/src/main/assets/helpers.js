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
