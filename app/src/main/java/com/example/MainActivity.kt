package com.example

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class AppTheme { SPATIAL, DARK, LIGHT }
enum class AppScreen { HOME, SETTINGS, ABOUT }
enum class ExtractMode { SINGLE, ZIP }

data class AppSettings(
    val theme: AppTheme = AppTheme.SPATIAL,
    val extractMode: ExtractMode = ExtractMode.SINGLE,
    val wordWrap: Boolean = false,
    val jsEnabled: Boolean = true,
    val maxAssets: Int = 20,
    val timeoutSeconds: Int = 15
)

data class AppColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val divider: Color,
    val premiumGradient: Brush
)

val LocalAppColors = staticCompositionLocalOf {
    AppColors(Color.Black, Color.DarkGray, Color.Gray, Color.White, Color.White, Color.White, Color.White, Brush.linearGradient(listOf(Color.White, Color.White)))
}
val LocalAppTheme = staticCompositionLocalOf { AppTheme.SPATIAL }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainApp()
                }
            }
        }
    }
}

class ExtractorViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ExtractorState())
    val uiState: StateFlow<ExtractorState> = _uiState.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun updateSettings(newSettings: AppSettings) {
        _settings.value = newSettings
    }

    fun updateUrl(newUrl: String) {
        _uiState.value = _uiState.value.copy(url = newUrl)
    }

    fun toggleUserAgent() {
        val newUa = if (_uiState.value.userAgent == "Desktop") "Mobile" else "Desktop"
        _uiState.value = _uiState.value.copy(userAgent = newUa)
    }

    fun setExtractMode(mode: ExtractMode) {
        _settings.value = _settings.value.copy(extractMode = mode)
    }

    fun fetchSourceCode() {
        val currentUrl = _uiState.value.url
        val currentUa = _uiState.value.userAgent
        val mode = _settings.value.extractMode
        val timeout = _settings.value.timeoutSeconds.toLong()

        if (currentUrl.isBlank()) return

        val finalUrl = if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
            "https://$currentUrl"
        } else {
            currentUrl
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null, zipBytes = null)

        viewModelScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val uaString = if (currentUa == "Mobile") {
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                }

                val request = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", uaString)
                    .build()
                val startTime = System.currentTimeMillis()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                
                if (response.isSuccessful) {
                    val code = response.body?.string() ?: ""
                    val headersStr = response.headers.joinToString("\n") { "${it.first}: ${it.second}" }
                    
                    var finalZipBytes: ByteArray? = null
                    var downloadedAssetsCount = 0

                    if (mode == ExtractMode.ZIP) {
                        withContext(Dispatchers.IO) {
                            val zipOut = ByteArrayOutputStream()
                            val zos = ZipOutputStream(zipOut)

                            zos.putNextEntry(ZipEntry("index.html"))
                            zos.write(code.toByteArray())
                            zos.closeEntry()

                            val baseUrlObj = java.net.URL(finalUrl)
                            val regex = Regex("""(?:src|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            val links = regex.findAll(code).map { it.groupValues[1] }
                                .distinct()
                                .filter { !it.startsWith("data:") && !it.startsWith("#") && !it.startsWith("javascript:") && !it.startsWith("mailto:") }
                                .take(_settings.value.maxAssets)
                                .toList()

                            val deferreds = links.map { link ->
                                async(Dispatchers.IO) {
                                    try {
                                        val assetUrl = java.net.URL(baseUrlObj, link)
                                        val req = Request.Builder().url(assetUrl).header("User-Agent", uaString).build()
                                        val res = client.newCall(req).execute()
                                        if (res.isSuccessful) {
                                            val bytes = res.body?.bytes()
                                            if (bytes != null) {
                                                val path = java.net.URI(assetUrl.toString()).path
                                                val name = path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "asset_${System.nanoTime()}"
                                                Pair("assets/$name", bytes)
                                            } else null
                                        } else null
                                    } catch (e: Exception) { null }
                                }
                            }

                            val results = deferreds.awaitAll().filterNotNull()
                            val addedNames = mutableSetOf<String>("index.html")
                            
                            for ((name, bytes) in results) {
                                var finalName = name
                                var counter = 1
                                while (addedNames.contains(finalName)) {
                                    finalName = "${name}_$counter"
                                    counter++
                                }
                                addedNames.add(finalName)
                                try {
                                    zos.putNextEntry(ZipEntry(finalName))
                                    zos.write(bytes)
                                    zos.closeEntry()
                                    downloadedAssetsCount++
                                } catch(e: Exception) {}
                            }
                            zos.close()
                            finalZipBytes = zipOut.toByteArray()
                        }
                    }

                    val endTime = System.currentTimeMillis()
                    val totalBytes = finalZipBytes?.size ?: code.toByteArray().size

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        sourceCode = code,
                        headersStr = headersStr,
                        currentUrl = finalUrl,
                        lineCount = code.lines().size,
                        sizeKb = totalBytes / 1024.0,
                        responseTimeMs = endTime - startTime,
                        zipBytes = finalZipBytes,
                        assetsCount = downloadedAssetsCount
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "خطای دریافت: ${response.code} ${response.message}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "خطای ارتباط با سرور"
                )
            }
        }
    }
}

data class ExtractorState(
    val url: String = "github.com",
    val currentUrl: String = "",
    val sourceCode: String = "",
    val headersStr: String = "",
    val userAgent: String = "Desktop",
    val isLoading: Boolean = false,
    val error: String? = null,
    val lineCount: Int = 0,
    val sizeKb: Double = 0.0,
    val responseTimeMs: Long = 0,
    val zipBytes: ByteArray? = null,
    val assetsCount: Int = 0
)

@Composable
fun MainApp(viewModel: ExtractorViewModel = viewModel()) {
    val settings by viewModel.settings.collectAsState()
    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

    val appColors = when (settings.theme) {
        AppTheme.LIGHT -> AppColors(
            bg = Color(0xFFF2F2F7), // iOS Light background
            surface = Color.White,
            surfaceVariant = Color(0xFFE5E5EA),
            textPrimary = Color(0xFF1C1C1E),
            textSecondary = Color(0xFF8E8E93),
            accent = Color(0xFF007AFF), // iOS Blue
            divider = Color.Black.copy(alpha = 0.05f),
            premiumGradient = Brush.linearGradient(listOf(Color(0xFF007AFF), Color(0xFF5856D6)))
        )
        AppTheme.DARK -> AppColors(
            bg = Color.Black,
            surface = Color(0xFF1C1C1E), // iOS Dark surface
            surfaceVariant = Color(0xFF2C2C2E),
            textPrimary = Color.White,
            textSecondary = Color(0xFFEBEBF5).copy(alpha = 0.6f),
            accent = Color(0xFFFFD700), // Premium Gold
            divider = Color.White.copy(alpha = 0.1f),
            premiumGradient = Brush.linearGradient(listOf(Color(0xFFFFD700), Color(0xFFFBC02D)))
        )
        AppTheme.SPATIAL -> AppColors(
            bg = Color(0xFF0F172A),
            surface = Color.White.copy(alpha = 0.08f),
            surfaceVariant = Color.White.copy(alpha = 0.15f),
            textPrimary = Color.White,
            textSecondary = Color.White.copy(alpha = 0.7f),
            accent = Color(0xFF81D4FA),
            divider = Color.White.copy(alpha = 0.12f),
            premiumGradient = Brush.linearGradient(listOf(Color(0xFF81D4FA), Color(0xFFB39DDB)))
        )
    }

    CompositionLocalProvider(
        LocalAppColors provides appColors,
        LocalAppTheme provides settings.theme
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Layer
            if (settings.theme == AppTheme.SPATIAL) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.spatial_bg),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF0F172A).copy(0.6f), Color.Black.copy(0.85f)))
                    )
                )
            } else {
                Box(modifier = Modifier.fillMaxSize().background(appColors.bg))
            }

            // Screen Transitions (iOS Style Spring)
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    val springSpecFloat = spring<Float>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
                    val springSpecIntOffset = spring<androidx.compose.ui.unit.IntOffset>(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioNoBouncy)
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(initialOffsetX = { it }, animationSpec = springSpecIntOffset) + fadeIn(springSpecFloat)) togetherWith
                        (slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = springSpecIntOffset) + fadeOut(springSpecFloat))
                    } else {
                        (slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = springSpecIntOffset) + fadeIn(springSpecFloat)) togetherWith
                        (slideOutHorizontally(targetOffsetX = { it }, animationSpec = springSpecIntOffset) + fadeOut(springSpecFloat))
                    }
                }, label = "ScreenTransition"
            ) { screen ->
                when (screen) {
                    AppScreen.HOME -> HomeScreen(viewModel, onNavigate = { currentScreen = it })
                    AppScreen.SETTINGS -> SettingsScreen(viewModel, onBack = { currentScreen = AppScreen.HOME })
                    AppScreen.ABOUT -> AboutScreen(onBack = { currentScreen = AppScreen.HOME })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(viewModel: ExtractorViewModel, onNavigate: (AppScreen) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val theme = LocalAppTheme.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAiDialog by remember { mutableStateOf(false) }

    var aiResponse by remember { mutableStateOf("") }
    var isAiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

    fun analyzeWithGemini() {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            aiError = "لطفا کلید GEMINI_API_KEY را در بخش Secrets وارد کنید."
            return
        }

        isAiLoading = true
        aiError = null
        aiResponse = ""

        viewModel.viewModelScope.launch {
            try {
                val jsonBody = """
                    {
                      "contents": [{
                        "parts": [{"text": "کد زیر را بررسی کن و یک خلاصه از ساختار آن بده و ۳ پیشنهاد برای بهینه‌سازی آن ارائه کن (پاسخ به زبان فارسی باشد):\n"}]
                      }, {
                        "parts": [{"text": ${org.json.JSONObject.quote(state.sourceCode.take(15000))}}]
                      }]
                    }
                """.trimIndent()

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), jsonBody))
                    .build()

                val client = OkHttpClient()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    try {
                        val jsonObject = org.json.JSONObject(responseBody)
                        val text = jsonObject.getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        aiResponse = text
                    } catch (e: Exception) {
                        aiError = "خطا در پردازش پاسخ."
                    }
                } else {
                    aiError = "خطای API: ${response.code}"
                }
            } catch (e: Exception) {
                aiError = e.localizedMessage ?: "خطای ناشناخته"
            } finally {
                isAiLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
    ) {
        // --- Elegant Top App Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "CodeX",
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                        fontWeight = FontWeight.Black,
                        fontSize = 24.sp,
                        letterSpacing = 2.sp,
                        brush = colors.premiumGradient
                    )
                )
            }
            IconButton(
                onClick = { onNavigate(AppScreen.ABOUT) },
                modifier = Modifier.glassButton(theme)
            ) {
                Icon(Icons.Rounded.Info, contentDescription = "درباره", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = { onNavigate(AppScreen.SETTINGS) },
                modifier = Modifier.glassButton(theme)
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = "تنظیمات", tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }

        // --- Search and Actions Panel ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .glassPanel(theme, radius = 28.dp)
                .padding(16.dp)
        ) {
            // Segmented Control for Extract Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.divider.copy(alpha = 0.05f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ExtractModeButton(
                    title = "سورس کد",
                    icon = Icons.Rounded.Html,
                    isSelected = settings.extractMode == ExtractMode.SINGLE,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setExtractMode(ExtractMode.SINGLE) }
                
                ExtractModeButton(
                    title = "کل سایت (ZIP)",
                    icon = Icons.Rounded.FolderZip,
                    isSelected = settings.extractMode == ExtractMode.ZIP,
                    modifier = Modifier.weight(1f)
                ) { viewModel.setExtractMode(ExtractMode.ZIP) }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Seamless Search Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surfaceVariant)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = state.url,
                    onValueChange = { viewModel.updateUrl(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("آدرس سایت...", color = colors.textSecondary) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null, tint = colors.textSecondary) },
                    trailingIcon = {
                        if (state.url.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateUrl("") }) {
                                Icon(Icons.Rounded.Cancel, contentDescription = "پاک کردن", tint = colors.textSecondary)
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.accent
                    )
                )
                
                IconButton(
                    onClick = { viewModel.toggleUserAgent() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (state.userAgent == "Desktop") colors.accent.copy(alpha = 0.1f) else Color.Transparent)
                ) {
                    Icon(
                        imageVector = if (state.userAgent == "Desktop") Icons.Rounded.Computer else Icons.Rounded.Smartphone,
                        contentDescription = "تغییر User Agent",
                        tint = if (state.userAgent == "Desktop") colors.accent else colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Button(
                    onClick = { viewModel.fetchSourceCode() },
                    enabled = !state.isLoading,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = if (theme == AppTheme.DARK) Color.Black else Color.White,
                        disabledContainerColor = colors.divider
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.textPrimary)
                    } else {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "استخراج", modifier = Modifier.size(24.dp))
                    }
                }
            }

            // Stats row if loaded
            AnimatedVisibility(
                visible = state.sourceCode.isNotEmpty() && !state.isLoading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        InfoChip(Icons.Rounded.Numbers, "${state.lineCount} خط")
                        InfoChip(Icons.Rounded.Storage, String.format("%.2f مگ", state.sizeKb / 1024.0))
                        if (state.zipBytes != null) {
                            InfoChip(Icons.Rounded.AttachFile, "+${state.assetsCount} فایل")
                        } else {
                            InfoChip(Icons.Rounded.Timer, "${state.responseTimeMs} ms")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Main Content Area ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp)
                .glassPanel(theme, radius = 28.dp)
                .clip(RoundedCornerShape(28.dp))
        ) {
            if (state.error != null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "خطا در استخراج", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = state.error!!, color = colors.textSecondary, textAlign = TextAlign.Center)
                }
            } else if (state.sourceCode.isNotEmpty() && !state.isLoading) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val springSpec = spring<Float>(stiffness = Spring.StiffnessLow)
                        (fadeIn(animationSpec = springSpec) + scaleIn(initialScale = 0.95f, animationSpec = springSpec)) togetherWith 
                        (fadeOut(animationSpec = springSpec) + scaleOut(targetScale = 0.95f, animationSpec = springSpec))
                    }, label = "tab_content"
                ) { targetTab ->
                    when (targetTab) {
                        0 -> SyntaxHighlighterView(code = state.sourceCode, theme = theme, wordWrap = settings.wordWrap)
                        1 -> BrowserPreviewView(url = state.currentUrl, html = state.sourceCode, jsEnabled = settings.jsEnabled)
                        2 -> HeadersView(headers = state.headersStr)
                        3 -> ExportView(state)
                    }
                }
            } else if (state.isLoading) {
                // Loading State
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = colors.accent)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("در حال استخراج...", color = colors.textSecondary)
                }
            } else {
                // Empty Welcome State
                WelcomeState(theme = theme)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Floating Dock ---
        Box(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 8.dp) // extra padding for bottom gesture navigation
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .glassPanel(theme, radius = 100.dp)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DockButton(icon = Icons.Rounded.Code, label = "سورس", isSelected = selectedTab == 0) { selectedTab = 0 }
                DockButton(icon = Icons.Rounded.Visibility, label = "نمایش", isSelected = selectedTab == 1) { selectedTab = 1 }
                DockButton(icon = Icons.Rounded.DataArray, label = "هدرها", isSelected = selectedTab == 2) { selectedTab = 2 }
                DockButton(icon = Icons.Rounded.SaveAlt, label = "خروجی", isSelected = selectedTab == 3) { selectedTab = 3 }
                
                Box(modifier = Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(colors.divider))
                
                DockButton(
                    icon = Icons.Rounded.Memory,
                    label = "هوش مصنوعی",
                    isSelected = false,
                    onClick = {
                        if (state.sourceCode.isNotEmpty()) showAiDialog = true
                        else Toast.makeText(context, "ابتدا سورس را استخراج کنید", Toast.LENGTH_SHORT).show()
                    }
                )
                if (state.sourceCode.isNotEmpty() && selectedTab == 0) {
                    DockButton(
                        icon = Icons.Rounded.ContentCopy,
                        label = "کپی",
                        isSelected = false,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Source Code", state.sourceCode)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "در حافظه کپی شد", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // AI Dialog (Keep unchanged mostly, just polished colors)
    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { showAiDialog = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textPrimary,
            shape = RoundedCornerShape(24.dp),
            title = { 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Memory, contentDescription = null, tint = colors.accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تحلیل هوشمند پروژه", fontWeight = FontWeight.Bold) 
                }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isAiLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp), color = colors.accent, strokeWidth = 3.dp)
                            Text("در حال پردازش سورس با Gemini...")
                        }
                    } else if (aiError != null) {
                        Text(aiError!!, color = Color(0xFFE53935))
                    } else if (aiResponse.isNotEmpty()) {
                        Text(aiResponse, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp))
                    } else {
                        Text("موتور هوش مصنوعی CodeX می‌تواند کدهای استخراج شده را تحلیل کرده و پیشنهادات امنیتی و بهینه‌سازی ارائه دهد.\n\n(نیاز به تنظیم GEMINI_API_KEY در پروژه)")
                    }
                }
            },
            confirmButton = {
                if (!isAiLoading && aiResponse.isEmpty() && aiError == null) {
                    Button(
                        onClick = { analyzeWithGemini() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if(theme==AppTheme.DARK) Color.Black else Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("شروع تحلیل", fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAiDialog = false }) {
                    Text("بستن", color = colors.textSecondary)
                }
            }
        )
    }
}

@Composable
fun WelcomeState(theme: AppTheme) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(colors.divider.copy(alpha=0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Explore, contentDescription = null, modifier = Modifier.size(50.dp), tint = colors.accent)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "به CodeX خوش آمدید",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "برای شروع، آدرس یک وب‌سایت را وارد کنید.\nمی‌توانید کل سایت را به صورت یکجا (ZIP) استخراج کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

@Composable
fun ExtractModeButton(title: String, icon: ImageVector, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    val bgColor by animateColorAsState(if (isSelected) colors.surface else Color.Transparent, label = "bg")
    val contentColor by animateColorAsState(if (isSelected) colors.textPrimary else colors.textSecondary, label = "content")
    
    // Add subtle shadow when selected
    val elevation by animateDpAsState(if (isSelected) 2.dp else 0.dp, label = "elevation")

    Row(
        modifier = modifier
            .shadow(elevation, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if(isSelected) colors.accent else contentColor, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = contentColor, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
    }
}

// --- iOS Style Settings & About Screens ---

@Composable
fun SettingsScreen(viewModel: ExtractorViewModel, onBack: () -> Unit) {
    val colors = LocalAppColors.current
    val settings by viewModel.settings.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "بازگشت", tint = colors.accent) }
            Text("تنظیمات", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.width(48.dp)) // Balance the back button
        }
        
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            
            SettingsGroupTitle("پوسته و ظاهر (Premium)")
            SettingsGroup(colors) {
                ThemeOptionRow(title = "طراحی فضایی (Spatial)", selected = settings.theme == AppTheme.SPATIAL, showDivider = true) { viewModel.updateSettings(settings.copy(theme = AppTheme.SPATIAL)) }
                ThemeOptionRow(title = "تاریک مطلق (Pitch Black)", selected = settings.theme == AppTheme.DARK, showDivider = true) { viewModel.updateSettings(settings.copy(theme = AppTheme.DARK)) }
                ThemeOptionRow(title = "روشن مینیمال (Light)", selected = settings.theme == AppTheme.LIGHT, showDivider = false) { viewModel.updateSettings(settings.copy(theme = AppTheme.LIGHT)) }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupTitle("موتور استخراج (Extraction)")
            SettingsGroup(colors) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("حداکثر فایل‌های جانبی در ZIP", color = colors.textPrimary, fontSize = 16.sp)
                    Text("افزایش این مقدار زمان پردازش را بیشتر می‌کند.", color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = settings.maxAssets.toFloat(),
                            onValueChange = { viewModel.updateSettings(settings.copy(maxAssets = it.toInt())) },
                            valueRange = 5f..50f,
                            steps = 8,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.divider),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("${settings.maxAssets} فایل", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("تایم‌اوت شبکه", color = colors.textPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = settings.timeoutSeconds.toFloat(),
                            onValueChange = { viewModel.updateSettings(settings.copy(timeoutSeconds = it.toInt())) },
                            valueRange = 5f..60f,
                            steps = 10,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.divider),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("${settings.timeoutSeconds} s", color = colors.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupTitle("نمایشگر و ویرایشگر")
            SettingsGroup(colors) {
                SwitchOptionRow(title = "شکستن خطوط طولانی (Word Wrap)", checked = settings.wordWrap, showDivider = true) { viewModel.updateSettings(settings.copy(wordWrap = it)) }
                SwitchOptionRow(title = "اجرای JavaScript در پیش‌نمایش", checked = settings.jsEnabled, showDivider = false) { viewModel.updateSettings(settings.copy(jsEnabled = it)) }
            }
            
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current

    Column(modifier = Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "بازگشت", tint = colors.accent) }
            Text("درباره توسعه‌دهنده", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.width(48.dp))
        }
        
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Box(
                modifier = Modifier.size(120.dp).clip(CircleShape).background(colors.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = colors.accent, modifier = Modifier.size(64.dp))
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text("aliem", style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Text("Creator & Lead Developer", style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)

            Spacer(modifier = Modifier.height(48.dp))
            
            SettingsGroup(colors) {
                InfoRow(icon = Icons.Rounded.Send, title = "کانال تلگرام رسمی", value = "@aliem_061", colors = colors, showDivider = true)
                InfoRow(icon = Icons.Rounded.AlternateEmail, title = "پشتیبانی (تلگرام / اینستاگرام)", value = "Aliem061", colors = colors, showDivider = false)
            }
        }
    }
}

// --- iOS Grouped List Helpers ---
@Composable
fun SettingsGroupTitle(title: String) {
    val colors = LocalAppColors.current
    Text(
        text = title.uppercase(),
        color = colors.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsGroup(colors: AppColors, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
    ) {
        content()
    }
}

@Composable
fun ThemeOptionRow(title: String, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = colors.textPrimary, fontSize = 16.sp)
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent)
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun SwitchOptionRow(title: String, checked: Boolean, showDivider: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = colors.textPrimary, fontSize = 16.sp)
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedTrackColor = colors.surfaceVariant,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
fun InfoRow(icon: ImageVector, title: String, value: String, colors: AppColors, showDivider: Boolean) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.accent.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, color = colors.textSecondary, fontSize = 13.sp)
                Text(value, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showDivider) {
            HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 68.dp))
        }
    }
}

// --- Glassmorphism Helpers ---
@SuppressLint("ModifierFactoryUnreferencedReceiver")
fun Modifier.glassPanel(theme: AppTheme, radius: Dp = 24.dp): Modifier = composed {
    val bg = when (theme) {
        AppTheme.SPATIAL -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.03f)))
        AppTheme.DARK -> SolidColor(LocalAppColors.current.surface)
        AppTheme.LIGHT -> SolidColor(Color.White)
    }
    val border = when (theme) {
        AppTheme.SPATIAL -> Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.02f)))
        AppTheme.DARK -> SolidColor(Color.White.copy(alpha = 0.05f))
        AppTheme.LIGHT -> SolidColor(Color.Black.copy(alpha = 0.05f))
    }
    // Very subtle shadow for depth
    this.shadow(if (theme == AppTheme.LIGHT) 4.dp else 8.dp, RoundedCornerShape(radius), spotColor = Color.Black.copy(alpha = 0.1f))
        .clip(RoundedCornerShape(radius))
        .background(bg)
        .border(1.dp, border, RoundedCornerShape(radius))
}

@SuppressLint("ModifierFactoryUnreferencedReceiver")
fun Modifier.glassButton(theme: AppTheme): Modifier = composed {
    val bg = when (theme) {
        AppTheme.SPATIAL -> Color.White.copy(alpha = 0.1f)
        AppTheme.DARK -> LocalAppColors.current.surface
        AppTheme.LIGHT -> Color.White
    }
    this.shadow(2.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.05f))
        .clip(CircleShape)
        .background(bg)
}

@Composable
fun InfoChip(icon: ImageVector, text: String) {
    val colors = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun DockButton(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current

    val backgroundColor by animateColorAsState(if (isSelected) colors.textPrimary else Color.Transparent, label = "bg")
    val contentColor by animateColorAsState(if (isSelected) colors.bg else colors.textSecondary, label = "content")

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = if (isSelected) 16.dp else 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(22.dp))
        AnimatedVisibility(
            visible = isSelected,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            Row {
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = label, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// --- Views ---

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SyntaxHighlighterView(code: String, theme: AppTheme, wordWrap: Boolean) {
    val isLight = theme == AppTheme.LIGHT
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            val escapedCode = code.replace("<", "&lt;").replace(">", "&gt;")
            val prismTheme = if (isLight) "prism.min.css" else "prism-tomorrow.min.css"
            val wrapRule = if (wordWrap) "pre-wrap" else "pre"
            // Use better fonts and padding for a premium IDE look
            val html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <link href="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/$prismTheme" rel="stylesheet" />
                    <link href="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/plugins/line-numbers/prism-line-numbers.min.css" rel="stylesheet" />
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js"></script>
                    <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/plugins/line-numbers/prism-line-numbers.min.js"></script>
                    <style>
                        @import url('https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500&display=swap');
                        body { margin: 0; background-color: transparent; }
                        pre { 
                            margin: 0 !important; 
                            border-radius: 0 !important; 
                            font-size: 13px !important; 
                            background: transparent !important; 
                            padding: 16px 0 16px 3.8em !important; 
                        }
                        code { 
                            white-space: $wrapRule !important; 
                            word-break: break-word; 
                            font-family: 'JetBrains Mono', Consolas, Monaco, monospace !important;
                            line-height: 1.6 !important;
                        }
                        .line-numbers .line-numbers-rows { border-right: 1px solid rgba(128,128,128,0.2); padding-top: 16px;}
                    </style>
                </head>
                <body class="line-numbers">
                    <pre><code class="language-markup">$escapedCode</code></pre>
                </body>
                </html>
            """.trimIndent()
            val encodedHtml = Base64.getEncoder().encodeToString(html.toByteArray())
            webView.loadData(encodedHtml, "text/html", "base64")
        },
        modifier = Modifier.fillMaxSize()
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPreviewView(url: String, html: String, jsEnabled: Boolean) {
    val theme = LocalAppTheme.current
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(if (theme == AppTheme.LIGHT) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY)
                settings.domStorageEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
            }
        },
        update = { webView ->
            webView.settings.javaScriptEnabled = jsEnabled
            webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
        },
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
    )
}

@Composable
fun HeadersView(headers: String) {
    val colors = LocalAppColors.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = "پاسخ سرور (HTTP Headers)",
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            HorizontalDivider(color = colors.divider, thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = headers.ifEmpty { "هدری دریافت نشد." },
                style = androidx.compose.ui.text.TextStyle(
                    color = colors.textSecondary,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 24.sp
                )
            )
        }
    }
}

@Composable
fun ExportView(state: ExtractorState) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val isZip = state.zipBytes != null

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(CircleShape).background(colors.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isZip) Icons.Rounded.FolderZip else Icons.Rounded.Html, 
                contentDescription = null, 
                modifier = Modifier.size(48.dp), 
                tint = colors.accent
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "خروجی سورس کد", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = colors.textPrimary)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (isZip) "وب‌سایت و تمام فایل‌های وابسته با موفقیت در یک فایل ZIP بسته‌بندی شدند." 
                   else "کد HTML صفحه استخراج شد. می‌توانید آن را ذخیره کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
        if (isZip) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("${state.assetsCount} فایل جانبی استخراج شد", color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(40.dp))
        Button(
            onClick = {
                val ext = if (isZip) ".zip" else ".html"
                val mime = if (isZip) "application/zip" else "text/html"
                val fileName = "CodeX_Export_${System.currentTimeMillis()}$ext"
                try {
                    val resolver = context.contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            if (isZip) {
                                outputStream.write(state.zipBytes!!)
                            } else {
                                outputStream.write(state.sourceCode.toByteArray())
                            }
                        }
                        Toast.makeText(context, "فایل $ext در پوشه دانلودها ذخیره شد", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطا در ذخیره فایل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth(0.9f).height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = if(LocalAppTheme.current==AppTheme.DARK) Color.Black else Color.White)
        ) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isZip) "ذخیره در دستگاه (ZIP)" else "ذخیره در دستگاه (HTML)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
