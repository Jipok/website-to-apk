// ==UserScript==
// @name         OZON App Banner Remover
// @namespace    http://tampermonkey.net/
// @version      1.0
// @description  Removes the app installation banner on OZON
// @author       Jipok
// @match        https://m.youtube.com/*
// @grant        none
// ==/UserScript==

GM_addStyle(`
ytm-companion-slot,
.ytd-companion-slot-renderer,
ad-slot-renderer,
#companion-slot {
    display: none !important;
}

/* Скрыть различные рекламные баннеры и блоки */
.ytd-promoted-video-renderer,
.ytd-promoted-sparkles-web-renderer,
.ytd-display-ad-renderer,
.ytd-ad-slot-renderer,
.ytp-ad-overlay-container,
.video-ads,
.ytp-ad-progress-list,
#masthead-ad,
#player-ads,
.ytd-in-feed-ad-layout-renderer,
.ytd-banner-promo-renderer,
.ytd-statement-banner-renderer,
.ytd-promoted-sparkles-text-search-renderer,
.ytd-primetime-promo-renderer {
    display: none !important;
}

/* Скрыть рекламные вставки в видео */
.ad-showing,
.ytp-ad-skip-button-container,
.ytp-ad-overlay-image,
.ytp-ad-text-overlay {
    display: none !important;
}
`)
