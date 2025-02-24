// ==UserScript==
// @version 1.0
// @match https://*.youtube.com/*
// @grant GM_addStyle
// @run-at document-idle
// ==/UserScript==

(function() {
    var punisherYT = "//yt1s.com/youtube-to-mp3?q=";

        function addDownloadButton() {
        const lastButton = document.querySelector('button-view-model[class="yt-spec-button-view-model slim_video_action_bar_renderer_button"]:last-of-type');
        
        if (lastButton && !document.getElementById('downloadBtn')) {
            // Копируем последнюю кнопку целиком
            const newButton = lastButton.cloneNode(true);
            
            // Меняем текст на "Скачать"
            const textElement = newButton.querySelector('.yt-spec-button-shape-next__button-text-content');
            if (textElement) {
                const language = navigator.language || navigator.userLanguage;
                textElement.textContent = language.startsWith('ru') ? 'Скачать' : 'Download';
            }
            
            // Оборачиваем в ссылку
            const wrapper = document.createElement('div');
            wrapper.style.display = 'contents';
            
            const link = document.createElement('a');
            link.id = 'downloadBtn';
            link.href = punisherYT + encodeURIComponent(window.location);
            link.target = '_blank';
            link.style.textDecoration = 'none';
            link.style.color = 'inherit';
            
            wrapper.appendChild(link);
            link.appendChild(newButton);
            
            // Заменяем последнюю кнопку на нашу
            lastButton.parentElement.replaceChild(wrapper, lastButton);
        }
    }

    // Запускаем первый раз
    setTimeout(addDownloadButton, 2000);
    
    // Следим за изменениями URL
    let lastUrl = location.href;
    new MutationObserver(() => {
        if (location.href !== lastUrl) {
            lastUrl = location.href;
            setTimeout(addDownloadButton, 2000);
        }
    }).observe(document, {subtree: true, childList: true});    
})();
