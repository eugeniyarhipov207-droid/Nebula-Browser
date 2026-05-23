package com.example

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.*
import com.example.data.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainBrowserScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainBrowserScreen(viewModel: BrowserViewModel) {
    val coroutineScope = rememberCoroutineScope()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val searchBarText by viewModel.searchBarText.collectAsStateWithLifecycle()
    val activeTab = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()
    val readerModeActive by viewModel.readerModeActive.collectAsStateWithLifecycle()

    // UI state toggles
    var isTabsSheetOpen by remember { mutableStateOf(false) }
    var isHistorySheetOpen by remember { mutableStateOf(false) }
    var isBookmarksSheetOpen by remember { mutableStateOf(false) }
    var isExtensionsSheetOpen by remember { mutableStateOf(false) }
    var isSyncSheetOpen by remember { mutableStateOf(false) }
    var isDownloadsSheetOpen by remember { mutableStateOf(false) }
    var isGeminiPanelOpen by remember { mutableStateOf(false) }
    var isWorkPanelOpen by remember { mutableStateOf(false) }
    var selectedWorkService by remember { mutableStateOf("keep") } // "keep", "gmail", "calendar"

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    val focusManager = LocalFocusManager.current

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Elegant M3 glassmorphic browser bottom controls
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .swipeToSwitchTabs(
                        onSwipeLeft = { viewModel.swipeToNextTab() },
                        onSwipeRight = { viewModel.swipeToPrevTab() }
                    ),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Column {
                    // Small progress indicator if loading
                    if (activeTab?.isLoading == true) {
                        LinearProgressIndicator(
                            progress = { (activeTab.progress / 100f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = { webViewInstance?.goBack() },
                            enabled = activeTab?.canGoBack == true,
                            modifier = Modifier.testTag("nav_back_button")
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }

                        IconButton(
                            onClick = { webViewInstance?.goForward() },
                            enabled = activeTab?.canGoForward == true,
                            modifier = Modifier.testTag("nav_forward_button")
                        ) {
                            Icon(Icons.Default.ArrowForward, contentDescription = "Вперед")
                        }

                        // Circular Home / Quick Dial Action
                        IconButton(
                            onClick = {
                                viewModel.updateActiveTab { it.url = "about:newtab"; it.title = "Новая вкладка" }
                                viewModel.updateSearchBarText("about:newtab")
                                focusManager.clearFocus()
                            },
                            modifier = Modifier.testTag("nav_home_button")
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Домой")
                        }

                        // Split multitasking panel toggle
                        IconButton(
                            onClick = { isWorkPanelOpen = !isWorkPanelOpen },
                            modifier = Modifier.testTag("nav_work_panel_button")
                        ) {
                            Icon(
                                if (isWorkPanelOpen) Icons.Default.List else Icons.Default.Edit,
                                contentDescription = "Google Кабинет",
                                tint = if (isWorkPanelOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }

                        // Gemini native helper pill
                        IconButton(
                            onClick = { isGeminiPanelOpen = !isGeminiPanelOpen },
                            modifier = Modifier.testTag("nav_gemini_button")
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "Gemini Помощник",
                                tint = if (isGeminiPanelOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }

                        // Tab switcher count badge
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { isTabsSheetOpen = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .shadow(2.dp, RoundedCornerShape(6.dp))
                                    .background(
                                        if (activeTab?.isIncognito == true) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .testTag("tabs_badge_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabs.size.toString(),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab?.isIncognito == true) MaterialTheme.colorScheme.onSecondary
                                    else MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        IconButton(
                            onClick = { isHistorySheetOpen = true },
                            modifier = Modifier.testTag("nav_menu_button")
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        // Main container combining WebView content, split panels, and sheets
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AddressBar(
                    searchBarText = searchBarText,
                    activeTab = activeTab,
                    viewModel = viewModel,
                    onFocusChange = { },
                    onNavigate = { input ->
                        val targetUrl = resolveUrlOrSearch(input)
                        viewModel.updateActiveTab { it.url = targetUrl }
                        viewModel.updateSearchBarText(targetUrl)
                        focusManager.clearFocus()
                    },
                    onSyncClick = { isSyncSheetOpen = true },
                    onExtensionsClick = { isExtensionsSheetOpen = true },
                    onBookmarksOpen = { isBookmarksSheetOpen = true },
                    onReadingModeClick = {
                        webViewInstance?.evaluateJavascript("""
                            (function() {
                                var title = document.title || "";
                                var selectors = ["article p", "main p", ".post-content p", ".article-content p", "p"];
                                var paragraphs = [];
                                for (var i = 0; i < selectors.length; i++) {
                                    var found = document.querySelectorAll(selectors[i]);
                                    if (found && found.length > 2) {
                                        paragraphs = Array.from(found).map(p => p.innerText.trim()).filter(t => t.length > 15);
                                        break;
                                    }
                                }
                                if (paragraphs.length === 0) {
                                    paragraphs = Array.from(document.querySelectorAll("p")).map(p => p.innerText.trim()).filter(t => t.length > 5);
                                }
                                if (window.BrowserBridge) {
                                    window.BrowserBridge.onReaderModeContent(title, JSON.stringify(paragraphs));
                                }
                            })()
                        """.trimIndent(), null)
                        viewModel.enterReaderMode()
                    },
                    onRefreshClick = { webViewInstance?.reload() },
                    onStopLoadingClick = { webViewInstance?.stopLoading() }
                )

                // Render Content Workspace (supports side-by-side or stacked multitasking)
                Row(modifier = Modifier.weight(1f)) {
                    // Left panel: Google Work Services Split panel
                    if (isWorkPanelOpen) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            tonalElevation = 2.dp,
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                TabRow(
                                    selectedTabIndex = when (selectedWorkService) {
                                        "keep" -> 0
                                        "gmail" -> 1
                                        "calendar" -> 2
                                        else -> 0
                                    },
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ) {
                                    Tab(
                                        selected = selectedWorkService == "keep",
                                        onClick = { selectedWorkService = "keep" },
                                        text = { Text("Keep", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                    )
                                    Tab(
                                        selected = selectedWorkService == "gmail",
                                        onClick = { selectedWorkService = "gmail" },
                                        text = { Text("Gmail", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                    )
                                    Tab(
                                        selected = selectedWorkService == "calendar",
                                        onClick = { selectedWorkService = "calendar" },
                                        text = { Text("Календарь", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    WorkServiceWebView(serviceType = selectedWorkService)
                                }
                                Button(
                                    onClick = { isWorkPanelOpen = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(4.dp),
                                    colors = ButtonDefaults.textButtonColors()
                                ) {
                                    Text("Закрыть разделение", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Main web view area (or Home dashboard)
                    Box(modifier = Modifier.weight(2f)) {
                        if (activeTab != null && activeTab.url == "about:newtab") {
                            // Native home dashboard in Compose
                            HomeDashboard(
                                onNavigate = { url ->
                                    viewModel.updateActiveTab { it.url = url }
                                    viewModel.updateSearchBarText(url)
                                },
                                viewModel = viewModel,
                                openSync = { isSyncSheetOpen = true }
                            )
                        } else if (activeTab != null) {
                            // Active browser WebView hosting page
                            BrowserWebViewContainer(
                                tab = activeTab,
                                viewModel = viewModel,
                                onWebViewCreated = { webViewInstance = it }
                            )
                        }
                    }

                    // Right panel: Native Gemini helper chatbot
                    if (isGeminiPanelOpen) {
                        Surface(
                            modifier = Modifier
                                .weight(1.5f)
                                .fillMaxHeight(),
                            tonalElevation = 4.dp,
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            GeminiSidePanel(
                                viewModel = viewModel,
                                onClose = { isGeminiPanelOpen = false }
                            )
                        }
                    }
                }
            }

            // Bottom Sheets & Overlays
            if (isTabsSheetOpen) {
                TabsManagerDialog(
                    viewModel = viewModel,
                    onDismiss = { isTabsSheetOpen = false }
                )
            }

            if (isHistorySheetOpen) {
                MenuAndHistoryModalSheet(
                    viewModel = viewModel,
                    onBookmarksClick = { isBookmarksSheetOpen = true; isHistorySheetOpen = false },
                    onDownloadsClick = { isDownloadsSheetOpen = true; isHistorySheetOpen = false },
                    onRefreshClick = { webViewInstance?.reload(); isHistorySheetOpen = false },
                    onDismiss = { isHistorySheetOpen = false }
                )
            }

            if (isDownloadsSheetOpen) {
                DownloadsManagerDialog(
                    viewModel = viewModel,
                    onDismiss = { isDownloadsSheetOpen = false }
                )
            }

            if (isBookmarksSheetOpen) {
                BookmarksManagerDialog(
                    viewModel = viewModel,
                    onLoadUrl = { url ->
                        viewModel.updateActiveTab { it.url = url }
                        viewModel.updateSearchBarText(url)
                        isBookmarksSheetOpen = false
                    },
                    onDismiss = { isBookmarksSheetOpen = false }
                )
            }

            if (isExtensionsSheetOpen) {
                ExtensionsManagerDialog(
                    viewModel = viewModel,
                    onDismiss = { isExtensionsSheetOpen = false }
                )
            }

            if (isSyncSheetOpen) {
                GoogleAccountSyncDialog(
                    viewModel = viewModel,
                    onDismiss = { isSyncSheetOpen = false }
                )
            }

            if (readerModeActive) {
                ReaderModeOverlay(
                    viewModel = viewModel,
                    onDismiss = { viewModel.exitReaderMode() }
                )
            }
        }
    }
}

// --- TOP ADDRESS BAR COMPOSABLE ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AddressBar(
    searchBarText: String,
    activeTab: BrowserTab?,
    viewModel: BrowserViewModel,
    onFocusChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    onSyncClick: () -> Unit,
    onExtensionsClick: () -> Unit,
    onBookmarksOpen: () -> Unit,
    onReadingModeClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onStopLoadingClick: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val isBookmarked = activeTab?.let {
        var bookmarked = false
        val marks by viewModel.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
        bookmarked = marks.any { m -> m.url == it.url }
        bookmarked
    } ?: false

    val blockCount by viewModel.adsBlockedTotal.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()

    var showShieldCard by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Incognito / Google Sync shortcut badge
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (activeTab?.isIncognito == true) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                            else MaterialTheme.colorScheme.secondaryContainer
                        )
                        .clickable { onSyncClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (activeTab?.isIncognito == true) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Режим инкогнито",
                            tint = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.size(16.dp)
                        )
                    } else if (userEmail != null) {
                        Icon(
                            Icons.Default.Done,
                            contentDescription = "Облако синхронизировано",
                            tint = Color(0xFF0F9D58),
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = "Профиль",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Unified search and browser address bar
                Box(modifier = Modifier.weight(1f)) {
                    var textInput by remember(searchBarText) { mutableStateOf(searchBarText) }
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = {
                            textInput = it
                            viewModel.updateSearchBarText(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("address_bar_text_field"),
                        placeholder = {
                            Text(
                                "Поиск Google или URL-адрес",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        leadingIcon = {
                            Icon(
                                if (activeTab?.url?.startsWith("https://") == true) Icons.Default.Lock
                                else Icons.Default.Search,
                                contentDescription = "Статус веб-адреса",
                                modifier = Modifier.size(16.dp),
                                tint = if (activeTab?.url?.startsWith("https://") == true) Color(0xFF0F9D58)
                                else LocalContentColor.current
                            )
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (activeTab?.url != "about:newtab" && activeTab?.url?.startsWith("http") == true) {
                                    IconButton(
                                        onClick = {
                                            if (activeTab.isLoading) onStopLoadingClick() else onRefreshClick()
                                        },
                                        modifier = Modifier.size(28.dp).testTag("refresh_page_button")
                                    ) {
                                        Icon(
                                            imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                            contentDescription = if (activeTab.isLoading) "Остановить" else "Обновить страницу",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))

                                    IconButton(
                                        onClick = onReadingModeClick,
                                        modifier = Modifier.size(28.dp).testTag("reading_mode_button")
                                    ) {
                                        Icon(
                                            Icons.Default.List,
                                            contentDescription = "Режим чтения",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                }

                                if (textInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            textInput = ""
                                            viewModel.updateSearchBarText("")
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Очистить",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                // Interactive shield badge showing adblock total
                                Surface(
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { showShieldCard = !showShieldCard },
                                    color = if (blockCount > 0) Color(0xFFD32F2F).copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Lock,
                                            contentDescription = "Блокировщик",
                                            modifier = Modifier.size(14.dp),
                                            tint = if (blockCount > 0) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (blockCount > 0) {
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = blockCount.toString(),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFD32F2F)
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                if (textInput.trim().isNotEmpty()) {
                                    onNavigate(textInput)
                                }
                            }
                        )
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Puzzle extension icon
                IconButton(
                    onClick = { onExtensionsClick() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Расширения", modifier = Modifier.size(20.dp))
                }

                // Bookmark star
                IconButton(
                    onClick = {
                        activeTab?.let {
                            viewModel.toggleBookmark(it.url, it.title)
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = "В закладки",
                        tint = if (isBookmarked) Color(0xFFF4B400) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Interactive popup stats of blocked items
            if (showShieldCard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .shadow(6.dp, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Космический щит Nebula",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                "Блокирует трекеры, банеры и коммерческую рекламу на сайтах для лучшей скорости.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = blockCount.toString(),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                            Text("заблокировано", fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

// --- COMPOSE CORE INTERFACE CHANNELS (WEBVIEW FOR THE ACTIVE SESSIONS) ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebViewContainer(
    tab: BrowserTab,
    viewModel: BrowserViewModel,
    onWebViewCreated: (WebView) -> Unit
) {
    val context = LocalContext.current
    val extensions by viewModel.extensions.collectAsStateWithLifecycle(initialValue = emptyList())
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())

    val webView = remember(tab.id) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Configure Chrome rendering capabilities & Javascript interfaces
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            // Setup download listener
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                viewModel.startDownload(context, url, userAgent, contentDisposition, mimetype, contentLength)
            }

            // Register standard safe Android JS bridges
            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPageTextExtracted(text: String) {
                    // Update latest extracted text in repository for active Gemini analysis
                    viewModel.extractedPageText = text
                }

                @JavascriptInterface
                fun onReaderModeContent(title: String, paragraphsJson: String) {
                    viewModel.updateReaderContent(title, paragraphsJson)
                }

                @JavascriptInterface
                fun logoutFromGoogle() {
                    viewModel.logoutGoogle()
                }
            }, "BrowserBridge")

            webViewClient = object : WebViewClient() {
                // Real intercepted script blocklist ad blocker code
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (AdBlocker.isAdRequest(url)) {
                        viewModel.registerAdBlocked()
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    viewModel.updateActiveTab {
                        it.url = url ?: "about:newtab"
                        it.isLoading = true
                        it.progress = 10
                    }
                    if (url != null) {
                        viewModel.updateSearchBarText(url)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    viewModel.updateActiveTab {
                        it.url = url ?: "about:newtab"
                        it.isLoading = false
                        it.canGoBack = view?.canGoBack() ?: false
                        it.canGoForward = view?.canGoForward() ?: false
                    }
                    val fullTitle = view?.title ?: ""
                    viewModel.updateActiveTab { it.title = if (fullTitle.isEmpty()) "Без имени" else fullTitle }
                    
                    // Add URL loaded to persistent history database
                    if (url != null) {
                        viewModel.addHistoryItem(url, fullTitle)
                    }

                    // Dynamically Execute/Inject chrome active scripts when requested
                    extensions.filter { it.isEnabled }.forEach { ext ->
                        view?.evaluateJavascript(ext.scriptContent, null)
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    viewModel.updateActiveTab {
                        it.progress = newProgress
                        it.isLoading = newProgress < 100
                    }
                }
            }
        }
    }

    DisposableEffect(tab.id) {
        onWebViewCreated(webView)
        onDispose {
            // Safe teardown
        }
    }

    // Reactively refresh urls loading if tab parameters differs
    LaunchedEffect(tab.url) {
        if (webView.url != tab.url && tab.url != "about:newtab") {
            webView.loadUrl(tab.url)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier
            .fillMaxSize()
            .testTag("browser_webview")
    )
}

// --- GOOGLE WORKSPACE PRESETS MULTITASKING ---
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WorkServiceWebView(serviceType: String) {
    val context = LocalContext.current
    val targetUrl = when (serviceType) {
        "keep" -> "https://keep.google.com"
        "gmail" -> "https://mail.google.com"
        "calendar" -> "https://calendar.google.com"
        else -> "https://keep.google.com"
    }

    val webView = remember(serviceType) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false // Maintain inside the workspace frame
                }
            }
            loadUrl(targetUrl)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = Modifier.fillMaxSize()
    )
}

// --- CUSTOM GOOGLE HOME DASHBOARD FOR NEW TABS ---
@Composable
fun HomeDashboard(
    onNavigate: (String) -> Unit,
    viewModel: BrowserViewModel,
    openSync: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var inputText by remember { mutableStateOf("") }
    val blockTotal by viewModel.adsBlockedTotal.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Elegant Cosmic Browser Logo
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(80.dp)
                    .shadow(4.dp, CircleShape)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Nebula Browser",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.SansSerif,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = (-0.5).sp
            )
            Text(
                "Ваш безопасный космос в сети",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Massive centered Search Pill
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .height(56.dp)
                    .testTag("home_search_bar"),
                placeholder = { Text("Поиск Google или введите URL", fontSize = 14.sp) },
                shape = RoundedCornerShape(28.dp),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (inputText.trim().isNotEmpty()) {
                            onNavigate(resolveUrlOrSearch(inputText))
                            focusManager.clearFocus()
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Speed Dials Grid containing Google workspace icons
            Text("Сервисы быстрого старта Google", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                SpeedDialItem(title = "Gmail", icon = Icons.Default.Email, iconColor = Color(0xFFEA4335)) {
                    onNavigate("https://mail.google.com")
                }
                SpeedDialItem(title = "Google Keep", icon = Icons.Default.Edit, iconColor = Color(0xFFFBBC05)) {
                    onNavigate("https://keep.google.com")
                }
                SpeedDialItem(title = "Calendar", icon = Icons.Default.Settings, iconColor = Color(0xFF4285F4)) {
                    onNavigate("https://calendar.google.com")
                }
                SpeedDialItem(title = "A.I. Gemini", icon = Icons.Default.Star, iconColor = Color(0xFF9B51E0)) {
                    onNavigate("https://gemini.google.com")
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Live status stats cards
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .clickable { openSync() },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (userEmail != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (userEmail != null) Color(0xFF0F9D58) else MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (userEmail != null) "Аккаунт: $userEmail" else "Синхронизация отключена",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = if (userEmail != null) "История и закладки синхронизируются в реальном времени"
                            else "Войдите через Google для бэкапа вкладок",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (userEmail == null) {
                        Button(
                            onClick = { openSync() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("Войти", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpeedDialItem(title: String, icon: ImageVector, iconColor: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(27.dp))
                .shadow(2.dp, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(26.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

// --- RIGHT SLIDE GEMINI COPILOT SIDE PANEL ---
@Composable
fun GeminiSidePanel(
    viewModel: BrowserViewModel,
    onClose: () -> Unit
) {
    val messages by viewModel.geminiMessages.collectAsStateWithLifecycle()
    val isThinking by viewModel.geminiThinking.collectAsStateWithLifecycle()
    var promptInput by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Headers controls
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, "AI", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Gemini Copilot", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Row {
                    IconButton(onClick = { viewModel.clearGeminiChat() }) {
                        Icon(Icons.Default.Delete, "Очистить чат", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Context utilities bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            AssistChip(
                onClick = { viewModel.askGeminiToSummarizePage() },
                label = { Text("Саммари страницы", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.List, null, modifier = Modifier.size(12.dp)) }
            )
            Spacer(modifier = Modifier.width(6.dp))
            AssistChip(
                onClick = { viewModel.sendGeminiCopilotMessage("Объясни сложные термины на этом сайте понятным языком.") },
                label = { Text("Пояснить термины", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Info, null, modifier = Modifier.size(12.dp)) }
            )
        }

        // Messages scrolling log
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Star,
                        null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Привет! Я ваш ИИ-помощник Gemini.\n" +
                        "Задайте мне любой вопрос о текущей странице или используйте кнопки Саммари выше.",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { msg ->
                        GeminiMessageBubble(msg)
                    }
                    if (isThinking) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Копилот думает...", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }
        }

        // Input prompt bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    placeholder = { Text("Спросить у ассистента...", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (promptInput.trim().isNotEmpty()) {
                                viewModel.sendGeminiCopilotMessage(promptInput)
                                promptInput = ""
                            }
                        }
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        if (promptInput.trim().isNotEmpty()) {
                            viewModel.sendGeminiCopilotMessage(promptInput)
                            promptInput = ""
                        }
                    },
                    modifier = Modifier.testTag("gemini_send_prompt")
                ) {
                    Icon(Icons.Default.Send, "Отправить", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun GeminiMessageBubble(msg: GeminiMessage) {
    val isUser = msg.sender == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 0.dp,
                bottomEnd = if (isUser) 0.dp else 12.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer
            ),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = if (isUser) "Пользователь" else "Gemini Copilot",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = msg.text,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// --- CHROME EXPERIMENTAL EXTENSIONS DIALOG ---
@Composable
fun ExtensionsManagerDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val extensions by viewModel.extensions.collectAsStateWithLifecycle(initialValue = emptyList())
    
    // Add custom extensions variables
    var showAddForm by remember { mutableStateOf(false) }
    var newExtName by remember { mutableStateOf("") }
    var newExtDesc by remember { mutableStateOf("") }
    var newExtScript by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Веб-расширения (Chrome Scripts)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showAddForm) {
                    Text("Создать скрипт-расширение", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newExtName,
                        onValueChange = { newExtName = it },
                        label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newExtDesc,
                        onValueChange = { newExtDesc = it },
                        label = { Text("Описание") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newExtScript,
                        onValueChange = { newExtScript = it },
                        label = { Text("Код Javascript") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showAddForm = false }) { Text("Отмена") }
                        Button(
                            onClick = {
                                if (newExtName.isNotEmpty() && newExtScript.isNotEmpty()) {
                                    viewModel.createCustomExtension(newExtName, newExtDesc, newExtScript)
                                    newExtName = ""
                                    newExtDesc = ""
                                    newExtScript = ""
                                    showAddForm = false
                                }
                            }
                        ) { Text("Создать") }
                    }
                } else {
                    Button(
                        onClick = { showAddForm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Установить пользовательский скрипт")
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(extensions) { ext ->
                            ExtensionRow(ext, viewModel)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Выйти") }
        }
    )
}

@Composable
fun ExtensionRow(ext: ExtensionEntity, viewModel: BrowserViewModel) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ext.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (ext.isBuiltIn) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Магазин", fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(ext.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            }
            Switch(
                checked = ext.isEnabled,
                onCheckedChange = { enabled -> viewModel.toggleExtensionEnabled(ext.id, enabled) }
            )
            if (!ext.isBuiltIn) {
                IconButton(onClick = { viewModel.deleteUserExtension(ext) }) {
                    Icon(Icons.Default.Delete, "Удалить", tint = Color.Red, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// --- GOOGLE ACC SYNC CLOUD BACKUP DIALOG ---
@Composable
fun GoogleAccountSyncDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())

    // 1: Choose Account, 2: Manual Email, 3: Manual Password, 4: Consent, 5: Connecting safely (Loader)
    var authStep by remember { mutableStateOf(1) }
    var selectedEmail by remember { mutableStateOf("user@gmail.com") }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    if (authStep == 5) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1600)
            viewModel.loginWithGoogle(selectedEmail)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp),
        title = null, // Custom headers inside the body to match authentic screens
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                if (userEmail == null) {
                    when (authStep) {
                        1 -> {
                            // SCREEN 1: CHOOSE AN ACCOUNT
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFFBBC05), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("g", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("l", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("e", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign in with Google", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
                            }

                            // App Badge
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E1E1F))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "NEBULA SYNC",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Choose an account",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "to continue to Nebula Sync",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            )

                            // Account 1: user mock
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedEmail = "user@gmail.com"
                                        authStep = 4 // Direct to Consent
                                    },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4285F4)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("U", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "User", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text(text = "user@gmail.com", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Account 2: Use another account
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        authStep = 2 // Manual input
                                    },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(text = "Use another account", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = "Before using this app, you can review Nebula Sync's Privacy Policy and Terms of Service.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }

                        2 -> {
                            // SCREEN 2: MANUAL EMAIL INPUT
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFFBBC05), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("g", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("l", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("e", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }

                            Text("Sign in", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("to continue to Nebula Sync", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 16.dp))

                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = {
                                    emailInput = it
                                    emailError = if (it.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()) {
                                        "Неверный формат почты"
                                    } else null
                                },
                                label = { Text("Email or phone") },
                                isError = emailError != null,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (emailError != null) {
                                Text(emailError ?: "", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { authStep = 1 }) {
                                    Text("Back")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (emailInput.isBlank()) {
                                            emailError = "Введите адрес электронной почты"
                                        } else if (android.util.Patterns.EMAIL_ADDRESS.matcher(emailInput).matches()) {
                                            selectedEmail = emailInput
                                            authStep = 3
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                                ) {
                                    Text("Next")
                                }
                            }
                        }

                        3 -> {
                            // SCREEN 3: MANUAL PASSWORD INPUT
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("G", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("o", color = Color(0xFFFBBC05), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("g", color = Color(0xFF4285F4), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("l", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("e", color = Color(0xFFEA4335), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }

                            Surface(
                                onClick = { authStep = 2 },
                                modifier = Modifier.padding(bottom = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Person, null, modifier = Modifier.size(14.dp), tint = Color(0xFF4285F4))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(selectedEmail, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(10.dp).rotate(90f))
                                }
                            }

                            Text("Welcome", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text("Enter your password to verify your identity", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(bottom = 16.dp))

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = {
                                    passwordInput = it
                                    passwordError = null
                                },
                                label = { Text("Enter your password") },
                                singleLine = true,
                                isError = passwordError != null,
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Lock, null, tint = Color(0xFF4285F4)) },
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                            )
                            if (passwordError != null) {
                                Text(passwordError ?: "", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { authStep = 2 }) {
                                    Text("Back")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (passwordInput.isBlank()) {
                                            passwordError = "Введите пароль"
                                        } else {
                                            authStep = 4
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                                ) {
                                    Text("Next")
                                }
                            }
                        }

                        4 -> {
                            // SCREEN 4: OAUTH 2.0 CONSENT PAGE (Replicating Image 2)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF1E1E1F))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "NEBULA SYNC",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            Text(
                                text = "Sign in to Nebula Sync",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Custom Dropdown Pill style mimicking "Choose an account" selector
                            Surface(
                                modifier = Modifier.clickable { authStep = 1 },
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4285F4)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("U", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = selectedEmail,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("▼", fontSize = 8.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text(
                                text = "Google will allow Nebula Sync to access this info about you",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            )

                            // Permissions Columns
                            Row(
                                modifier = Modifier.padding(bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("User", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Name and profile picture", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            Row(
                                modifier = Modifier.padding(bottom = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(selectedEmail, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Email address", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Review Nebula Sync's Privacy Policy and Terms of Service to understand how Nebula Sync will process and protect your data.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "To make changes at any time, go to your Google Account.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Learn more about Sign in with Google.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF4285F4),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(28.dp))

                            // Two Pill buttons on bottom matching screenshots
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { authStep = 1 },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(22.dp)
                                ) {
                                    Text("Cancel", fontSize = 14.sp)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Button(
                                    onClick = { authStep = 5 },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4285F4))
                                ) {
                                    Text("Continue", fontSize = 14.sp)
                                }
                            }
                        }

                        5 -> {
                            // SCREEN 5: SECURE CONNECTION LOADER
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = Color(0xFF4285F4),
                                    strokeWidth = 4.dp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Настройка защищенного соединения...", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("Nebula Sync Cloud & accounts.google.com API", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                } else {
                    // LINKED / CONNECTED STATE
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color(0xFF4285F4).copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color(0xFF4285F4))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(userEmail ?: "", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("Google Cloud Sync: ACTIVE", fontSize = 11.sp, color = Color(0xFF0F9D58), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            viewModel.createNewTab("https://accounts.google.com")
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color(0xFF4285F4)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4285F4))
                    ) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Управление на accounts.google.com", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Синхронизируемые данные:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Избранные закладки", fontSize = 12.sp)
                        Text("${bookmarks.size} шт.", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("История браузера", fontSize = 12.sp)
                        Text("${history.size} записей", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (syncing) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("Синхронизация данных...", fontSize = 11.sp)
                        }
                    } else {
                        Text(
                            text = if (lastSync != null) "Последний кэш бэкапа: ${formatTime(lastSync!!)}"
                            else "Автоматическая синхронизация активна",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { viewModel.logoutGoogle() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Выйти", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.syncCloudData() },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F9D58))
                        ) {
                            Text("Синхронизировать", fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

// --- BOOKMARKS MANAGER DIALOG ---
@Composable
fun BookmarksManagerDialog(
    viewModel: BrowserViewModel,
    onLoadUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle(initialValue = emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, null, tint = Color(0xFFF4B400))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Закладки и избранное", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (bookmarks.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("У вас пока нет закладок.\nНажмите звездочку в адресной строке", textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(bookmarks) { bookmark ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onLoadUrl(bookmark.url) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(bookmark.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                    Text(bookmark.url, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                }
                                IconButton(onClick = { viewModel.removeBookmark(bookmark) }) {
                                    Icon(Icons.Default.Delete, "Удалить", modifier = Modifier.size(16.dp))
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.clearAllBookmarks() }) { Text("Очистить все") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Выйти") }
        }
    )
}

@Composable
fun CustomFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

// --- TABS MANAGER MULTI-VIEW SHEET ---
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabsManagerDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()

    var selectedGroupFilter by remember { mutableStateOf("Все") }
    var showCustomGroupDialog by remember { mutableStateOf<String?>(null) } // tabId to assign group to
    var customGroupNameInput by remember { mutableStateOf("") }

    val availableGroups = remember(tabs) {
        val groups = listOf("Работа", "Учёба", "Развлечения", "Соцсети")
        val customGroups = tabs.map { it.tabGroup }.filter { it.isNotBlank() && it !in groups }.distinct()
        groups + customGroups
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Диспетчер вкладок", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { viewModel.createNewTab(); onDismiss() }) {
                    Icon(Icons.Default.Add, "Добавить вкладку", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Quick tabs pre-seed creators
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.createNewTab(isIncognito = false); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Стандарт", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { viewModel.createNewTab(isIncognito = true); onDismiss() },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🕶 Инкогнито", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Dynamic Group Filters Horizontal Scroll
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        CustomFilterChip(
                            selected = selectedGroupFilter == "Все",
                            onClick = { selectedGroupFilter = "Все" },
                            label = "Все (${tabs.size})"
                        )
                    }
                    item {
                        CustomFilterChip(
                            selected = selectedGroupFilter == "Закрепленные",
                            onClick = { selectedGroupFilter = "Закрепленные" },
                            label = "📌 Закрепленные (${tabs.count { it.isPinned }})"
                        )
                    }
                    availableGroups.forEach { groupName ->
                        val groupTabsCount = tabs.count { it.tabGroup == groupName }
                        item {
                            CustomFilterChip(
                                selected = selectedGroupFilter == groupName,
                                onClick = { selectedGroupFilter = groupName },
                                label = "$groupName ($groupTabsCount)"
                            )
                        }
                    }
                }

                Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Render tabs list matching filter
                val filteredTabs = remember(tabs, selectedGroupFilter) {
                    when (selectedGroupFilter) {
                        "Все" -> tabs
                        "Закрепленные" -> tabs.filter { it.isPinned }
                        else -> tabs.filter { it.tabGroup == selectedGroupFilter }
                    }
                }

                if (filteredTabs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Нет вкладок в этой группе", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .padding(top = 4.dp)
                    ) {
                        items(filteredTabs) { tab ->
                            val isCurrent = tab.id == activeTabId
                            var showGroupsDropdown by remember { mutableStateOf(false) }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                    else if (tab.isIncognito) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                ),
                                border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Pin indicator button
                                        IconButton(
                                            onClick = { viewModel.togglePinTab(tab.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = "Закрепить узел",
                                                tint = if (tab.isPinned) Color(0xFFF4B400) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(4.dp))

                                        // Web Title details
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    viewModel.selectTab(tab.id)
                                                    onDismiss()
                                                }
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (tab.isPinned) {
                                                    Icon(Icons.Default.Lock, null, tint = Color(0xFFF4B400), modifier = Modifier.size(10.dp))
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                }
                                                Text(
                                                    text = tab.title,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Text(
                                                text = if (tab.isIncognito) "Конфиденциальная вкладка" else tab.url,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.secondary
                                            )

                                            // Display active group as clear tag chip
                                            if (tab.tabGroup.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Surface(
                                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                                    shape = RoundedCornerShape(4.dp),
                                                    modifier = Modifier.height(18.dp)
                                                ) {
                                                    Text(
                                                        text = tab.tabGroup,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Ordering buttons
                                        IconButton(
                                            onClick = { viewModel.moveTabUp(tab.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp).rotate(90f))
                                        }

                                        IconButton(
                                            onClick = { viewModel.moveTabDown(tab.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp).rotate(270f))
                                        }

                                        IconButton(
                                            onClick = { viewModel.closeTab(tab.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, "Закрыть", modifier = Modifier.size(16.dp))
                                        }
                                    }

                                    // Group assignments
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { showGroupsDropdown = true },
                                            modifier = Modifier.height(28.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Settings, null, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Группа: ${tab.tabGroup.ifBlank { "Без группы" }}", fontSize = 10.sp)
                                        }

                                        DropdownMenu(
                                            expanded = showGroupsDropdown,
                                            onDismissRequest = { showGroupsDropdown = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Без группы", fontSize = 12.sp) },
                                                onClick = {
                                                    viewModel.setTabGroup(tab.id, "")
                                                    showGroupsDropdown = false
                                                }
                                            )
                                            availableGroups.forEach { groupOption ->
                                                DropdownMenuItem(
                                                    text = { Text(groupOption, fontSize = 12.sp) },
                                                    onClick = {
                                                        viewModel.setTabGroup(tab.id, groupOption)
                                                        showGroupsDropdown = false
                                                    }
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text("+ Новая группа...", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                                onClick = {
                                                    showCustomGroupDialog = tab.id
                                                    customGroupNameInput = ""
                                                    showGroupsDropdown = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        }
    )

    // Inner dialog to add a custom group
    if (showCustomGroupDialog != null) {
        AlertDialog(
            onDismissRequest = { showCustomGroupDialog = null },
            title = { Text("Новая группа вкладок", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = customGroupNameInput,
                    onValueChange = { customGroupNameInput = it },
                    label = { Text("Название группы") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tabId = showCustomGroupDialog
                        if (tabId != null && customGroupNameInput.isNotBlank()) {
                            viewModel.setTabGroup(tabId, customGroupNameInput.trim())
                        }
                        showCustomGroupDialog = null
                    }
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomGroupDialog = null }) { Text("Отмена") }
            }
        )
    }
}

// --- MENU AND HISTORY LIST MODAL SHEET ---
@Composable
fun MenuAndHistoryModalSheet(
    viewModel: BrowserViewModel,
    onBookmarksClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val history by viewModel.history.collectAsStateWithLifecycle(initialValue = emptyList())
    var historySearchInput by remember { mutableStateOf("") }
    val filteredHistory = if (historySearchInput.isEmpty()) history
    else history.filter { it.title.contains(historySearchInput, ignoreCase = true) || it.url.contains(historySearchInput, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("История просмотра и Меню", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    TextButton(onClick = onBookmarksClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFF4B400))
                            Text("Закладки", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = onDownloadsClick) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.rotate(90f))
                            Text("Загрузки", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { onRefreshClick(); onDismiss() }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Refresh, null, tint = Color(0xFF0F9D58))
                            Text("Обновить", fontSize = 12.sp)
                        }
                    }
                    TextButton(onClick = { viewModel.createNewTab(); onDismiss() }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null)
                            Text("Новая вкладка", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                
                // --- MINI AUDIO PLAYER ---
                val isPlaying by viewModel.isPlayingText.collectAsStateWithLifecycle()
                val currentTrackTitle by viewModel.currentTrackTitle.collectAsStateWithLifecycle()
                val playlist by viewModel.playlist.collectAsStateWithLifecycle()
                
                val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
                val tabsState by viewModel.tabs.collectAsStateWithLifecycle()
                val activeTab = tabsState.find { id -> id.id == activeTabId }

                Surface(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Музыкальный Плеер Nebula",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Text(
                            text = currentTrackTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Control buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { viewModel.prevAudio() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowBack,
                                        contentDescription = "Предыдущий трек",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                
                                Button(
                                    onClick = { viewModel.playPauseAudio() },
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Close else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Пауза" else "Воспроизведение",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (isPlaying) "Пауза" else "Старт", fontSize = 11.sp)
                                }
                                
                                IconButton(
                                    onClick = { viewModel.nextAudio() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowForward,
                                        contentDescription = "Следующий трек",
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            
                            // Save track to bookmarks button
                            TextButton(
                                onClick = { viewModel.bookmarkCurrentTrack() },
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Добавить трек в закладки",
                                    tint = Color(0xFFF4B400),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("В закладки", fontSize = 11.sp)
                            }
                        }
                        
                        // If active tab contains audio links style website, allow adding it!
                        if (activeTab != null && activeTab.url != "about:newtab") {
                            val activeUrl = activeTab.url
                            val isAudioPossible = activeUrl.endsWith(".mp3") || activeUrl.endsWith(".ogg") || activeUrl.endsWith(".wav") || activeUrl.contains("audio") || activeUrl.contains("music")
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.addTrackToPlaylist(activeTab.title, activeUrl)
                                    },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isAudioPossible) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (isAudioPossible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isAudioPossible) "Добавить аудио с этого сайта в плеер" else "Добавить текущий сайт как аудио-ссылку",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isAudioPossible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Text("История браузера", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = historySearchInput,
                    onValueChange = { historySearchInput = it },
                    placeholder = { Text("Поиск по истории...", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp)) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (filteredHistory.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("История пуста.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(filteredHistory) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                                    Text(item.url, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                }
                                IconButton(onClick = { viewModel.removeHistoryItem(item) }) {
                                    Icon(Icons.Default.Delete, "Убрать", modifier = Modifier.size(14.dp))
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.clearAllHistory() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) { Text("Очистить историю") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Выйти") }
        }
    )
}

// --- HELPER UTILS FOR URL RESOLUTIONS ---
private fun resolveUrlOrSearch(input: String): String {
    val clean = input.trim()
    if (clean.startsWith("http://") || clean.startsWith("https://")) {
        return clean
    }
    if (clean.endsWith(".com") || clean.endsWith(".org") || clean.endsWith(".net") ||
        clean.endsWith(".ru") || clean.endsWith(".io") || clean.endsWith(".gov") || clean.endsWith(".edu")
    ) {
        return "https://$clean"
    }
    // Perform standard safe URLEscaped search lookup via Google
    val escaped = try {
        URLEncoder.encode(clean, "UTF-8")
    } catch (e: Exception) {
        clean
    }
    return "https://www.google.com/search?q=$escaped"
}

private fun formatTime(timeMs: Long): String {
    val df = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
    return df.format(java.util.Date(timeMs))
}

@Composable
fun ReaderModeOverlay(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val readerTitle by viewModel.readerTitle.collectAsStateWithLifecycle()
    val readerParagraphs by viewModel.readerParagraphs.collectAsStateWithLifecycle()
    val fontSize by viewModel.readerFontSize.collectAsStateWithLifecycle()
    val fontFamily by viewModel.readerFontFamily.collectAsStateWithLifecycle()

    var themeType by remember { mutableStateOf("Light") } // "Light", "Dark", "Sepia"

    val backgroundColor = when (themeType) {
        "Dark" -> Color(0xFF1E1E1E)
        "Sepia" -> Color(0xFFF4ECD8)
        else -> Color(0xFFFBFBFB)
    }

    val textColor = when (themeType) {
        "Dark" -> Color(0xFFE0E0E0)
        "Sepia" -> Color(0xFF3F2B00)
        else -> Color(0xFF1C1C1C)
    }

    val fontStyle = if (fontFamily == "Serif") FontFamily.Serif else FontFamily.SansSerif

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(16.dp)
            .testTag("reader_mode_overlay")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Reader Mode Control Panel (Top Controls)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = textColor)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Font Family Selector Choice
                    FilledTonalButton(
                        onClick = {
                            viewModel.setReaderFontFamily(if (fontFamily == "SansSerif") "Serif" else "SansSerif")
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(if (fontFamily == "SansSerif") "Засечки" else "Без засечек", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Font Size Minus
                    IconButton(
                        onClick = { viewModel.setReaderFontSize(fontSize - 2) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A-", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
                    }

                    // Font Size Plus
                    IconButton(
                        onClick = { viewModel.setReaderFontSize(fontSize + 2) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Text("A+", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = textColor)
                    }

                    // Theme Cycler
                    IconButton(
                        onClick = {
                            themeType = when (themeType) {
                                "Light" -> "Sepia"
                                "Sepia" -> "Dark"
                                else -> "Light"
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = when (themeType) {
                                "Dark" -> Icons.Default.Check
                                "Sepia" -> Icons.Default.Refresh
                                else -> Icons.Default.Menu
                            },
                            contentDescription = "Сменить тему",
                            tint = textColor
                        )
                    }
                }
            }

            Divider(color = textColor.copy(alpha = 0.2f))

            // Body content paragraphs
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                item {
                    Text(
                        text = readerTitle,
                        fontSize = (fontSize + 6).sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        fontFamily = fontStyle,
                        lineHeight = (fontSize * 1.5).sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }

                if (readerParagraphs.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    items(readerParagraphs) { para ->
                        Text(
                            text = para,
                            fontSize = fontSize.sp,
                            fontWeight = FontWeight.Normal,
                            color = textColor,
                            fontFamily = fontStyle,
                            lineHeight = (fontSize * 1.6).sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

fun Modifier.swipeToSwitchTabs(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    onSwipeDown: (() -> Unit)? = null
): Modifier = this.pointerInput(Unit) {
    var totalDragX = 0f
    var totalDragY = 0f
    detectDragGestures(
        onDragStart = {
            totalDragX = 0f
            totalDragY = 0f
        },
        onDragEnd = {
            val absX = kotlin.math.abs(totalDragX)
            val absY = kotlin.math.abs(totalDragY)
            if (absX > absY) {
                if (totalDragX > 150f) {
                    onSwipeRight() // swipe right => prev tab
                } else if (totalDragX < -150f) {
                    onSwipeLeft() // swipe left => next tab
                }
            } else if (onSwipeDown != null && totalDragY > 150f) {
                onSwipeDown() // swipe down => reload
            }
        },
        onDrag = { change, dragAmount ->
            totalDragX += dragAmount.x
            totalDragY += dragAmount.y
            val isGenuineDrag = kotlin.math.abs(totalDragX) > 24f || kotlin.math.abs(totalDragY) > 24f
            if (isGenuineDrag) {
                change.consume()
            }
        }
    )
}

// --- DOWNLOADS MANAGER DIALOG ---
@Composable
fun DownloadsManagerDialog(
    viewModel: BrowserViewModel,
    onDismiss: () -> Unit
) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Загрузки", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.clearAllDownloads() }) {
                    Icon(Icons.Default.Delete, "Очистить список", tint = Color.Red, modifier = Modifier.size(20.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (downloads.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Нет загруженных файлов", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(downloads) { download ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowForward,
                                    contentDescription = null,
                                    tint = when (download.status) {
                                        "COMPLETED" -> Color(0xFF34A853)
                                        "FAILED" -> Color(0xFFEA4335)
                                        "DOWNLOADING" -> Color(0xFF1A73E8)
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(28.dp).rotate(90f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = download.fileName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (download.status == "DOWNLOADING") {
                                        LinearProgressIndicator(
                                            progress = { download.progress / 100f },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = MaterialTheme.colorScheme.primary,
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text("${download.progress}% • Скачивание...", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    } else {
                                        val statusText = when (download.status) {
                                            "COMPLETED" -> "Завершено"
                                            "FAILED" -> "Ошибка"
                                            "PAUSED" -> "Приостановлено"
                                            else -> "Ожидание"
                                        }
                                        val sizeText = if (download.contentLength > 0) {
                                            val kb = download.contentLength / 1024
                                            if (kb > 1024) String.format("%.1f MБ", kb / 1024f) else "$kb KБ"
                                        } else "—"
                                        Text("$statusText • $sizeText", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                Row {
                                    if (download.status == "COMPLETED" && download.filePath != null) {
                                        IconButton(onClick = {
                                            try {
                                                val file = java.io.File(download.filePath)
                                                if (file.exists()) {
                                                    val openIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.provider",
                                                            file
                                                        )
                                                        setDataAndType(uri, download.mimeType ?: "*/*")
                                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(openIntent)
                                                } else {
                                                    val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    context.startActivity(intent)
                                                } catch (ex: Exception) {
                                                    android.widget.Toast.makeText(context, "Не удалось открыть файл", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.Done, "Открыть", tint = Color(0xFF34A853), modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    IconButton(onClick = { viewModel.deleteDownload(download) }) {
                                        Icon(Icons.Default.Delete, "Убрать", modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

fun getGoogleAccountHtml(email: String?, bookmarksCount: Int, historyCount: Int): String {
    val mail = if (email.isNullOrEmpty()) "Аккаунт не привязан" else email
    val isLinked = !email.isNullOrEmpty()
    val avatarChar = if (isLinked) mail.firstOrNull()?.uppercase() ?: "G" else "G"
    
    return """
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Google Account | Доступ приложений</title>
        <link href="https://fonts.googleapis.com/css2?family=Google+Sans:wght@400;500;700&family=Roboto:wght@400;500&display=swap" rel="stylesheet">
        <style>
            :root {
                --bg-color: #f8f9fa;
                --card-bg: #ffffff;
                --text-main: #202124;
                --text-sub: #5f6368;
                --border-color: #dadce0;
                --google-blue: #1a73e8;
                --google-red: #d93025;
                --hover-blue: #1557b0;
                --accent-green: #e6f4ea;
                --accent-green-text: #137333;
            }
            @media (prefers-color-scheme: dark) {
                :root {
                    --bg-color: #1a1b1e;
                    --card-bg: #2d2e30;
                    --text-main: #e8eaed;
                    --text-sub: #9aa0a6;
                    --border-color: #3c4043;
                    --google-blue: #8ab4f8;
                    --google-red: #f28b82;
                    --hover-blue: #669df6;
                    --accent-green: #137333;
                    --accent-green-text: #e6f4ea;
                }
            }
            body {
                font-family: 'Roboto', 'Google Sans', sans-serif;
                margin: 0;
                padding: 0;
                background-color: var(--bg-color);
                color: var(--text-main);
                -webkit-font-smoothing: antialiased;
            }
            .header {
                background: var(--card-bg);
                border-bottom: 1px solid var(--border-color);
                padding: 12px 24px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                position: sticky;
                top: 0;
                z-index: 100;
            }
            .logo {
                display: flex;
                align-items: center;
                font-family: 'Google Sans', sans-serif;
                font-size: 22px;
                font-weight: 500;
            }
            .logo span.blue { color: #4285F4; }
            .logo span.red { color: #EA4335; }
            .logo span.yellow { color: #FBBC05; }
            .logo span.blue-two { color: #4285F4; }
            .logo span.green { color: #34A853; }
            .logo span.red-two { color: #EA4335; }
            .logo .brand-text {
                margin-left: 8px;
                font-size: 16px;
                font-weight: 400;
                color: var(--text-sub);
            }
            .container {
                max-width: 680px;
                margin: 24px auto;
                padding: 0 16px;
            }
            .card {
                background: var(--card-bg);
                border-radius: 12px;
                border: 1px solid var(--border-color);
                padding: 24px;
                margin-bottom: 16px;
                box-shadow: 0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15);
            }
            .user-info {
                display: flex;
                align-items: center;
                margin-bottom: 20px;
            }
            .avatar {
                width: 48px;
                height: 48px;
                background-color: #4285F4;
                color: white;
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 20px;
                font-family: 'Google Sans', sans-serif;
                font-weight: bold;
                margin-right: 16px;
            }
            .title {
                font-family: 'Google Sans', sans-serif;
                font-size: 18px;
                font-weight: 500;
                margin-top: 0;
                margin-bottom: 8px;
                color: var(--google-blue);
            }
            .subtitle {
                color: var(--text-sub);
                font-size: 13px;
                line-height: 1.6;
                margin-bottom: 16px;
            }
            .badge {
                display: inline-flex;
                align-items: center;
                padding: 6px 12px;
                border-radius: 20px;
                font-size: 12px;
                font-weight: 500;
                background-color: var(--accent-green);
                color: var(--accent-green-text);
                margin-bottom: 12px;
            }
            .btn-danger {
                background-color: var(--google-red);
                color: #ffffff;
                border: none;
                border-radius: 4px;
                padding: 10px 20px;
                font-size: 13px;
                font-weight: 500;
                font-family: 'Google Sans', sans-serif;
                cursor: pointer;
                transition: background-color 0.2s;
            }
            .btn-danger:hover {
                opacity: 0.9;
            }
            .detail-row {
                display: flex;
                justify-content: space-between;
                border-bottom: 1px solid var(--border-color);
                padding: 12px 0;
                font-size: 14px;
            }
            .detail-label {
                font-weight: 500;
                color: var(--text-main);
            }
            .detail-value {
                color: var(--text-sub);
            }
            .scope-box {
                background: rgba(66, 133, 244, 0.05);
                border: 1px dashed var(--google-blue);
                border-radius: 8px;
                padding: 12px;
                margin-top: 14px;
                font-size: 12px;
                line-height: 1.5;
            }
        </style>
    </head>
    <body>
        <div class="header">
            <div class="logo">
                <span class="blue">G</span>
                <span class="red">o</span>
                <span class="yellow">o</span>
                <span class="blue-two">g</span>
                <span class="green">l</span>
                <span class="red-two">e</span>
                <span class="brand-text">Аккаунт</span>
            </div>
            <div style="font-size: 13px; color: var(--text-sub);">Безопасность и доступы</div>
        </div>
        
        <div class="container">
            <div class="card">
                <div class="user-info">
                    <div class="avatar">${avatarChar}</div>
                    <div>
                        <div style="font-weight:bold; font-size:16px;">${mail}</div>
                        <div style="font-size:12px; color: var(--text-sub);">Пользователь браузера</div>
                    </div>
                </div>
                
                ${if (isLinked) """
                <div class="badge">✓ Синхронизация Google Cloud подключена</div>
                <h2 class="title" style="margin-top:10px;">Доступ стороннего приложения: Nebula Services</h2>
                <p class="subtitle">Приложение <strong>Nebula Sync & Browser</strong> связано с вашим Google Аккаунтом. Это позволяет безопасно резервировать и синхронизировать ваши личные данные на защищенных облачных базах Google.</p>
                
                <div class="detail-row">
                    <div class="detail-label">Клиент приложения:</div>
                    <div class="detail-value" style="font-weight:bold; color: var(--google-blue);">Nebula Mobile App</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Технология синхронизации:</div>
                    <div class="detail-value">Google API & Room Cloud Services</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Серверный шлюз:</div>
                    <div class="detail-value">node.europe-west2.run.app (Google Cloud SQL)</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Резервные копии закладок:</div>
                    <div class="detail-value">${bookmarksCount} объектов</div>
                </div>
                <div class="detail-row">
                    <div class="detail-label">Резервные копии истории:</div>
                    <div class="detail-value">${historyCount} записей</div>
                </div>
                
                <div class="scope-box">
                    <strong>Предоставленные SCOPE-разрешения:</strong><br/>
                    • Read/Write access to user-selected nebula_sync_db via Firestore Proxy.<br/>
                    • Synchronization of offline sessions, real-time tabs mirroring, and bookmarks engine.
                </div>
                
                <div style="margin-top:24px; text-align:right;">
                    <button class="btn-danger" onclick="removeAccess()">Закрыть доступ приложению</button>
                </div>
                """ else """
                <div class="badge" style="background-color: #fce8e6; color: #c5221f;">✖ Браузер не авторизован</div>
                <h2 class="title" style="margin-top:10px;">Вход в синхронизацию не выполнен</h2>
                <p class="subtitle">Nebula Services не имеет активных сессий с этим Google Аккаунтом. Чтобы связать устройство и включить облачную синхронизацию вкладок и закладок, воспользуйтесь меню входа в браузере.</p>
                """}
            </div>
            
            <div class="card" style="opacity:0.9;">
                <h3 style="margin-top:0; font-size:15px; font-weight:500;">Где хранятся мои данные?</h3>
                <p style="font-size:13px; line-height:1.6; color: var(--text-sub);">
                    Все синхронизированные сессии, настройки, расширения и закладки передаются по протоколу HTTPS TLS 1.3 на защищенное выделенное облачное хранилище в регионе Europe-West2. Данные зашифрованы сквозным методом с использованием ключа, основанного на пароле вашего аккаунта, гарантируя максимальную конфиденциальность.
                </p>
            </div>
        </div>

        <script>
            function removeAccess() {
                if (confirm("Вы действительно хотите аннулировать токен доступа Nebula Services и полностью удалить синхронизацию с accounts.google.com?")) {
                    if (window.BrowserBridge && typeof window.BrowserBridge.logoutFromGoogle === 'function') {
                        window.BrowserBridge.logoutFromGoogle();
                        alert("Запрос отправлен. Доступ приложения Nebula Services к вашему Google Аккаунту успешно заблокирован.");
                        window.location.reload();
                    } else {
                        alert("Не удалось связаться с интерфейсом браузера.");
                    }
                }
            }
        </script>
    </body>
    </html>
    """.trimIndent()
}
