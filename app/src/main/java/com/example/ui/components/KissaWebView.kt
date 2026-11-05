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
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
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
import com.example.auth.GoogleAuthManager

private const val TAG = "KissaWebView"

fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/**
 * Native JavaScript Interface exposed to web pages:
 * window.AndroidNative.signInWithGoogle(clientId)
 * window.AndroidNative.openCustomTab(url)
 */
class AndroidNativeInterface(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?
) {
    @JavascriptInterface
    fun signInWithGoogle(serverClientId: String?) {
        activity.runOnUiThread {
            GoogleAuthManager.signInWithGoogle(
                activity = activity,
                serverClientId = serverClientId ?: "",
                fallbackAuthUrl = null,
                onSuccess = { idToken ->
                    // Notify webpage of successful token
                    webViewProvider()?.evaluateJavascript(
                        "if (window.onGoogleSignInSuccess) { window.onGoogleSignInSuccess('$idToken'); }",
                        null
                    )
                },
                onError = { error ->
                    Log.w(TAG, "Native Google Sign-In error: $error")
                }
            )
        }
    }

    @JavascriptInterface
    fun openCustomTab(url: String?) {
        if (!url.isNullOrBlank()) {
            activity.runOnUiThread {
                GoogleAuthManager.openChromeCustomTab(activity, url)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KissaWebView(
    url: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

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

    // Initial Network check
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
                        GoogleAuthManager.openChromeCustomTab(context, url)
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

                        // Clean Chrome Mobile User-Agent so Razorpay unlocks UPI option
                        val rawUa = settings.userAgentString
                        val cleanChromeUa = rawUa
                            .replace("; wv", "")
                            .replace("Version/4.0 ", "")

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
                            userAgentString = cleanChromeUa
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(true)
                        }

                        // Enable Cookies & Third-Party Cookies (essential for Razorpay & Auth session persistence)
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Attach native JS bridge for Google Auth & Custom Tabs
                        if (activity != null) {
                            addJavascriptInterface(
                                AndroidNativeInterface(activity) { webViewInstance },
                                "AndroidNative"
                            )
                        }

                        // Handle downloads (exported stories, pdfs, images)
                        setDownloadListener(DownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            try {
                                val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                                    setMimeType(mimetype)
                                    addRequestHeader("User-Agent", userAgent)
                                    setDescription("Downloading file from Kissa AI...")
                                    setTitle(Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download")
                                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                    setDestinationInExternalPublicDir(
                                        Environment.DIRECTORY_DOWNLOADS,
                                        Uri.parse(downloadUrl).lastPathSegment ?: "kissa_ai_download"
                                    )
                                }
                                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                dm.enqueue(request)
                                Toast.makeText(ctx, "Download started...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        })

                        // WebChromeClient for Progress, File Chooser, Permissions & Multi-window
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

                            // Support popup windows created by payment gateways or auth providers
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?
                            ): Boolean {
                                val newWebView = WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.userAgentString = cleanChromeUa
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                            val popupUrl = req?.url?.toString() ?: return false
                                            return handlePaymentAndAuthSchemes(ctx, view, popupUrl)
                                        }
                                    }
                                }
                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                transport?.webView = newWebView
                                resultMsg?.sendToTarget()
                                return true
                            }
                        }

                        // WebViewClient with robust UPI & Google OAuth interceptors
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
                                val requestUrl = request?.url?.toString() ?: return false
                                return handlePaymentAndAuthSchemes(ctx, view, requestUrl)
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
            // Keep WebView alive during background payment navigation;
            // only clean up when the entire activity is destroyed
        }
    }
}

/**
 * Intercepts payment schemes (UPI, GPay, PhonePe, Paytm, CRED) and Google OAuth URLs.
 * Returns true if the URL was handled natively, false if the WebView should proceed.
 */
private fun handlePaymentAndAuthSchemes(
    ctx: Context,
    view: WebView?,
    url: String
): Boolean {
    // 1. Google OAuth Interceptor: Open in Chrome Custom Tab to prevent 403 disallowed_useragent
    if (url.contains("accounts.google.com") || url.contains("apis.google.com/signin")) {
        GoogleAuthManager.openChromeCustomTab(ctx, url)
        return true
    }

    // 2. Razorpay & UPI Payment Apps Interceptor (upi, gpay, phonepe, paytm, bhim, cred, etc.)
    val isUpiScheme = url.startsWith("upi://") ||
            url.startsWith("phonepe://") ||
            url.startsWith("gpay://") ||
            url.startsWith("paytmmp://") ||
            url.startsWith("bhim://") ||
            url.startsWith("credpay://") ||
            url.startsWith("mobikwik://")

    if (isUpiScheme) {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Pay using UPI App").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No UPI app could handle the scheme: $url", e)
            Toast.makeText(ctx, "No compatible UPI app found", Toast.LENGTH_SHORT).show()
            true
        }
    }

    // 3. Android Intent scheme (e.g. intent://upi/pay?pa=...#Intent;scheme=upi;package=...;end)
    if (url.startsWith("intent://")) {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val packageManager = ctx.packageManager
            if (packageManager.resolveActivity(intent, 0) != null) {
                ctx.startActivity(intent)
                true
            } else {
                // If specific package not installed, check for browser_fallback_url
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrBlank()) {
                    view?.loadUrl(fallbackUrl)
                    true
                } else {
                    // Try generic upi data if available
                    val dataString = intent.dataString
                    if (dataString != null && dataString.startsWith("upi://")) {
                        val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse(dataString)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val chooser = Intent.createChooser(genericIntent, "Pay using UPI").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(chooser)
                        true
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent URL: $url", e)
            false
        }
    }

    // 4. Other external non-http schemes (mailto, tel, whatsapp, sms)
    val uri = Uri.parse(url)
    val scheme = uri.scheme ?: ""
    if (scheme != "http" && scheme != "https") {
        return try {
            val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(externalIntent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not open external scheme $scheme: ${e.message}")
            false
        }
    }

    // Default: Load inside the WebView
    return false
}
