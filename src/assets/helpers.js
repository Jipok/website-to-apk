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

// ---------------------------------------------------------------------------------------------
// Userscript manager API. Deliberately only what popular scripts actually ask for: storage,
// metadata, the page window, the clipboard, notifications, tabs and a value-change hook.
// GM_xmlhttpRequest is the one request API, and its real feature is the CORS bypass, so it goes
// through the native bridge: cookie-less by construction, text-only, and asynchronous — the native
// call returns at once and the answer arrives later as window.__webtoapkHttpDone(id, json).
// responseType and onprogress are not implemented; a script that needs them must not use this.
// ---------------------------------------------------------------------------------------------

if (!window.GM_xmlhttpRequest) {
    const gmPending = new Map();
    let gmNextId = 1;

    // The native side calls this back with the whole answer, on the page's thread
    window.__webtoapkHttpDone = function(id, json) {
        const details = gmPending.get(id);
        if (!details) return; // aborted, or a second answer
        gmPending.delete(id);
        let answer;
        try { answer = JSON.parse(json); } catch (e) { answer = { error: String(e) }; }
        if (answer.error) {
            if (typeof details.onerror === 'function') details.onerror({ error: answer.error, status: 0, responseText: '' });
            else console.error('GM_xmlhttpRequest:', answer.error);
            return;
        }
        const out = {
            status: answer.status,
            statusText: '',
            responseText: answer.body || '',
            responseHeaders: Object.keys(answer.headers || {})
                .map((name) => name + ': ' + answer.headers[name]).join('\r\n'),
            finalUrl: details.url || ''
        };
        if (details.responseType === 'json') { try { out.response = JSON.parse(out.responseText); } catch (e) {} }
        if (typeof details.onload === 'function') details.onload(out);
    };

    window.GM_xmlhttpRequest = function(details) {
        const d = details || {};
        const id = String(gmNextId++);
        gmPending.set(id, d);
        window.WebToApk.httpRequest(id, String(d.method || 'GET').toUpperCase(), String(d.url || ''),
            JSON.stringify(d.headers || {}), d.data == null ? '' : String(d.data));
        return {
            abort: function() {
                if (gmPending.delete(id)) window.WebToApk.httpAbort(id);
            }
        };
    };
}
// ---------------------------------------------------------------------------------------------

if (!window.unsafeWindow) {
    // A userscript is injected into the page context, so the page window *is* the unsafe window.
    window.unsafeWindow = window;
}

if (!window.GM_info) {
    window.GM_info = {
        script: { name: 'WebToApk userscript', version: '1.0', description: 'Injected by WebToApk', author: '' },
        scriptHandler: 'WebToApk',
        version: '1.0',
    };
}

if (!window.GM_getValue) {
    const GM_PREFIX = '__webtoapk_gm__:';
    const gmListeners = {};
    let gmNextListenerId = 0;

    const gmRead = key => {
        try {
            const raw = localStorage.getItem(GM_PREFIX + key);
            if (raw === null) return undefined;
            // Tampermonkey stores JSON so values keep their type; an entry written by something else
            // is handed back raw instead of being thrown away.
            try { return JSON.parse(raw); } catch (e) { return raw; }
        } catch (e) { return undefined; }
    };

    const gmFire = (key, oldValue, newValue) => {
        (gmListeners[key] || []).slice().forEach(listener => {
            try { listener.callback(key, oldValue, newValue, false); } catch (e) { console.error(e); }
        });
    };

    window.GM_getValue = function(key, fallback) {
        const value = gmRead(key);
        return value === undefined ? fallback : value;
    };

    window.GM_setValue = function(key, value) {
        const oldValue = gmRead(key);
        // `undefined` is not JSON: JSON.stringify returns the value `undefined` (not a string), setItem
        // turns it into the literally stored string "undefined", and gmRead then hands that raw string
        // back instead of falling through to the caller's default. null is the JSON notion of "no value".
        try { localStorage.setItem(GM_PREFIX + key, JSON.stringify(value === undefined ? null : value)); } catch (e) { return; }
        gmFire(key, oldValue, value);
    };

    window.GM_deleteValue = function(key) {
        const oldValue = gmRead(key);
        try { localStorage.removeItem(GM_PREFIX + key); } catch (e) { return; }
        gmFire(key, oldValue, undefined);
    };

    window.GM_listValues = function() {
        const keys = [];
        try {
            for (let i = 0; i < localStorage.length; i++) {
                const key = localStorage.key(i);
                if (key && key.indexOf(GM_PREFIX) === 0) keys.push(key.slice(GM_PREFIX.length));
            }
        } catch (e) { /* ignore */ }
        return keys;
    };

    // There is one context, not several tabs, so a change always looks local (remote = false).
    window.GM_addValueChangeListener = function(key, callback) {
        const id = ++gmNextListenerId;
        (gmListeners[key] = gmListeners[key] || []).push({ id, callback });
        return id;
    };

    window.GM_removeValueChangeListener = function(id) {
        Object.keys(gmListeners).forEach(key => {
            gmListeners[key] = gmListeners[key].filter(listener => listener.id !== id);
        });
    };
}

if (!window.GM_setClipboard) {
    const copyFallback = text => {
        const area = document.createElement('textarea');
        area.value = text;
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy'); } catch (e) { /* ignore */ }
        document.body.removeChild(area);
    };

    window.GM_setClipboard = function(text) {
        const value = String(text == null ? '' : text);
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(value).catch(() => copyFallback(value));
        } else {
            copyFallback(value);
        }
    };
}

if (!window.GM_addElement) {
    // GM_addElement(tag, attrs) or GM_addElement(parent, tag, attrs)
    window.GM_addElement = function(a, b, c) {
        const parent = typeof a === 'string' ? (document.head || document.body) : a;
        const tag = typeof a === 'string' ? a : b;
        const attrs = (typeof a === 'string' ? b : c) || {};
        const element = document.createElement(tag);
        Object.keys(attrs).forEach(name => element.setAttribute(name, attrs[name]));
        if (parent) parent.appendChild(element);
        return element;
    };
}

if (!window.GM_log) {
    window.GM_log = function() { console.log.apply(console, arguments); };
}

if (!window.GM_notification) {
    window.GM_notification = function(details, ondone) {
        const options = typeof details === 'string' ? { text: details } : (details || {});
        try {
            const notification = new Notification(options.title || '', {
                body: options.text || '',
                icon: options.image,
            });
            const done = typeof options.ondone === 'function' ? options.ondone
                : (typeof ondone === 'function' ? ondone : null);
            if (done) notification.onclose = done;
        } catch (e) { /* ignore */ }
    };
}

if (!window.GM_openInTab) {
    // There is no tab strip: the app decides, and a _blank window.open takes the same external-link
    // path as a normal target=_blank link.
    window.GM_openInTab = function(url) {
        window.open(url, '_blank');
        return { closed: true, close: function() {} };
    };
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

})();


/*
 * This script intercepts calls to the standard navigator.mediaSession API
 * and forwards them to the native Android app via the WebToApk JavascriptInterface.
 * This allows the web page's media metadata and playback state to be reflected in
 * Android's system UI (e.g., notification shade, lock screen).
 */
(function() {
    if (navigator.mediaSession) {
        // Injected twice into one document: keep the instance the page already holds, or the
        // handlers it registered would be thrown away.
        if (navigator.mediaSession._isShim) return;
        // A real Media Session API. This shim carries four actions; the platform one has seekto,
        // stop, seekbackward/forward and the rest, so replacing it would be a downgrade — and a
        // future WebView having one is the whole reason this check exists. WebView has none today.
        console.log("WebToApk: native Media Session API present, keeping it.");
        return;
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
    // Artwork is fetched asynchronously, so the answer for track A can arrive after the one for track
    // B and put A's cover back on the notification. Every update takes a ticket; only the newest one
    // is allowed to reach the native side.
    let _metadataSeq = 0;
    const SUPPORTED_ACTIONS = ['play', 'pause', 'previoustrack', 'nexttrack'];

    const mediaSessionShim = {
        _isShim: true,

        // --- Properties ---
        get metadata() {
            return _metadata;
        },

        set metadata(metadata) {
            _metadata = metadata;
            const seq = ++_metadataSeq;
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
                        if (seq !== _metadataSeq) return;   // a newer track already won
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
                                if (seq !== _metadataSeq) return;   // a newer track already won
                                window.WebToApk.updateMediaMetadata(title, artist, album, dataUrl);
                            })
                            .catch(networkError => {
                                // Final fallback: If both cache and network fail, send no artwork
                                console.error('WebToApk: Both cache and network fetch for artwork failed. Sending null.', networkError.message);
                                if (seq !== _metadataSeq) return;   // a newer track already won
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

// --- File export ---------------------------------------------------------
(function() {
    const CHUNK = 1 << 20;

    // DownloadListener does not receive the `download` attribute, so remember it on click
    document.addEventListener('click', (e) => {
        const a = e.target.closest && e.target.closest('a[download]');
        if (a) window.__webtoapkDownloadName = a.download;
    }, true);

    // A site exports like this: build a Blob, point a link at it, click the link, revoke the URL.
    // The element is usually not in the DOM (so no click ever reaches the listener above), and by the
    // time the SAF picker comes back the URL is long revoked — fetching then reported "download
    // failed" for an export the site had done correctly. Keep the Blob itself, not just the name: it
    // is the page's own object, so a reference leaks nothing new, and the last few are plenty.
    const RECENT_BLOBS = 8;
    const recentBlobs = new Map();
    const createObjectURL = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function (blob) {
        const url = createObjectURL(blob);
        recentBlobs.set(url, blob);
        if (recentBlobs.size > RECENT_BLOBS) recentBlobs.delete(recentBlobs.keys().next().value);
        return url;
    };

    // blob:/data: links cannot go through DownloadManager. The app asks the user for a
    // destination (SAF), then we stream the bytes to it in chunks to keep memory flat.
    window.__webtoapkSaveBlob = (url, mime) => {
        const name = window.__webtoapkDownloadName || 'download';
        window.__webtoapkDownloadName = null;   // it used to stick to every later download
        WebToApk.pickSaveTarget(name, mime);
        const kept = recentBlobs.get(url);
        recentBlobs.delete(url);
        window.__webtoapkSendBlob = async () => {
            let ok = true;
            try {
                const blob = kept || await (await fetch(url)).blob();
                for (let offset = 0; offset < blob.size; offset += CHUNK) {
                    const reader = new FileReader();
                    await new Promise((resolve) => {
                        reader.onload = resolve;
                        reader.readAsDataURL(blob.slice(offset, offset + CHUNK));
                    });
                    if (!WebToApk.appendChunk(reader.result.split(',')[1])) ok = false;
                }
            } catch (e) {
                ok = false;
            }
            WebToApk.finishSave(ok);
        };
    };
})();
