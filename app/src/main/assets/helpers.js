(function() {
    'use strict';


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

// Some sites check for window.PushManager to detect support.
if (!window.PushManager) {
    window.PushManager = function() {};
}

// Polyfill for Notification API, which is missing in WebView
if (!window.Notification) {
    window.Notification = function(title, options) {
        if (window.WebToApk && window.WebToApk.showNotification) {
            window.WebToApk.showNotification(title, (options && options.body) ? options.body : '');
        }
    };

    window.Notification.requestPermission = function() {
        return new Promise(function(resolve, reject) {
            window.WebToApk.requestNotificationPermission();

            let pollCount = 20;
            const intervalId = setInterval(function() {
                const state = window.WebToApk.getNotificationPermissionState();
                const webState = (state === 'prompt') ? 'default' : state;

                if (webState !== 'default' || pollCount-- < 0) {
                    clearInterval(intervalId);
                    resolve(webState);
                }
            }, 500);
        });
    };

    Object.defineProperty(window.Notification, 'permission', {
        get: function() {
            if (!window.WebToApk || !window.WebToApk.getNotificationPermissionState) {
                return 'default';
            }
            const state = window.WebToApk.getNotificationPermissionState();
            return (state === 'prompt') ? 'default' : state;
        },
        configurable: true
    });
}

let subscriptionPromiseResolver = null;
window.__shim_onNewEndpoint = function(subscriptionJson) {
    console.log("WebToApk Shim: Received new endpoint from native.", subscriptionJson);
    if (subscriptionPromiseResolver) {
        try {
            const subData = JSON.parse(subscriptionJson);
            const subscription = subscriptionFromEndpoint(subData.endpoint, subData.keys);
            subscriptionPromiseResolver(subscription);
            subscriptionPromiseResolver = null;
        } catch (e) {
            console.error("WebToApk Shim: Failed to parse subscription JSON.", e);
        }
    }
};

function uint8ArrayToUrlBase64(buffer) {
    let binary = '';
    const bytes = new Uint8Array(buffer);
    const len = bytes.byteLength;
    for (let i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
    }
    return window.btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function subscriptionFromEndpoint(endpoint, keys) {
    if (!endpoint) return null;
    const p256dh = keys.p256dh;
    const auth = keys.auth;

    function urlBase64ToUint8Array(base64String) {
        const padding = '='.repeat((4 - base64String.length % 4) % 4);
        const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
        const rawData = window.atob(base64);
        const outputArray = new Uint8Array(rawData.length);
        for (let i = 0; i < rawData.length; ++i) {
            outputArray[i] = rawData.charCodeAt(i);
        }
        return outputArray.buffer;
    }

    return {
        endpoint: endpoint,
        expirationTime: null,
        getKey: function(keyName) {
            if (keyName === 'p256dh') return urlBase64ToUint8Array(p256dh);
            if (keyName === 'auth') return urlBase64ToUint8Array(auth);
            return null;
        },
        toJSON: function() {
            return { endpoint: this.endpoint, expirationTime: null, keys: { p256dh: p256dh, auth: auth } };
        },
        unsubscribe: function() {
            return new Promise(function(resolve, reject) {
                if (window.WebToApk.unifiedPushUnregister) {
                    window.WebToApk.unifiedPushUnregister();
                    resolve(true);
                } else {
                    reject(new Error('Unsubscribe not supported by native app.'));
                }
            });
        }
    };
}

const fakePushManager = {
    getSubscription: function() {
        return new Promise(function(resolve, reject) {
            const subscriptionJson = window.WebToApk.getUnifiedPushSubscriptionJson();
            if (subscriptionJson) {
                const subData = JSON.parse(subscriptionJson);
                resolve(subscriptionFromEndpoint(subData.endpoint, subData.keys));
            } else {
                resolve(null);
            }
        });
    },
    permissionState: function(options) {
            return new Promise(function(resolve, reject) {
            try {
                const state = window.WebToApk.getNotificationPermissionState();
                resolve(state);
            } catch(e) { reject(e); }
        });
    },
    subscribe: function(options) {
        return new Promise(function(resolve, reject) {
            if (!options || !options.applicationServerKey) {
                return reject(new TypeError('subscribe() requires an applicationServerKey option.'));
            }
            subscriptionPromiseResolver = resolve;
            const vapidPublicKey = uint8ArrayToUrlBase64(options.applicationServerKey);
            window.WebToApk.unifiedPushSubscribe(vapidPublicKey);
            setTimeout(() => {
                if (subscriptionPromiseResolver) {
                    reject(new Error("Push subscription timeout."));
                    subscriptionPromiseResolver = null;
                }
            }, 30000);
        });
    }
};

const fakeServiceWorkerRegistration = {
    pushManager: fakePushManager,
    active: null, // Can be null
    showNotification: function(title, options) {
        if (window.WebToApk.showNotification) {
            window.WebToApk.showNotification(title, options.body || '');
        }
        return Promise.resolve();
    },
    // For debugging purposes
    toString: function() { return '[object FakeServiceWorkerRegistration]'; }
};

// Ensure navigator.serviceWorker exists
if (!navigator.serviceWorker) {
    navigator.serviceWorker = {};
}

// Define properties using Object.defineProperty for more robustness
Object.defineProperties(navigator.serviceWorker, {
    'ready': {
        value: Promise.resolve(fakeServiceWorkerRegistration),
        writable: true, configurable: true
    },
    'register': {
        value: function(scriptURL, options) {
            console.log(`WebToApk Shim: Intercepted service worker registration for ${scriptURL}.`);
            return Promise.resolve(fakeServiceWorkerRegistration);
        },
        writable: true, configurable: true
    },
    'getRegistration': {
        value: function() {
            return Promise.resolve(fakeServiceWorkerRegistration);
        },
        writable: true, configurable: true
    },
    'controller': {
        value: null,
        writable: true, configurable: true
    },
    'addEventListener': {
            value: (type, listener) => {
            if (type === 'message') {
                console.log("WebToApk Shim: 'message' event listener added, but will not be fired.");
            }
        },
        writable: true, configurable: true
    }
});



})();