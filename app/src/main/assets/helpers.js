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


/*
 * This script intercepts calls to the standard navigator.mediaSession API
 * and forwards them to the native Android app via the WebToApk JavascriptInterface.
 * This allows the web page's media metadata and playback state to be reflected in
 * Android's system UI (e.g., notification shade, lock screen).
 */
(function() {
    if (!('mediaSession' in navigator) || navigator.mediaSession._isShim) {
        if (navigator.mediaSession && navigator.mediaSession._isShim) {
             console.log("WebToApk: Media Session shim already initialized.");
             return;
        }
    }

    window.MediaMetadata = class MediaMetadata {
        constructor(data = {}) {
            this.title = data.title || '';
            this.artist = data.artist || '';
            this.album = data.album || '';
            this.artwork = data.artwork || [];
        }
    }

    const _handlers = {};
    let _metadata = null;
    let _playbackState = "none";
    const SUPPORTED_ACTIONS = ['play', 'pause', 'previoustrack', 'nexttrack'];

    const mediaSessionShim = {
        _isShim: true,

        // --- Properties ---
        get metadata() {
            return _metadata;
        },

        set metadata(metadata) {
            _metadata = metadata;
            if (!metadata) {
                // Clear all metadata
                window.WebToApk.updateMediaMetadata(null, null, null, null);
                return;
            }

            const artworkSrc = (metadata.artwork && metadata.artwork.length > 0) ? metadata.artwork[0].src : null;
            const title = metadata.title || null;
            const artist = metadata.artist || null;
            const album = metadata.album || null;

            if (artworkSrc) {
                const absoluteUrl = new URL(artworkSrc, document.baseURI).href;

                const convertToDataURL = (blob) => {
                    return new Promise((resolve, reject) => {
                        const reader = new FileReader();
                        reader.onload = () => resolve(reader.result);
                        reader.onerror = (error) => reject(error);
                        reader.readAsDataURL(blob);
                    });
                };

                // Attempt 1: Try to get the image from the cache first (for offline support)
                fetch(absoluteUrl, { cache: 'force-cache' })
                    .then(response => {
                        if (!response.ok) throw new Error('Image not in cache');
                        return response.blob();
                    })
                    .then(convertToDataURL)
                    .then(dataUrl => {
                        window.WebToApk.updateMediaMetadata(title, artist, album, dataUrl);
                    })
                    .catch(cacheError => {
                        // Attempt 2: If cache fails, fall back to a network request
                        console.warn('WebToApk: Could not load image from cache. Attempting network fetch.', cacheError.message);

                        fetch(absoluteUrl) // Default cache policy, will hit the network if needed
                            .then(response => {
                                if (!response.ok) throw new Error('Network response was not ok');
                                return response.blob();
                            })
                            .then(convertToDataURL)
                            .then(dataUrl => {
                                window.WebToApk.updateMediaMetadata(title, artist, album, dataUrl);
                            })
                            .catch(networkError => {
                                // Final fallback: If both cache and network fail, send no artwork
                                console.error('WebToApk: Both cache and network fetch for artwork failed. Sending null.', networkError.message);
                                window.WebToApk.updateMediaMetadata(title, artist, album, null);
                            });
                    });
            } else {
                // No artwork specified, just update metadata without an image
                window.WebToApk.updateMediaMetadata(title, artist, album, null);
            }
        },

        get playbackState() {
            return _playbackState;
        },
        set playbackState(state) {
            _playbackState = state;
            window.WebToApk.updateMediaPlaybackState(state);
        },

        // --- Methods ---
        setActionHandler: function(action, handler) {
            if (!SUPPORTED_ACTIONS.includes(action)) {
                console.warn(`WebToApk: Unsupported media session action handler '${action}'. The app will ignore it. Supported actions are: ${SUPPORTED_ACTIONS.join(', ')}.`);
                return;
            }

            _handlers[action] = handler;
            // Inform the native side about which actions are now available.
            const supportedActions = Object.keys(_handlers).filter(key => _handlers[key] !== null);
            window.WebToApk.setMediaActionHandlers(supportedActions);
        },

        setPositionState: function(state) {
            if (!state) {
                // If called with null or undefined, do nothing or reset.
                // For now, we'll just log and exit.
                console.log('WebToApk: setPositionState called with no state.');
                return;
            }

            // Extract values, providing defaults if they are missing.
            const duration = state.duration || 0;
            const playbackRate = state.playbackRate || 1.0;
            const position = state.position || 0;

            window.WebToApk.updateMediaPositionState(duration, playbackRate, position);
        }
    };

    // This internal function will be called by the native Android code.
    window.__runMediaAction = function(action) {
        if (typeof _handlers[action] === 'function') {
            console.log(`WebToApk: Executing media action: ${action}`);
            _handlers[action]();
        } else {
             console.warn(`WebToApk: No handler for media action: ${action}`);
        }
    };

    // Replace the original mediaSession object with our shim.
    Object.defineProperty(navigator, 'mediaSession', {
        value: mediaSessionShim,
        writable: false,
        configurable: true
    });

})();
