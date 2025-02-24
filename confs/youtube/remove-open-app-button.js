// ==UserScript==
// @name         Removes button "Open App"
// @version      1.0
// @match        https://m.youtube.com/*
// @grant        none
// ==/UserScript==

(function() {
    'use strict';

    function removeAppButton() {
        const buttons = document.querySelectorAll('ytm-button-renderer');
        
        buttons.forEach((button) => {
            const link = button.querySelector('a[href*="com.google.android.youtube"]');
            if (link) {
                button.remove();
            }
        });
    }

    window.addEventListener('load', removeAppButton);

    const observer = new MutationObserver(() => {
        removeAppButton();
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true
    });
})();
