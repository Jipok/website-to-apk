// ==UserScript==
// @name               Auto Skip YouTube Ads
// @name:ar            التخطي التلقائي لإعلانات YouTube
// @name:es            Saltar Automáticamente Anuncios De YouTube
// @name:hi            YouTube विज्ञापन स्वचालित रूप से छोड़ें
// @name:id            Lewati Otomatis Iklan YouTube
// @name:ja            YouTube 広告を自動スキップ
// @name:ko            YouTube 광고 자동 건너뛰기
// @name:pt-BR         Pular Automaticamente Anúncios Do YouTube
// @name:ru            Автоматический Пропуск Рекламы На YouTube
// @name:vi            Tự Động Bỏ Qua Quảng Cáo YouTube
// @name:zh-CN         自动跳过 YouTube 广告
// @name:zh-TW         自動跳過 YouTube 廣告
// @namespace          https://github.com/tientq64/userscripts
// @version            7.0.1
// @description        Automatically skip YouTube ads almost instantly. Remove the ad blocker warning pop-up.
// @description:ar     تخطي إعلانات YouTube تلقائيًا في الحال. إزالة النافذة المنبثقة لتحذير مانع الإعلانات.
// @description:es     Omite automáticamente los anuncios de YouTube casi al instante. Elimina la ventana emergente de advertencia del bloqueador de anuncios.
// @description:hi     YouTube विज्ञापनों को लगभग तुरंत ही स्वचालित रूप से छोड़ दें। विज्ञापन अवरोधक चेतावनी पॉप-अप हटाएँ।
// @description:id     Lewati iklan YouTube secara otomatis hampir seketika. Hapus pop-up peringatan pemblokir iklan.
// @description:ja     YouTube 広告をほぼ瞬時に自動的にスキップします。広告ブロッカーの警告ポップアップを削除します。
// @description:ko     YouTube 광고를 거의 즉시 자동으로 건너뜁니다. 광고 차단 경고 팝업을 제거합니다.
// @description:pt-BR  Pule automaticamente os anúncios do YouTube quase instantaneamente. Remova o pop-up de aviso do bloqueador de anúncios.
// @description:ru     Автоматически пропускайте рекламу YouTube почти мгновенно. Уберите всплывающее предупреждение о блокировщике рекламы.
// @description:vi     Tự động bỏ qua quảng cáo YouTube gần như ngay lập tức. Loại bỏ cửa sổ bật lên cảnh báo trình chặn quảng cáo.
// @description:zh-CN  几乎立即自动跳过 YouTube 广告。删除广告拦截器警告弹出窗口。
// @description:zh-TW  幾乎立即自動跳過 YouTube 廣告。刪除廣告攔截器警告彈出視窗。
// @author             tientq64
// @icon               https://cdn-icons-png.flaticon.com/64/2504/2504965.png
// @match              https://www.youtube.com/*
// @match              https://m.youtube.com/*
// @match              https://music.youtube.com/*
// @grant              none
// @license            MIT
// @compatible         firefox
// @compatible         chrome
// @compatible         opera
// @compatible         safari
// @compatible         edge
// @noframes
// @homepage           https://github.com/tientq64/userscripts/tree/main/scripts/Auto-Skip-YouTube-Ads
// ==/UserScript==

/**
 * Skip ads. Remove ad elements.
 */
function skipAd() {
    removeAdElements()

    video = null
    fineScrubber = document.querySelector('.ytp-fine-scrubbing')

    // Check if the current URL is a YouTube Shorts URL and exit the function if true.
    if (window.location.pathname.startsWith('/shorts/')) return

    const moviePlayer = document.querySelector('#movie_player')

    if (moviePlayer) {
        hasAd = moviePlayer.classList.contains('ad-showing')
        video = moviePlayer.querySelector('video.html5-main-video')
    }

    if (hasAd) {
        const skipButton = document.querySelector(`
            .ytp-skip-ad-button,
            .ytp-ad-skip-button,
            .ytp-ad-skip-button-modern,
            .ytp-ad-survey-answer-button
        `)
        // Click the skip ad button if available.
        if (skipButton) {
            skipButton.click()
            skipButton.remove()
        }
        // Otherwise, fast forward to the end of the ad video.
        // Use `9999` instead of `video.duration` to avoid errors when `duration` is not a number.
        else if (video && video.src) {
            video.currentTime = 9999
        }
    }

    if (video) {
        video.addEventListener('pause', handleVideoPause)
        video.addEventListener('pointerup', allowPauseVideo)
        video.addEventListener('timeupdate', handleVideoTimeUpdate)
    }

    // Timed pie countdown ad.
    const pieCountdown = document.querySelector('.ytp-ad-timed-pie-countdown-container')
    if (pieCountdown) {
        pieCountdown.remove()
        replaceCurrentVideo()
    }

    // Handle when ad blocker warning appears inside video player.
    const adBlockerWarningInner = document.querySelector(
        '.yt-playability-error-supported-renderers'
    )
    if (adBlockerWarningInner) {
        adBlockerWarningInner.remove()
        document.addEventListener('yt-navigate-finish', handleYouTubeNavigateFinish)
        replaceCurrentVideo()
    }

    // Video play/pause button.
    const playButton = document.querySelector(
        'button.ytp-play-button, button.player-control-play-pause-icon'
    )
    if (playButton) {
        playButton.addEventListener('click', allowPauseVideo)
    }
}

function queryHasSelector(selector, hasSelector, element = document) {
    const el = element.querySelector(selector)
    if (el === null) return null
    const hasEl = el.querySelector(hasSelector)
    if (hasEl === null) return null
    return el
}

function queryHasSelectorAll(selector, hasSelector, element = document) {
    const els = element.querySelectorAll(selector)
    const result = []
    for (const el of els) {
        const hasEl = el.querySelector(hasSelector)
        if (hasEl === null) continue
        result.push(el)
    }
    return result
}

function getCurrentVideoId() {
    const params = new URLSearchParams(location.search)
    const videoId = params.get('v')
    return videoId
}

/**
 * Check if the user is focused on the input.
 */
function checkEnteringInput() {
    if (document.activeElement === null) {
        return false
    }
    return document.activeElement.matches('input, textarea, select')
}

/**
 * Temporarily allows the video to be paused, for a short period of time.
 */
function allowPauseVideo() {
    pausedByUser = true
    window.clearTimeout(allowPauseVideoTimeoutId)
    allowPauseVideoTimeoutId = window.setTimeout(disallowPauseVideo, 500)
}

/**
 * Pausing the video is not allowed. The purpose is to prevent video from being paused,
 * against the behavior of pausing video when YouTube ad blocking warning dialog appears.
 * Unless certain conditions, such as pausing by user, etc.
 */
function disallowPauseVideo() {
    pausedByUser = false
    window.clearTimeout(allowPauseVideoTimeoutId)
}

function handleWindowBlur() {
    isTabBlurred = true
}

function handleWindowFocus() {
    isTabBlurred = false
}

/**
 * Handle when video is paused. If certain conditions are not met, it will continue
 * playing. Returning early in this function means the video should be paused as it should
 * be.
 */
function handleVideoPause() {
    if (isYouTubeMusic) return

    // If it was stopped by the user, it's ok, let the video pause as it should, and exit the function.
    if (pausedByUser) {
        disallowPauseVideo()
        return
    }

    // The video will pause normally if the tab is not focused. This is to allow for pausing the video via the media controller (of the browser or operating system), etc.
    // Note: While this also gives YouTube the opportunity to pause videos to annoy users, it's an acceptable trade-off.
    if (document.hidden) return
    if (isTabBlurred) return

    if (fineScrubber && fineScrubber.style.display !== 'none') return
    if (video === null) return
    if (video.duration - video.currentTime < 0.1) return

    // This is YouTube's disruptive behavior towards users, so the video should continue to play as normal.
    video.play()
}

function handleVideoTimeUpdate() {
    if (hasAd || video === null) return
    currentVideoTime = video.currentTime
}

/**
 * Handle both keyboard press or release events.
 */
function handleWindowKeyDownAndKeyUp(event) {
    if (isYouTubeMusic) return
    if (checkEnteringInput()) return
    const code = event.code
    if (event.type === 'keydown') {
        if (code === 'KeyK' || code === 'MediaPlayPause') {
            allowPauseVideo()
        }
    } else {
        if (code === 'Space') {
            allowPauseVideo()
        }
    }
}

function handleYouTubeNavigateFinish() {
    currentVideoTime = 0
    replaceCurrentVideo()
}

async function replaceCurrentVideo() {
    const start = Math.floor(currentVideoTime)
    for (let i = 0; i < 16; i++) {
        await waitFor(500)
        const videoId = getCurrentVideoId()
        if (!videoId || !video || video.src) continue
        if (isYouTubeMobile) {
            const player = document.querySelector('#movie_player')
            if (!player) continue
            player.loadVideoByPlayerVars({ videoId, start })
        } else {
            const player = document.querySelector('#ytd-player')
            if (!player) continue
            player.loadVideoWithPlayerVars({ videoId, start })
        }
    }
}

function waitFor(millis) {
    return new Promise((resolve) => {
        window.setTimeout(resolve, millis)
    })
}

/**
 * Add CSS hides some ad elements on the page.
 */
function addCss() {
    const hideCssSelector = [
        // Ad banner in the upper right corner, above the video playlist.
        '#player-ads',

        // Masthead ad on home page.
        '#masthead-ad',

        // 'ytd-ad-slot-renderer',

        // Ad blocker warning inside the player.
        'yt-playability-error-supported-renderers#error-screen',

        // '.ytp-suggested-action',
        '.yt-mealbar-promo-renderer',

        // YouTube Music Premium trial promotion dialog, bottom left corner.
        'ytmusic-mealbar-promo-renderer',

        // YouTube Music Premium trial promotion banner on home page.
        'ytmusic-statement-banner-renderer'
    ].join(',')
    const css = `
        #ytd-player { visibility: visible !important; }
        ${hideCssSelector} { display: none !important; }
    `
    const style = document.createElement('style')
    style.textContent = css
    document.head.appendChild(style)
}

/**
 * Remove ad elements using javascript because these selectors require the use of the CSS
 * `:has` selector which is not supported in older browser versions.
 */
function removeAdElements() {
    const adSelectors = [
        // Ad banner in the upper right corner, above the video playlist.
        ['#panels', 'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]'],

        // Temporarily comment out this selector to fix issue [#265124](https://greasyfork.org/en/scripts/498197-auto-skip-youtube-ads/discussions/265124).
        // ['#panels', 'ytd-ads-engagement-panel-content-renderer'],

        // Sponsored ad video items on home page.
        // ['ytd-rich-item-renderer', '.ytd-ad-slot-renderer'],

        // ['ytd-rich-section-renderer', '.ytd-statement-banner-renderer'],

        // Ad videos on YouTube Short.
        ['ytd-reel-video-renderer', '.ytd-ad-slot-renderer'],

        // Ad blocker warning dialog.
        ['tp-yt-paper-dialog', '#feedback.ytd-enforcement-message-view-model']

        // Survey dialog on home page, located at bottom right.
        // ['tp-yt-paper-dialog', ':scope > ytd-checkbox-survey-renderer'],

        // Survey to rate suggested content, located at bottom right.
        // ['tp-yt-paper-dialog', ':scope > ytd-single-option-survey-renderer']
    ]
    for (const adSelector of adSelectors) {
        const adEls = queryHasSelectorAll(adSelector[0], adSelector[1])
        for (const adEl of adEls) {
            adEl.remove()
        }
    }
}

/**
 * Is it YouTube mobile version.
 */
const isYouTubeMobile = location.hostname === 'm.youtube.com'

/**
 * Is the current page YouTube Music.
 */
const isYouTubeMusic = location.hostname === 'music.youtube.com'

/**
 * Current video element.
 */
let video = null

let fineScrubber = null
let hasAd = false
let currentVideoTime = 0

/**
 * Is the video paused by the user, not paused by YouTube's ad blocker warning dialog.
 */
let pausedByUser = false

/**
 * Is the current tab blurred.
 */
let isTabBlurred = false

let allowPauseVideoTimeoutId = 0

// Observe DOM changes to detect ads.
if (window.MutationObserver) {
    const observer = new MutationObserver(skipAd)
    observer.observe(document.body, {
        attributes: true,
        attributeFilter: ['class', 'src'],
        childList: true,
        subtree: true
    })
}
// If DOM observation is not supported. Detect ads every 500ms (2 times per second).
else {
    window.setInterval(skipAd, 500)
}

window.addEventListener('blur', handleWindowBlur)
window.addEventListener('focus', handleWindowFocus)
window.addEventListener('keydown', handleWindowKeyDownAndKeyUp)
window.addEventListener('keyup', handleWindowKeyDownAndKeyUp)

addCss()
skipAd()