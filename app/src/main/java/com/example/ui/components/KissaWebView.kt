package com.example.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.config.AppConfig

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KissaWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var loadingProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // File chooser callback for <input type="file">
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val dataIntent = result.data
            val results: Array<Uri>? = when {
                dataIntent?.dataString != null -> arrayOf(Uri.parse(dataIntent.dataString))
                dataIntent?.clipData != null -> {
                    val clipData = dataIntent.clipData!!
                    Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    // Android back navigation handler
    BackHandler(enabled = canGoBack) {
        webViewInstance?.let { webView ->
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }
    }

    // Network error listener & check
    LaunchedEffect(Unit) {
        if (!isNetworkAvailable(context)) {
            isError = true
            errorMessage = "No internet connection detected. Please connect to the internet to load Kissa AI."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (isError) {
            OfflineView(
                errorMessage = errorMessage,
                onRetry = {
                    if (isNetworkAvailable(context)) {
                        isError = false
                        errorMessage = null
                        webViewInstance?.reload() ?: webViewInstance?.loadUrl(url)
                    } else {
                        Toast.makeText(context, "Still offline. Please check your network.", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenInBrowser = {
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        // Enable modern web settings
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = false
                            displayZoomControls = false
                            allowFileAccess = true
                            allowContentAccess = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT

                            // Set clean user agent
                            val defaultUa = userAgentString
                            userAgentString = "$defaultUa${AppConfig.USER_AGENT_SUFFIX}"
                        }

                        // Enable Cookies & Third Party Cookies for session / auth persistence
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Handle downloads (e.g. exported stories, pdfs, images)
                        setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                    setMimeType(mimetype)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading file from Kissa AI...")
                                    setTitle(Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download")
                                }
                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(ctx, "Download started...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                // Fallback to external browser
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })

                        // WebChromeClient for Progress, File Chooser, Permissions
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadingProgress = newProgress / 100f
                                isLoading = newProgress < 100
                            }

                            override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallbackLocal: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                            ): Boolean {
                                filePathCallback?.onReceiveValue(null)
                                filePathCallback = filePathCallbackLocal

                                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                }

                                try {
                                    fileChooserLauncher.launch(intent)
                                } catch (e: Exception) {
                                    filePathCallback = null
                                    return false
                                }
                                return true
                            }

                            override fun onPermissionRequest(request: PermissionRequest?) {
                                request?.grant(request.resources)
                            }
                        }

                        // WebViewClient for navigation and error handling
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        error?.description?.toString()
                                    } else null
                                    errorMessage = desc ?: "Unable to load page"
                                    isError = true
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val requestUrl = request?.url ?: return false
                                val scheme = requestUrl.scheme ?: ""

                                // Handle external protocols (tel, mailto, whatsapp, intent)
                                if (scheme != "http" && scheme != "https") {
                                    return try {
                                        val intent = Intent(Intent.ACTION_VIEW, requestUrl)
                                        ctx.startActivity(intent)
                                        true
                                    } catch (e: Exception) {
                                        false
                                    }
                                }

                                // Open in WebView
                                return false
                            }
                        }

                        loadUrl(url)
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("kissa_main_webview")
            )
        }

        // Sleek top progress indicator when loading
        AnimatedVisibility(
            visible = isLoading && !isError,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        ) {
            LinearProgressIndicator(
                progress = { loadingProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }
}
