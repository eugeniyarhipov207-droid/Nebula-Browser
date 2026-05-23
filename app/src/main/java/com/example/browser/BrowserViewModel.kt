package com.example.browser

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.example.api.GeminiService
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    var url: String = "about:newtab",
    var title: String = "Новая вкладка",
    var progress: Int = 0,
    var isLoading: Boolean = false,
    var canGoBack: Boolean = false,
    var canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
    var isPinned: Boolean = false,
    var tabGroup: String = "",
    var position: Int = 0
)

data class GeminiMessage(
    val sender: String, // "user" or "gemini"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db: BrowserDatabase by lazy {
        Room.databaseBuilder(
            application,
            BrowserDatabase::class.java,
            "nebula_browser_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    private val repository: BrowserRepository by lazy { BrowserRepository(db) }

    // --- Tab States (In memory for instant response, backed by DB) ---
    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String>("")
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    // --- Search Query State ---
    private val _searchBarText = MutableStateFlow("")
    val searchBarText: StateFlow<String> = _searchBarText.asStateFlow()

    // --- Web Page Text Extracted for Gemini ---
    var extractedPageText: String? = null

    // --- Local DB flows ---
    val bookmarks = repository.bookmarks
    val history = repository.history
    val extensions = repository.extensions
    val downloads = repository.downloads

    // --- Adblock Count ---
    private val _adsBlockedTotal = MutableStateFlow(0)
    val adsBlockedTotal: StateFlow<Int> = _adsBlockedTotal.asStateFlow()

    // --- Google account & Sync ---
    private val _userEmail = MutableStateFlow<String?>(null)
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    // --- Gemini Chat Interface ---
    private val _geminiMessages = MutableStateFlow<List<GeminiMessage>>(emptyList())
    val geminiMessages: StateFlow<List<GeminiMessage>> = _geminiMessages.asStateFlow()

    private val _geminiThinking = MutableStateFlow(false)
    val geminiThinking: StateFlow<Boolean> = _geminiThinking.asStateFlow()

    init {
        val prefs = application.getSharedPreferences("nebula_prefs", android.content.Context.MODE_PRIVATE)
        _userEmail.value = prefs.getString("user_email", null)

        viewModelScope.launch {
            // Pre-seed Extensions inside Room
            repository.initializeBuiltInExtensions()

            // Initialize tabs from database if present, otherwise create new tab
            repository.tabs.collectLatest { dbTabs ->
                if (dbTabs.isNotEmpty()) {
                    val converted = dbTabs.sortedBy { it.position }.map {
                        BrowserTab(
                            id = it.id,
                            url = it.url,
                            title = it.title,
                            isIncognito = it.isIncognito,
                            isPinned = it.isPinned,
                            tabGroup = it.tabGroup,
                            position = it.position
                        )
                    }
                    if (_tabs.value.isEmpty()) {
                        _tabs.value = converted
                        _activeTabId.value = converted.first().id
                    }
                } else {
                    if (_tabs.value.isEmpty()) {
                        createNewTab()
                    }
                }
            }
        }
    }

    // --- Tab Actions ---
    fun createNewTab(url: String = "about:newtab", isIncognito: Boolean = false) {
        val newTab = BrowserTab(
            url = url,
            isIncognito = isIncognito,
            position = _tabs.value.size
        )
        val list = _tabs.value.toMutableList()
        list.add(newTab)
        _tabs.value = list
        _activeTabId.value = newTab.id
        updateSearchBarText(url)

        viewModelScope.launch(Dispatchers.IO) {
            repository.insertTab(
                TabEntity(
                    id = newTab.id,
                    url = newTab.url,
                    title = newTab.title,
                    isIncognito = newTab.isIncognito,
                    isPinned = newTab.isPinned,
                    tabGroup = newTab.tabGroup,
                    position = newTab.position
                )
            )
        }
    }

    fun selectTab(id: String) {
        _activeTabId.value = id
        val active = _tabs.value.find { it.id == id }
        active?.let {
            updateSearchBarText(it.url)
        }
    }

    fun closeTab(id: String) {
        val list = _tabs.value.toMutableList()
        if (list.size <= 1) {
            // Maintain at least one tab
            val active = list.first()
            list.remove(active)
            val replacement = BrowserTab()
            list.add(replacement)
            _tabs.value = list
            _activeTabId.value = replacement.id
            updateSearchBarText(replacement.url)
            viewModelScope.launch(Dispatchers.IO) {
                repository.clearTabs()
                repository.insertTab(
                    TabEntity(
                        id = replacement.id,
                        url = replacement.url,
                        title = replacement.title,
                        isIncognito = replacement.isIncognito
                    )
                )
            }
        } else {
            val tabToClose = list.find { it.id == id }
            val index = list.indexOf(tabToClose)
            list.remove(tabToClose)
            _tabs.value = list
            
            if (_activeTabId.value == id) {
                val newActive = if (index > 0) list[index - 1] else list[0]
                _activeTabId.value = newActive.id
                updateSearchBarText(newActive.url)
            }
            viewModelScope.launch(Dispatchers.IO) {
                repository.deleteTab(id)
            }
        }
    }

    fun updateActiveTab(updates: (BrowserTab) -> Unit) {
        val currentActiveId = _activeTabId.value
        val list = _tabs.value.map {
            if (it.id == currentActiveId) {
                val copy = it.copy()
                updates(copy)
                // Persist URL/title tweaks to Tab Dao
                if (copy.url != it.url || copy.title != it.title || copy.isPinned != it.isPinned || copy.tabGroup != it.tabGroup || copy.position != it.position) {
                    viewModelScope.launch(Dispatchers.IO) {
                        repository.insertTab(
                            TabEntity(
                                id = copy.id,
                                url = copy.url,
                                title = copy.title,
                                isIncognito = copy.isIncognito,
                                isPinned = copy.isPinned,
                                tabGroup = copy.tabGroup,
                                position = copy.position
                            )
                        )
                    }
                }
                copy
            } else {
                it
            }
        }
        _tabs.value = list
    }

    fun updateSearchBarText(text: String) {
        if (text == "about:newtab") {
            _searchBarText.value = ""
        } else {
            _searchBarText.value = text
        }
    }

    // --- Ads Block Stats ---
    fun registerAdBlocked() {
        _adsBlockedTotal.value += 1
    }

    // --- Bookmarks management ---
    fun toggleBookmark(url: String, title: String) {
        viewModelScope.launch {
            if (repository.isBookmarked(url)) {
                repository.deleteBookmarkByUrl(url)
            } else {
                repository.insertBookmark(BookmarkEntity(url = url, title = title))
            }
        }
    }

    fun removeBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch { repository.deleteBookmark(bookmark) }
    }

    fun clearAllBookmarks() {
        viewModelScope.launch { repository.clearBookmarks() }
    }

    // --- History management ---
    fun addHistoryItem(url: String, title: String) {
        if (url == "about:newtab" || url.trim().isEmpty() || getActiveTab()?.isIncognito == true) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertHistory(
                HistoryEntity(
                    url = url,
                    title = if (title.isEmpty()) url else title
                )
            )
        }
    }

    fun removeHistoryItem(item: HistoryEntity) {
        viewModelScope.launch { repository.deleteHistory(item) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    // --- Support Custom Extension scripts addition ---
    fun createCustomExtension(name: String, desc: String, script: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val uniqueId = "custom_" + UUID.randomUUID().toString().substring(0, 8)
            repository.insertExtension(
                ExtensionEntity(
                    id = uniqueId,
                    name = name,
                    description = desc,
                    scriptContent = script,
                    isEnabled = true,
                    isBuiltIn = false
                )
            )
        }
    }

    fun toggleExtensionEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setExtensionEnabled(id, enabled)
        }
    }

    fun deleteUserExtension(ext: ExtensionEntity) {
        if (!ext.isBuiltIn) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.deleteExtension(ext)
            }
        }
    }

    // --- Google Sync Core ---
    fun loginWithGoogle(email: String) {
        // Link custom Google profile
        _userEmail.value = email
        val prefs = getApplication<Application>().getSharedPreferences("nebula_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("user_email", email).apply()
        syncCloudData()
    }

    fun logoutGoogle() {
        _userEmail.value = null
        _lastSync.value = null
        val prefs = getApplication<Application>().getSharedPreferences("nebula_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("user_email").apply()
    }

    fun syncCloudData() {
        if (_userEmail.value == null) return
        viewModelScope.launch {
            _syncing.value = true
            // Simulate networking sync delay
            withContext(Dispatchers.IO) {
                kotlinx.coroutines.delay(1800)
            }
            _syncing.value = false
            _lastSync.value = System.currentTimeMillis()
        }
    }

    // --- Gemini Interactive Assistant Workspace ---
    fun sendGeminiCopilotMessage(prompt: String) {
        if (prompt.trim().isEmpty()) return
        
        // Add User Message
        val updatedMsgs = _geminiMessages.value.toMutableList()
        updatedMsgs.add(GeminiMessage("user", prompt))
        _geminiMessages.value = updatedMsgs
        
        _geminiThinking.value = true
        
        viewModelScope.launch {
            // Gather history (exclude last turn since we're passing it)
            val historyTurns = mutableListOf<Pair<String, String>>()
            val rawMsgs = _geminiMessages.value
            if (rawMsgs.size > 2) {
                for (i in 0 until rawMsgs.size - 1 step 2) {
                    val userMsg = rawMsgs.getOrNull(i)
                    val geminiMsg = rawMsgs.getOrNull(i + 1)
                    if (userMsg != null && geminiMsg != null) {
                        historyTurns.add(Pair(userMsg.text, geminiMsg.text))
                    }
                }
            }

            // Optional contextual reading of active web assets
            val activeTabContentText = extractedPageText // holds the extracted innerText in focus
            
            val aiResponse = GeminiService.generateResponse(
                prompt = prompt,
                contextText = activeTabContentText,
                chatHistory = historyTurns
            )
            
            _geminiThinking.value = false
            val finalizedMsgs = _geminiMessages.value.toMutableList()
            finalizedMsgs.add(GeminiMessage("gemini", aiResponse))
            _geminiMessages.value = finalizedMsgs
        }
    }

    fun askGeminiToSummarizePage() {
        if (extractedPageText.isNullOrEmpty()) {
            val errorMsgs = _geminiMessages.value.toMutableList()
            errorMsgs.add(GeminiMessage("user", "Сделай саммари этой страницы"))
            errorMsgs.add(GeminiMessage("gemini", "Не удалось извлечь текст страницы. Дождитесь полной загрузки сайта или попробуйте запустить на информационном ресурсе (например, статья в Википедии/блог)."))
            _geminiMessages.value = errorMsgs
            return
        }

        sendGeminiCopilotMessage("Сделай краткое научно-популярное резюме (саммари) этого сайта. Выдели 4-5 ключевых пунктов в красивом форматировании с использованием маркированного списка, а затем укажи основной вывод.")
    }

    fun clearGeminiChat() {
        _geminiMessages.value = emptyList()
    }

    fun getActiveTab(): BrowserTab? {
        val currentId = _activeTabId.value
        return _tabs.value.find { it.id == currentId }
    }

    // --- Pinned, Custom Ordering & Grouping Actions ---
    fun togglePinTab(id: String) {
        val list = _tabs.value.map {
            if (it.id == id) {
                val updated = it.copy(isPinned = !it.isPinned)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.insertTab(
                        TabEntity(
                            id = updated.id,
                            url = updated.url,
                            title = updated.title,
                            isIncognito = updated.isIncognito,
                            isPinned = updated.isPinned,
                            tabGroup = updated.tabGroup,
                            position = updated.position
                        )
                    )
                }
                updated
            } else {
                it
            }
        }
        _tabs.value = list
    }

    fun setTabGroup(id: String, group: String) {
        val list = _tabs.value.map {
            if (it.id == id) {
                val updated = it.copy(tabGroup = group)
                viewModelScope.launch(Dispatchers.IO) {
                    repository.insertTab(
                        TabEntity(
                            id = updated.id,
                            url = updated.url,
                            title = updated.title,
                            isIncognito = updated.isIncognito,
                            isPinned = updated.isPinned,
                            tabGroup = updated.tabGroup,
                            position = updated.position
                        )
                    )
                }
                updated
            } else {
                it
            }
        }
        _tabs.value = list
    }

    fun moveTabUp(id: String) {
        val currentList = _tabs.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index > 0) {
            val temp = currentList[index]
            currentList[index] = currentList[index - 1]
            currentList[index - 1] = temp
            
            // Re-assign position indices
            for (i in currentList.indices) {
                currentList[i] = currentList[i].copy(position = i)
            }
            _tabs.value = currentList
            persistAllTabsOrder()
        }
    }

    fun moveTabDown(id: String) {
        val currentList = _tabs.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == id }
        if (index >= 0 && index < currentList.size - 1) {
            val temp = currentList[index]
            currentList[index] = currentList[index + 1]
            currentList[index + 1] = temp
            
            // Re-assign position indices
            for (i in currentList.indices) {
                currentList[i] = currentList[i].copy(position = i)
            }
            _tabs.value = currentList
            persistAllTabsOrder()
        }
    }

    private fun persistAllTabsOrder() {
        viewModelScope.launch(Dispatchers.IO) {
            _tabs.value.forEach { tab ->
                repository.insertTab(
                    TabEntity(
                        id = tab.id,
                        url = tab.url,
                        title = tab.title,
                        isIncognito = tab.isIncognito,
                        isPinned = tab.isPinned,
                        tabGroup = tab.tabGroup,
                        position = tab.position
                    )
                )
            }
        }
    }

    // --- Reading Mode Feature (Режим Чтения) ---
    private val _readerModeActive = MutableStateFlow(false)
    val readerModeActive: StateFlow<Boolean> = _readerModeActive.asStateFlow()

    private val _readerTitle = MutableStateFlow("")
    val readerTitle: StateFlow<String> = _readerTitle.asStateFlow()

    private val _readerParagraphs = MutableStateFlow<List<String>>(emptyList())
    val readerParagraphs: StateFlow<List<String>> = _readerParagraphs.asStateFlow()

    private val _readerFontSize = MutableStateFlow(16) // Default size: 16sp
    val readerFontSize: StateFlow<Int> = _readerFontSize.asStateFlow()

    private val _readerFontFamily = MutableStateFlow("SansSerif") // "SansSerif" or "Serif"
    val readerFontFamily: StateFlow<String> = _readerFontFamily.asStateFlow()

    fun enterReaderMode() {
        _readerModeActive.value = true
    }

    fun exitReaderMode() {
        _readerModeActive.value = false
    }

    fun setReaderFontSize(size: Int) {
        _readerFontSize.value = size.coerceIn(12, 32)
    }

    fun setReaderFontFamily(family: String) {
        _readerFontFamily.value = family
    }

    fun updateReaderContent(title: String, paragraphsJson: String) {
        _readerTitle.value = if (title.trim().isEmpty()) "Статья для чтения" else title
        try {
            val cleanJson = paragraphsJson.trim().removePrefix("[").removeSuffix("]")
            if (cleanJson.isEmpty()) {
                _readerParagraphs.value = listOf("Не удалось обнаружить содержательный текст статьи на этой странице.")
                return
            }
            val parsedList = cleanJson.split("\",\"").map {
                it.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
            }.filter { it.isNotBlank() }
            
            _readerParagraphs.value = parsedList.ifEmpty { listOf("Не удалось обнаружить содержательный текст статьи на этой странице.") }
        } catch (e: Exception) {
            _readerParagraphs.value = listOf("Произошла ошибка при разборе текста страницы.")
        }
    }

    // --- Gestures Swiping Controls ---
    fun swipeToNextTab() {
        val currentList = _tabs.value
        if (currentList.size <= 1) return
        val currentActiveId = _activeTabId.value
        val currentIndex = currentList.indexOfFirst { it.id == currentActiveId }
        if (currentIndex >= 0) {
            val nextIndex = (currentIndex + 1) % currentList.size
            selectTab(currentList[nextIndex].id)
        }
    }

    fun swipeToPrevTab() {
        val currentList = _tabs.value
        if (currentList.size <= 1) return
        val currentActiveId = _activeTabId.value
        val currentIndex = currentList.indexOfFirst { it.id == currentActiveId }
        if (currentIndex >= 0) {
            val prevIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else currentList.size - 1
            selectTab(currentList[prevIndex].id)
        }
    }

    // --- Downloads Helpers ---
    fun startDownload(
        context: android.content.Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val filePath = if (downloadDir != null) "${downloadDir.absolutePath}/$fileName" else null

            try {
                // Prepare request for standard Android DownloadManager
                val uri = android.net.Uri.parse(url)
                val request = android.app.DownloadManager.Request(uri).apply {
                    if (userAgent != null) {
                        addRequestHeader("User-Agent", userAgent)
                    }
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
                    if (cookie != null) {
                        addRequestHeader("Cookie", cookie)
                    }
                    setDescription("Downloading: $fileName")
                    setTitle(fileName)
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                }

                val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val downloadId = downloadManager.enqueue(request)

                // Save in local Room Database
                val downloadRecordId = repository.insertDownload(
                    DownloadEntity(
                        url = url,
                        fileName = fileName,
                        filePath = filePath,
                        mimeType = mimetype,
                        contentLength = contentLength,
                        status = "DOWNLOADING",
                        progress = 0
                    )
                )

                // Launch progress monitoring loop for this specific download
                monitorDownloadProgress(context, downloadId, downloadRecordId)

                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Началась загрузка: $fileName", android.widget.Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("BrowserViewModel", "Download failed: ${e.message}", e)
                repository.insertDownload(
                    DownloadEntity(
                        url = url,
                        fileName = fileName,
                        filePath = filePath,
                        mimeType = mimetype,
                        contentLength = contentLength,
                        status = "FAILED",
                        progress = 0
                    )
                )
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Ошибка скачивания: ${e.localizedMessage}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun monitorDownloadProgress(
        context: android.content.Context,
        downloadManagerId: Long,
        dbRecordId: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            var isDownloading = true

            while (isDownloading) {
                val query = android.app.DownloadManager.Query().setFilterById(downloadManagerId)
                val cursor = downloadManager.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)

                    val bytesDownloaded = if (bytesDownloadedIndex != -1) cursor.getInt(bytesDownloadedIndex) else 0
                    val bytesTotal = if (bytesTotalIndex != -1) cursor.getInt(bytesTotalIndex) else 0
                    val statusValue = if (statusIndex != -1) cursor.getInt(statusIndex) else 0

                    val calculatedProgress = if (bytesTotal > 0) {
                        ((bytesDownloaded.toLong() * 100) / bytesTotal).toInt()
                    } else {
                        0
                    }

                    val (dbStatus, keepLooping) = when (statusValue) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> Pair("COMPLETED", false)
                        android.app.DownloadManager.STATUS_FAILED -> Pair("FAILED", false)
                        android.app.DownloadManager.STATUS_PAUSED -> Pair("PAUSED", true)
                        android.app.DownloadManager.STATUS_PENDING -> Pair("PENDING", true)
                        else -> Pair("DOWNLOADING", true)
                    }

                    // Update local db
                    val currentDownloads = repository.downloads.first()
                    val match = currentDownloads.find { it.id == dbRecordId.toInt() }
                    if (match != null) {
                        repository.updateDownload(
                            match.copy(
                                status = dbStatus,
                                progress = if (dbStatus == "COMPLETED") 100 else calculatedProgress
                            )
                        )
                    }

                    isDownloading = keepLooping
                    cursor.close()
                } else {
                    cursor?.close()
                    isDownloading = false
                }

                if (isDownloading) {
                    kotlinx.coroutines.delay(1500)
                }
            }
        }
    }

    fun deleteDownload(download: DownloadEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteDownload(download)
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearDownloads()
        }
    }
}
