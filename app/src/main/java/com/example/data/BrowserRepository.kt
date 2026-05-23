package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class BrowserRepository(private val db: BrowserDatabase) {

    private val bookmarkDao = db.bookmarkDao()
    private val historyDao = db.historyDao()
    private val extensionDao = db.extensionDao()
    private val tabDao = db.tabDao()

    // --- Bookmarks ---
    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.getAllBookmarks()
    suspend fun insertBookmark(bookmark: BookmarkEntity) = bookmarkDao.insertBookmark(bookmark)
    suspend fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkDao.deleteBookmark(bookmark)
    suspend fun isBookmarked(url: String): Boolean = bookmarkDao.isBookmarked(url)
    suspend fun deleteBookmarkByUrl(url: String) = bookmarkDao.deleteBookmarkByUrl(url)
    suspend fun clearBookmarks() = bookmarkDao.clearAll()

    // --- History ---
    val history: Flow<List<HistoryEntity>> = historyDao.getAllHistory()
    fun searchHistory(query: String): Flow<List<HistoryEntity>> = historyDao.searchHistory("%$query%")
    suspend fun insertHistory(history: HistoryEntity) = historyDao.insertHistory(history)
    suspend fun deleteHistory(history: HistoryEntity) = historyDao.deleteHistory(history)
    suspend fun clearHistory() = historyDao.clearAll()

    // --- Extensions ---
    val extensions: Flow<List<ExtensionEntity>> = extensionDao.getAllExtensions()
    suspend fun insertExtension(extension: ExtensionEntity) = extensionDao.insertExtension(extension)
    suspend fun setExtensionEnabled(id: String, enabled: Boolean) = extensionDao.setExtensionEnabled(id, enabled)
    suspend fun deleteExtension(extension: ExtensionEntity) = extensionDao.deleteExtension(extension)

    // --- Tabs ---
    val tabs: Flow<List<TabEntity>> = tabDao.getAllTabs()
    suspend fun insertTab(tab: TabEntity) = tabDao.insertTab(tab)
    suspend fun deleteTab(id: String) = tabDao.deleteTabById(id)
    suspend fun clearTabs() = tabDao.clearAll()

    // --- Setup Built-in Extensions if empty ---
    suspend fun initializeBuiltInExtensions() {
        val count = extensionDao.getCount()
        if (count == 0) {
            val builtIns = listOf(
                ExtensionEntity(
                    id = "dark_reader",
                    name = "Dark Reader Mode",
                    description = "Инвертирует цвета страниц для комфортного чтения ночью",
                    scriptContent = """
                        (function() {
                            var style = document.getElementById('dark-reader-style');
                            if (style) {
                                style.remove();
                            } else {
                                style = document.createElement('style');
                                style.id = 'dark-reader-style';
                                style.innerHTML = `
                                    html, body {
                                        filter: invert(0.92) hue-rotate(180deg) !important;
                                        background-color: #121212 !important;
                                    }
                                    img, video, iframe, [style*="background-image"] {
                                        filter: invert(1) hue-rotate(180deg) !important;
                                    }
                                `;
                                document.head.appendChild(style);
                            }
                        })()
                    """.trimIndent(),
                    isEnabled = false,
                    isBuiltIn = true
                ),
                ExtensionEntity(
                    id = "adblock_plus",
                    name = "AdBlock Plus Cosmic",
                    description = "Блокирует косметическую рекламу, всплывающие окна и банеры",
                    scriptContent = """
                        (function() {
                            var style = document.getElementById('adblock-cosmetic-style');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'adblock-cosmetic-style';
                                style.innerHTML = `
                                    .ad, .ads, .ad-box, .ad-banner, .google-ad, .doubleclick,
                                    [id*="google_ads_iframe"], iframe[src*="doubleclick.net"],
                                    #ad-container, #ad-placement, .ad_wrapper, .banner-ads,
                                    [class*="ad-"], [id*="ad-"] {
                                        display: none !important;
                                        pointer-events: none !important;
                                        height: 0 !important;
                                        width: 0 !important;
                                        opacity: 0 !important;
                                    }
                                `;
                                document.head.appendChild(style);
                            }
                        })()
                    """.trimIndent(),
                    isEnabled = true,
                    isBuiltIn = true
                ),
                ExtensionEntity(
                    id = "gemini_copilot",
                    name = "Gemini Copilot Bridge",
                    description = "Позволяет встроенному Gemini анализировать и пересказывать содержимое текущей вкладки",
                    scriptContent = """
                        (function() {
                            if (window.BrowserBridge) {
                                var text = document.body.innerText || document.body.textContent;
                                window.BrowserBridge.onPageTextExtracted(text.substring(0, 15000));
                            }
                        })()
                    """.trimIndent(),
                    isEnabled = true,
                    isBuiltIn = true
                ),
                ExtensionEntity(
                    id = "super_translate",
                    name = "Google Translate Quick-Initer",
                    description = "Добавляет виджет интеллектуального перевода страницы на русский язык",
                    scriptContent = """
                        (function() {
                            if (document.getElementById('google_translate_element')) return;
                            var div = document.createElement('div');
                            div.id = 'google_translate_element';
                            div.style.position = 'fixed';
                            div.style.bottom = '16px';
                            div.style.right = '16px';
                            div.style.zIndex = '999999';
                            div.style.backgroundColor = '#ffffff';
                            div.style.padding = '8px';
                            div.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)';
                            div.style.borderRadius = '8px';
                            document.body.appendChild(div);
                            
                            window.googleTranslateElementInit = function() {
                                new google.translate.TranslateElement({
                                    pageLanguage: 'auto',
                                    includedLanguages: 'ru,en,fr,de,es,zh',
                                    layout: google.translate.TranslateElement.InlineLayout.SIMPLE
                                }, 'google_translate_element');
                            };
                            
                            var script = document.createElement('script');
                            script.src = '//translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                            document.body.appendChild(script);
                        })()
                    """.trimIndent(),
                    isEnabled = false,
                    isBuiltIn = true
                )
            )
            for (be in builtIns) {
                extensionDao.insertExtension(be)
            }
        }
    }
}
