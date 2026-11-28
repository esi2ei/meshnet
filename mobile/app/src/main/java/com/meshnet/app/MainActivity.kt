package com.meshnet.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.net.Uri
import android.net.VpnService
import java.io.File
import java.io.ByteArrayInputStream
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal object MeshWebViewSecurityPolicy {
    private const val BUNDLED_HOST = "appassets.androidplatform.net"

    fun isBundledAsset(uri: Uri): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(BUNDLED_HOST, ignoreCase = true) ||
            uri.userInfo != null || uri.port != -1
        ) return false
        val path = uri.path ?: return false
        if (!path.startsWith("/assets/web/")) return false
        if (path.contains('\\\\') || path.contains('\\u0000')) return false
        return path.split('/').none { it == "." || it == ".." }
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var keyInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var wifiAddress: EditText
    @Volatile private var meshService: MeshForegroundService? = null
    private var webView: WebView? = null
    private val recentEvents = ArrayList<String>()
    private val appPreferences by lazy {
        getSharedPreferences("meshnet_app_settings", MODE_PRIVATE)
    }
    private var bound = false
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording = false
    private var voiceButton: Button? = null
    private var liveVoiceButton: Button? = null
    private var joinVoiceButton: Button? = null
    private var joinVoiceAfterPermission = false
    private var voicePlayer: MediaPlayer? = null
    private lateinit var walletStatus: TextView
    private lateinit var transferStatus: TextView
    private lateinit var receivedFileAccess: MeshFileTransfer
    private var pendingExportFileName: String? = null
    private var pendingExportUri: Uri? = null
    private lateinit var walletAuthStore: MeshWalletAuthorizationStore
    private lateinit var offlineWalletStore: MeshOfflineWalletStore
    @Volatile private var walletScenario: LocalAssociationScenario? = null
    @Volatile private var walletBusy = false
    @Volatile private var walletAuthToken: String? = null
    @Volatile private var walletAddress: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            meshService = (binder as? MeshForegroundService.LocalBinder)?.service()
            meshService?.setListener(::append)
            status.text = if (meshService?.isRunning() == true) "Local mesh session running" else "Service connected · mesh stopped"
            append("Connected to MeshNet foreground service")
            publishRuntimeStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService?.setListener(null)
            meshService = null
            append("MeshNet service disconnected")
            publishRuntimeStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletAuthStore = MeshWalletAuthorizationStore(applicationContext)
        offlineWalletStore = MeshOfflineWalletStore(applicationContext)
        receivedFileAccess = MeshFileTransfer(
            context = applicationContext,
            sendMessage = { false },
            onEvent = ::append
        )
        render()
        runCatching { walletAuthStore.load() }
            .onSuccess { saved ->
                if (saved != null) {
                    walletAuthToken = saved.authToken
                    walletAddress = saved.address
                    walletStatus.text = "Authorized · Devnet · ${saved.address}"
                }
            }
            .onFailure {
                walletStatus.text = "Stored wallet authorization unavailable · reconnect required"
                append("Stored wallet authorization could not be decrypted: ${it.message}")
            }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, MeshForegroundService::class.java), connection, BIND_AUTO_CREATE)
    }

    private fun render() {
        // Keep the existing native actions behind a narrow, local-only JavaScript bridge.
        // The dashboard itself is compiled into APK assets and never loaded from a remote host.
        status = TextView(this)
        log = TextView(this)
        walletStatus = TextView(this)
        transferStatus = TextView(this)
        keyInput = EditText(this).apply { inputType = 0x00000081 }
        messageInput = EditText(this)
        wifiAddress = EditText(this)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val localWebView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.rgb(10, 11, 13))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            isVerticalScrollBarEnabled = true
            overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addJavascriptInterface(MeshNetBridge(), "MeshNetNative")
            webViewClient = object : WebViewClient() {
                private fun blockedResponse(): WebResourceResponse =
                    WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Forbidden",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(ByteArray(0))
                    )

                private fun interceptBundledAsset(url: Uri): WebResourceResponse {
                    // addJavascriptInterface is visible to every frame. Block external and
                    // unknown subresources instead of allowing WebView to fall back to network.
                    if (!MeshWebViewSecurityPolicy.isBundledAsset(url)) return blockedResponse()
                    return assetLoader.shouldInterceptRequest(url) ?: blockedResponse()
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? = interceptBundledAsset(request.url)

                @Suppress("DEPRECATION")
                override fun shouldInterceptRequest(
                    view: WebView?,
                    url: String?
                ): WebResourceResponse? =
                    url?.let { interceptBundledAsset(Uri.parse(it)) } ?: blockedResponse()

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest
                ): Boolean = !MeshWebViewSecurityPolicy.isBundledAsset(request.url)

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    url == null || !MeshWebViewSecurityPolicy.isBundledAsset(Uri.parse(url))

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    publishRuntimeStatus()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        append("Local dashboard load failed: ${error?.description ?: "unknown WebView error"}")
                    }
                }
            }
        }
        webView = localWebView
        localWebView.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safeInsets = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                view.setPadding(safeInsets.left, safeInsets.top, safeInsets.right, safeInsets.bottom)
            } else {
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(localWebView)
        localWebView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    private fun startOfflineVpnService() {
        val intent = Intent(this, MeshVpnService::class.java).apply {
            action = MeshVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun runtimeStatusJson(): String {
        val events = JSONArray()
        synchronized(recentEvents) {
            recentEvents.takeLast(12).forEach { events.put(it) }
        }
        val peers = meshService?.connectedPeersJson() ?: JSONArray()
        val routes = meshService?.observedRoutesJson() ?: JSONArray()
        val localNodeId = meshService?.localNodeIdValue()
        val offlineNodeIp = meshService?.offlineNodeIpValue()
        val offlinePortalUrl = meshService?.offlinePortalUrlValue()
        val outgoingTransfers = JSONArray()
        runCatching {
            offlineWalletStore.outgoingHistory().takeLast(8).forEach { transfer ->
                outgoingTransfers.put(
                    JSONObject()
                        .put("id", transfer.id)
                        .put("recipient", transfer.recipient)
                        .put("amountMinor", transfer.amountMinor)
                        .put("createdAt", transfer.createdAt)
                        .put("status", transfer.status)
                )
            }
        }
        val receivedReceipts = JSONArray()
        runCatching {
            offlineWalletStore.history().takeLast(8).forEach { receipt ->
                receivedReceipts.put(
                    JSONObject()
                        .put("id", receipt.id)
                        .put("sender", receipt.sender)
                        .put("amountMinor", receipt.amountMinor)
                        .put("createdAt", receipt.createdAt)
                        .put("asset", receipt.asset)
                )
            }
        }
        return JSONObject()
            .put("native", true)
            .put("platform", "android")
            .put("running", meshService?.isRunning() == true)
            .put("onlineIntegrationsEnabled", appPreferences.getBoolean(KEY_ONLINE_INTEGRATIONS, false))
            .put("walletBusy", walletBusy)
            .put("walletAddress", walletAddress ?: JSONObject.NULL)
            .put("offlineWalletAddress", runCatching { offlineWalletStore.address() }.getOrNull() ?: JSONObject.NULL)
            .put("offlineWalletTransfers", outgoingTransfers)
            .put("offlineWalletReceipts", receivedReceipts)
            .put("offlineLibrary", meshService?.offlineLibraryJson() ?: JSONArray())
            .put("offlineNetworkRunning", offlineNodeIp != null)
            .put("offlineNodeIp", offlineNodeIp ?: JSONObject.NULL)
            .put("offlinePortalUrl", offlinePortalUrl ?: JSONObject.NULL)
            .put("recentEvents", events)
            .put("connectedPeers", peers)
            .put("observedRoutes", routes)
            .put("localNodeId", localNodeId ?: JSONObject.NULL)
            .toString()
    }

    private fun publishRuntimeStatus(message: String? = null) {
        val view = webView ?: return
        val event = JSONObject()
            .put("message", message ?: JSONObject.NULL)
            .put("status", JSONObject(runtimeStatusJson()))
        val script = "window.MeshNetUI&&window.MeshNetUI.onNativeEvent($event)"
        view.post { runCatching { view.evaluateJavascript(script, null) } }
    }

    private fun <T> runOnMainBlocking(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        val result = AtomicReference<Any?>(null)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                result.set(block())
            } catch (_: Exception) {
                failed.set(true)
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS) || failed.get()) return null
        @Suppress("UNCHECKED_CAST")
        return result.get() as? T
    }

    inner class MeshNetBridge {
        @JavascriptInterface
        fun getRuntimeStatus(): String = runtimeStatusJson()

        @JavascriptInterface
        fun startMesh(passphrase: String?) {
            runOnUiThread {
                keyInput.setText(passphrase.orEmpty())
                requestAndStart()
            }
        }

        @JavascriptInterface
        fun stopMesh() {
            runOnUiThread {
                startService(Intent(this@MainActivity, MeshForegroundService::class.java).apply {
                    action = MeshForegroundService.ACTION_STOP
                })
                append("Mesh stop requested")
            }
        }

        @JavascriptInterface
        fun sendDirectMessage(destinationNodeId: String, message: String): Boolean =
            runOnMainBlocking {
                try {
                    val accepted = meshService?.sendDirectMessage(destinationNodeId.trim(), message)
                        ?: throw IllegalStateException("Start the mesh before sending a directed message")
                    append(if (accepted) "Directed message accepted into encrypted outbox" else "Directed message was not accepted")
                    accepted
                } catch (error: Exception) {
                    append(error.message ?: "Unable to queue directed message")
                    false
                }
            } ?: false

        @JavascriptInterface
        fun sendMessage(message: String): Boolean =
            runOnMainBlocking {
                try {
                    val accepted = meshService?.sendMessage(message)
                        ?: throw IllegalStateException("Start the mesh before sending a message")
                    append(if (accepted) "Message accepted into encrypted outbox" else "Message was not accepted")
                    accepted
                } catch (error: Exception) {
                    append(error.message ?: "Unable to queue message")
                    false
                }
            } ?: false

        @JavascriptInterface
        fun startOfflineNetwork() {
            runOnUiThread {
                if (meshService?.localNodeIdValue() == null) {
                    toast("Start the MeshNet radio mesh first")
                    return@runOnUiThread
                }
                val permission = VpnService.prepare(this@MainActivity)
                if (permission != null) startActivityForResult(permission, REQUEST_OFFLINE_VPN)
                else startOfflineVpnService()
            }
        }

        @JavascriptInterface
        fun stopOfflineNetwork() {
            runOnUiThread {
                startService(Intent(this@MainActivity, MeshVpnService::class.java).apply {
                    action = MeshVpnService.ACTION_STOP
                })
            }
        }

        @JavascriptInterface
        fun openOfflinePortal() {
            runOnUiThread {
                val url = meshService?.offlinePortalUrlValue()
                if (url == null) toast("Start the offline IP network first")
                else runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { toast("No browser is available to open the offline portal") }
            }
        }

        @JavascriptInterface
        fun selectOfflineContent() {
            runOnUiThread {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(picker, REQUEST_PUBLISH_CONTENT)
            }
        }

        @JavascriptInterface
        fun requestOfflineContent(id: String): Boolean = runOnMainBlocking {
            runCatching { meshService?.requestOfflineContent(id) == true }
                .onFailure { toast(it.message ?: "Unable to request offline content") }
                .getOrDefault(false)
        } ?: false

        @JavascriptInterface
        fun removeOfflineContent(id: String): Boolean = runOnMainBlocking {
            meshService?.removeOfflineContent(id) == true
        } ?: false

        @JavascriptInterface
        fun selectFile() {
            runOnUiThread {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(picker, REQUEST_FILE)
            }
        }

        @JavascriptInterface
        fun cancelFileTransfer(): Boolean =
            runOnMainBlocking { meshService?.cancelFileTransfers() == true } ?: false

        @JavascriptInterface
        fun forgetGroupCredentials() {
            runOnUiThread {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Forget group credentials?")
                    .setMessage("This deletes the saved group passphrase and local queued mesh data on this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        startService(Intent(this@MainActivity, MeshForegroundService::class.java).apply {
                            action = MeshForegroundService.ACTION_FORGET_CREDENTIALS
                        })
                        append("Forget credentials requested")
                    }
                    .show()
            }
        }

        @JavascriptInterface
        fun saveReceivedFile() {
            runOnUiThread { chooseReceivedFileForExport() }
        }

        @JavascriptInterface
        fun toggleVoiceNote() {
            runOnUiThread {
                if (isRecording) stopVoiceRecording() else requestVoiceRecording()
            }
        }

        @JavascriptInterface
        fun playVoiceNote() {
            runOnUiThread { playLatestVoiceNote() }
        }

        @JavascriptInterface
        fun startLiveVoice(join: Boolean) {
            runOnUiThread {
                joinVoiceAfterPermission = join
                requestLiveVoice()
            }
        }

        @JavascriptInterface
        fun endLiveVoice() {
            runOnUiThread {
                runCatching { meshService?.endLiveVoiceCall() }
                    .onFailure { toast(it.message ?: "Unable to end live voice") }
                append("Live voice end requested")
            }
        }

        @JavascriptInterface
        fun createWifiDirectGroup() {
            runOnUiThread { runWifiAction { meshService?.createWifiDirectGroup() } }
        }

        @JavascriptInterface
        fun discoverWifiDirect() {
            runOnUiThread { runWifiAction { meshService?.discoverWifiDirect() } }
        }

        @JavascriptInterface
        fun connectWifiDirect(address: String) {
            runOnUiThread { runWifiAction { meshService?.connectWifiDirect(address.trim()) } }
        }

        @JavascriptInterface
        fun resumeWifiAware() {
            runOnUiThread { runWifiAction { meshService?.resumeWifiAware() } }
        }

        @JavascriptInterface
        fun setOnlineIntegrationsEnabled(enabled: Boolean) {
            runOnUiThread {
                appPreferences.edit().putBoolean(KEY_ONLINE_INTEGRATIONS, enabled).apply()
                append(if (enabled) "Optional online integrations enabled by user" else "Optional online integrations disabled")
            }
        }

        @JavascriptInterface
        fun copyOfflineWalletAddress(): Boolean =
            runOnMainBlocking {
                try {
                    val address = offlineWalletStore.address()
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("MeshNet wallet address", address))
                    toast("Offline wallet address copied")
                    true
                } catch (error: Exception) {
                    toast(error.message ?: "Unable to copy offline wallet address")
                    false
                }
            } ?: false

        @JavascriptInterface
        fun getOfflineWalletAddress(): String = runCatching { offlineWalletStore.address() }.getOrElse { "" }

        @JavascriptInterface
        fun sendOfflinePayment(recipientAddress: String, amountMinor: Double): Boolean =
            runOnMainBlocking {
                try {
                    require(amountMinor.isFinite() && amountMinor % 1.0 == 0.0 && amountMinor in 1.0..9_000_000_000_000.0) { "Amount must be a positive whole number of local minor units" }
                    val accepted = meshService?.sendOfflinePayment(recipientAddress.trim(), amountMinor.toLong())
                        ?: throw IllegalStateException("Start the local mesh before sending an offline wallet transfer")
                    append(if (accepted) "Offline wallet transfer queued" else "Offline wallet transfer was not fully queued")
                    accepted
                } catch (error: Exception) {
                    append(error.message ?: "Unable to create offline wallet transfer")
                    false
                }
            } ?: false

        @JavascriptInterface
        fun retryPendingOfflinePayments(): Int =
            runOnMainBlocking {
                try {
                    val queued = meshService?.retryPendingOfflinePayments()
                        ?: throw IllegalStateException("Start the local mesh before retrying wallet transfers")
                    append("Requeued $queued unconfirmed offline wallet transfers")
                    queued
                } catch (error: Exception) {
                    append(error.message ?: "Unable to retry offline wallet transfers")
                    0
                }
            } ?: 0

        @JavascriptInterface
        fun connectWallet() {
            runOnUiThread {
                if (!appPreferences.getBoolean(KEY_ONLINE_INTEGRATIONS, false)) {
                    toast("Enable optional online integrations first")
                } else {
                    connectSolanaWalletDevnet()
                }
            }
        }

        @JavascriptInterface
        fun sendSolanaDevnetTransfer(recipientAddress: String, amountSol: String) {
            runOnUiThread { submitSolanaDevnetTransfer(recipientAddress, amountSol) }
        }

        @JavascriptInterface
        fun disconnectWallet() {
            runOnUiThread { disconnectSolanaWallet() }
        }
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OFFLINE_VPN) {
            if (resultCode == RESULT_OK) startOfflineVpnService()
            else toast("VPN permission was not granted; the offline IP network remains stopped")
            return
        }
        if (requestCode == REQUEST_PUBLISH_CONTENT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val grant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (grant != 0 && (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, grant) }
            }
            val activeService = meshService
            if (activeService == null) { toast("Start the local mesh before publishing content"); return }
            Thread({
                try {
                    val id = activeService.publishOfflineContent(uri)
                    runOnUiThread { toast("Published offline content · ${id.take(12)}…"); publishRuntimeStatus() }
                } catch (error: Exception) {
                    runOnUiThread { toast(error.message ?: "Unable to publish offline content") }
                }
            }, "meshnet-library-publish").apply { isDaemon = true; start() }
            return
        }
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val readGrant = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (readGrant != 0 && (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                runCatching { contentResolver.takePersistableUriPermission(uri, readGrant) }
                    .onFailure { append("Selected file provider did not allow a persistent read grant: ${it.message}") }
            }
            try {
                queueFile(uri)
            } catch (error: Exception) {
                toast(error.message ?: "Unable to send file")
            }
        }
        if (requestCode == REQUEST_EXPORT_FILE) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null && pendingExportFileName != null) {
                pendingExportUri = uri
                exportPendingFileIfReady()
            } else {
                pendingExportFileName = null
                pendingExportUri = null
            }
            return
        }
        if (requestCode == REQUEST_WALLET && resultCode == RESULT_CANCELED) {
            append("Wallet activity returned; checking the local association result")
        }
    }


    /**
     * Connects to a user-selected MWA-compatible wallet on Solana Devnet.
     * The authorization token is persisted only as ciphertext with an Android Keystore key.
     * Native SOL Devnet transfers are a separate, explicit wallet-approved action; no mainnet,
     * SPL-token, or escrow transaction is constructed or submitted by this flow.
     */
    private fun connectSolanaWalletDevnet() {
        if (walletAuthToken != null) {
            toast("A wallet is already authorized; disconnect it before connecting another account")
            return
        }
        if (walletBusy) {
            toast("A wallet operation is already in progress")
            return
        }
        walletBusy = true
        val scenario = try {
            LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        } catch (error: Exception) {
            walletBusy = false
            toast(error.message ?: "Unable to create wallet association")
            return
        }
        walletScenario = scenario
        try {
            val associationIntent = LocalAssociationIntentCreator.createAssociationIntent(
                null, scenario.port, scenario.session
            )
            val connectionFuture = scenario.start()
            @Suppress("DEPRECATION")
            startActivityForResult(associationIntent, REQUEST_WALLET)
            walletStatus.text = "Connecting to a wallet · Solana Devnet"
            Thread({
                try {
                    val client = connectionFuture.get(WALLET_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val authorization = client.authorize(
                        null, null, "MeshNet prototype", ProtocolContract.CHAIN_SOLANA_DEVNET,
                        null, null, null, null
                    ).get(WALLET_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val address = try {
                        val account = authorization.accounts.firstOrNull()
                            ?: throw IllegalStateException("Wallet returned no authorized account")
                        val publicAddress = encodeBase58(account.publicKey)
                        walletAuthStore.save(authorization.authToken, publicAddress)
                        publicAddress
                    } catch (error: Exception) {
                        // Do not leave a wallet session authorized when local secure persistence failed.
                        runCatching {
                            client.deauthorize(authorization.authToken)
                                .get(WALLET_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                        throw error
                    }
                    walletAuthToken = authorization.authToken
                    walletAddress = address
                    runOnUiThread {
                        walletStatus.text = "Connected · Devnet · $address"
                        append("Wallet authorized for read-only prototype use on Solana Devnet")
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        walletStatus.text = "Solana wallet: not connected · Devnet only"
                        toast(error.cause?.message ?: error.message ?: "Wallet authorization failed")
                    }
                } finally {
                    runCatching { scenario.close().get(WALLET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    runOnUiThread {
                        if (walletScenario === scenario) walletScenario = null
                        walletBusy = false
                    }
                }
            }, "meshnet-wallet-authorize").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            runCatching { scenario.close() }
            walletScenario = null
            walletBusy = false
            walletStatus.text = "Solana wallet: not connected · Devnet only"
            toast(error.message ?: "No compatible wallet activity was found")
        }
    }

    /**
     * Sends a native SOL transfer on Devnet through the connected wallet.
     * The wallet displays the transaction and controls signing/submission; the app never sees keys.
     */
    private fun submitSolanaDevnetTransfer(recipientAddress: String, amountSol: String) {
        if (!appPreferences.getBoolean(KEY_ONLINE_INTEGRATIONS, false)) {
            toast("Enable optional online integrations before sending a Devnet transaction")
            return
        }
        if (walletBusy) {
            toast("A wallet operation is already in progress")
            return
        }
        val saved = runCatching { walletAuthStore.load() }.getOrNull()
        val authToken = walletAuthToken ?: saved?.authToken
        val savedAddress = walletAddress ?: saved?.address
        if (authToken.isNullOrBlank() || savedAddress.isNullOrBlank()) {
            toast("Connect a Solana Devnet wallet first")
            return
        }
        val lamports = try {
            SolanaDevnetTransfer.parseLamports(amountSol)
        } catch (error: Exception) {
            toast(error.message ?: "Invalid SOL amount")
            return
        }
        val recipient = try {
            SolanaDevnetTransfer.decodeBase58(recipientAddress.trim()).also {
                require(it.size == 32) { "Recipient must be a 32-byte Solana address" }
                require(SolanaDevnetTransfer.encodeBase58(it) != savedAddress) {
                    "Sender and recipient addresses must be different"
                }
            }
        } catch (error: Exception) {
            toast(error.message ?: "Invalid Solana recipient address")
            return
        }
        walletBusy = true
        walletStatus.text = "Preparing Solana Devnet transfer…"
        publishRuntimeStatus("Preparing Devnet SOL transfer")
        val scenario = try {
            LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        } catch (error: Exception) {
            walletBusy = false
            toast(error.message ?: "Unable to create wallet association")
            publishRuntimeStatus()
            return
        }
        walletScenario = scenario
        try {
            val associationIntent = LocalAssociationIntentCreator.createAssociationIntent(
                null, scenario.port, scenario.session
            )
            val connectionFuture = scenario.start()
            @Suppress("DEPRECATION")
            startActivityForResult(associationIntent, REQUEST_WALLET)
            Thread({
                try {
                    val client = connectionFuture.get(WALLET_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val authorization = client.authorize(
                        null, null, "MeshNet prototype", ProtocolContract.CHAIN_SOLANA_DEVNET,
                        authToken, null, null, null
                    ).get(WALLET_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val account = authorization.accounts.firstOrNull()
                        ?: throw IllegalStateException("Wallet returned no authorized account")
                    val payerAddress = encodeBase58(account.publicKey)
                    require(payerAddress == savedAddress) {
                        "Connected wallet account changed; reconnect and verify the selected address"
                    }
                    walletAuthStore.save(authorization.authToken, payerAddress)
                    walletAuthToken = authorization.authToken
                    walletAddress = payerAddress

                    val blockhash = fetchDevnetRecentBlockhash()
                    val transaction = SolanaDevnetTransfer.buildUnsignedTransfer(
                        account.publicKey, recipient, blockhash, lamports
                    )
                    val submitted = client.signAndSendTransactions(
                        arrayOf(transaction), null, "confirmed", false, 3, true
                    ).get(WALLET_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    val signatureBytes = submitted.signatures.firstOrNull()
                        ?: throw IllegalStateException("Wallet returned no transaction signature")
                    val signature = SolanaDevnetTransfer.encodeBase58(signatureBytes)
                    val confirmation = try {
                        checkDevnetSignature(signature)
                    } catch (error: Exception) {
                        if (error.message?.startsWith("Devnet rejected transaction") == true) throw error
                        "wallet submitted; RPC confirmation read-back unavailable"
                    }
                    runOnUiThread {
                        walletStatus.text = "Devnet transaction · $confirmation"
                        append("Solana Devnet SOL transfer · $signature · $confirmation")
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        walletStatus.text = "Solana Devnet transfer not confirmed"
                        toast(error.cause?.message ?: error.message ?: "Devnet transfer failed")
                        append("Solana Devnet transfer failed or remains unconfirmed: " +
                            (error.cause?.message ?: error.message ?: "unknown error"))
                    }
                } finally {
                    runCatching { scenario.close().get(WALLET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    runOnUiThread {
                        if (walletScenario === scenario) walletScenario = null
                        walletBusy = false
                        publishRuntimeStatus()
                    }
                }
            }, "meshnet-solana-devnet-transfer").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            runCatching { scenario.close() }
            walletScenario = null
            walletBusy = false
            toast(error.message ?: "Unable to open the Devnet wallet")
            publishRuntimeStatus()
        }
    }

    private fun fetchDevnetRecentBlockhash(): ByteArray {
        val result = solanaDevnetRpc(
            "getLatestBlockhash",
            JSONArray().put(JSONObject().put("commitment", "confirmed"))
        )
        val encoded = result.getJSONObject("value").getString("blockhash")
        return SolanaDevnetTransfer.decodeBase58(encoded).also {
            require(it.size == 32) { "Devnet RPC returned an invalid recent blockhash" }
        }
    }

    private fun checkDevnetSignature(signature: String): String {
        val result = solanaDevnetRpc(
            "getSignatureStatuses",
            JSONArray()
                .put(JSONArray().put(signature))
                .put(JSONObject().put("searchTransactionHistory", true))
        )
        val status = result.getJSONArray("value").optJSONObject(0)
            ?: return "submitted; confirmation pending"
        val error = status.opt("err")
        require(error == null || error == JSONObject.NULL) { "Devnet rejected transaction: $error" }
        val commitment = status.optString("confirmationStatus")
        return if (commitment == "confirmed" || commitment == "finalized") {
            "confirmed ($commitment)"
        } else {
            "submitted; confirmation pending"
        }
    }

    private fun solanaDevnetRpc(method: String, params: JSONArray): JSONObject {
        val connection = (URL(SOLANA_DEVNET_RPC).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = (WALLET_RPC_TIMEOUT_SECONDS * 1000).toInt()
            readTimeout = (WALLET_RPC_TIMEOUT_SECONDS * 1000).toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", method)
                .put("params", params)
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(status in 200..299) { "Devnet RPC HTTP $status: $response" }
            val error = response.optJSONObject("error")
            require(error == null) { "Devnet RPC error: $error" }
            return response.getJSONObject("result")
        } finally {
            connection.disconnect()
        }
    }

    private fun disconnectSolanaWallet() {
        val authToken = walletAuthToken ?: runCatching { walletAuthStore.load()?.authToken }.getOrNull()
        if (authToken == null) {
            val cleared = runCatching { walletAuthStore.clear() }
            walletAddress = null
            walletStatus.text = if (cleared.isSuccess) {
                "Solana wallet: not connected · Devnet only"
            } else {
                "Local wallet authorization cleanup failed"
            }
            if (cleared.isSuccess) toast("Local wallet authorization data cleared")
            else toast(cleared.exceptionOrNull()?.message ?: "Unable to clear local wallet authorization")
            return
        }
        if (walletBusy) {
            toast("A wallet operation is already in progress")
            return
        }
        walletBusy = true
        val scenario = try {
            LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        } catch (error: Exception) {
            walletBusy = false
            toast(error.message ?: "Unable to create wallet association")
            return
        }
        walletScenario = scenario
        try {
            val associationIntent = LocalAssociationIntentCreator.createAssociationIntent(
                null, scenario.port, scenario.session
            )
            val connectionFuture = scenario.start()
            @Suppress("DEPRECATION")
            startActivityForResult(associationIntent, REQUEST_WALLET)
            Thread({
                var deauthorized = false
                try {
                    val client = connectionFuture.get(WALLET_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    client.deauthorize(authToken).get(WALLET_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    deauthorized = true
                } catch (error: Exception) {
                    runOnUiThread {
                        toast(error.cause?.message ?: error.message ?: "Wallet deauthorization failed")
                    }
                } finally {
                    runCatching { scenario.close().get(WALLET_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    runOnUiThread {
                        if (deauthorized) {
                            walletAuthStore.clear()
                            walletAuthToken = null
                            walletAddress = null
                            walletStatus.text = "Solana wallet: disconnected · Devnet"
                            append("Wallet authorization revoked")
                        }
                        if (walletScenario === scenario) walletScenario = null
                        walletBusy = false
                    }
                }
            }, "meshnet-wallet-deauthorize").apply { isDaemon = true; start() }
        } catch (error: Exception) {
            runCatching { scenario.close() }
            walletScenario = null
            walletBusy = false
            toast(error.message ?: "Unable to open wallet for deauthorization")
        }
    }

    private fun encodeBase58(bytes: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger(1, bytes)
        val radix = BigInteger.valueOf(58)
        val encoded = StringBuilder()
        while (value.signum() > 0) {
            val parts = value.divideAndRemainder(radix)
            encoded.append(alphabet[parts[1].toInt()])
            value = parts[0]
        }
        var leadingZeroes = 0
        while (leadingZeroes < bytes.size && bytes[leadingZeroes] == 0.toByte()) leadingZeroes++
        repeat(leadingZeroes) { encoded.append('1') }
        return encoded.reverse().toString()
    }

    private fun playLatestVoiceNote() {
        val file = receivedFileAccess.latestVoiceNote()
        if (file == null || !file.exists()) {
            toast("No received voice note is available")
            return
        }
        try {
            voicePlayer?.release()
            voicePlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { player ->
                    player.release()
                    if (voicePlayer === player) voicePlayer = null
                }
                prepare()
                start()
            }
            toast("Playing ${file.name}")
        } catch (error: Exception) {
            voicePlayer?.release()
            voicePlayer = null
            toast(error.message ?: "Unable to play voice note")
        }
    }

    private fun queueFile(uri: Uri) {
        val activeService = meshService
        if (activeService == null) {
            toast("Mesh service is not connected")
            return
        }
        transferStatus.text = "Preparing encrypted file transfer…"
        Thread({
            try {
                val accepted = activeService.sendFile(uri)
                runOnUiThread {
                    if (accepted) toast("File transfer completed")
                    else toast("File transfer paused or could not be fully queued; reselect the same file to resume")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    transferStatus.text = "File transfer error: ${error.message}"
                    toast(error.message ?: "Unable to queue file transfer")
                }
            }
        }, "meshnet-file-send").start()
    }

    private fun chooseReceivedFileForExport() {
        val files = receivedFileAccess.receivedFiles()
        if (files.isEmpty()) {
            toast("No completed received files are available")
            return
        }
        val names = files.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Save received file")
            .setItems(names) { _, index ->
                val selected = files.getOrNull(index) ?: return@setItems
                pendingExportFileName = selected.name
                val picker = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, selected.name)
                }
                startActivityForResult(picker, REQUEST_EXPORT_FILE)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPendingFileIfReady() {
        val name = pendingExportFileName ?: return
        val destination = pendingExportUri ?: return
        pendingExportFileName = null
        pendingExportUri = null
        Thread({
            try {
                receivedFileAccess.exportReceivedFile(name, destination)
                runOnUiThread { toast("Saved $name") }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "Unable to save received file") }
            }
        }, "meshnet-file-export").start()
    }

    private fun requestVoiceRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
            return
        }
        beginVoiceRecording()
    }

    private fun requestLiveVoice() {
        if (meshService?.isRunning() != true) {
            toast("Start the mesh before starting or joining a live voice call")
            joinVoiceAfterPermission = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_LIVE_VOICE)
            return
        }
        startOrJoinLiveVoice()
    }

    private fun startOrJoinLiveVoice() {
        try {
            if (joinVoiceAfterPermission) meshService?.joinIncomingLiveVoiceCall()
            else meshService?.startLiveVoiceCall()
            liveVoiceButton?.text = "End live group voice"
        } catch (error: Exception) {
            toast(error.message ?: "Unable to start live voice")
        } finally {
            joinVoiceAfterPermission = false
        }
    }

    private fun beginVoiceRecording() {
        val file = File(cacheDir, "mesh-voice-${System.currentTimeMillis()}.m4a")
        val media = createMediaRecorder()
        try {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioSamplingRate(16_000)
            media.setAudioEncodingBitRate(12_000)
            media.setMaxDuration(7_000)
            media.setOutputFile(file.absolutePath)
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED && isRecording) stopVoiceRecording()
            }
            media.prepare()
            media.start()
            recorder = media
            recordingFile = file
            isRecording = true
            voiceButton?.text = "Stop and send voice note"
            toast("Recording voice note · maximum 7 seconds")
        } catch (error: Exception) {
            runCatching { media.release() }
            file.delete()
            toast(error.message ?: "Unable to start voice recording")
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun stopVoiceRecording() {
        val media = recorder ?: return
        val file = recordingFile
        isRecording = false
        recorder = null
        recordingFile = null
        voiceButton?.text = "Record voice note (up to 7 seconds)"
        try {
            media.stop()
            file?.let {
                queueFile(Uri.fromFile(it))
            }
        } catch (error: Exception) {
            file?.delete()
            toast(error.message ?: "Voice recording was too short or could not be sent")
        } finally {
            runCatching { media.reset() }
            media.release()
        }
    }

    private fun requestAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE)
        } else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.S..32) permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startMeshService()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginVoiceRecording()
            else toast("Microphone permission is required to record a voice note")
            return
        }
        if (requestCode == REQUEST_LIVE_VOICE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startOrJoinLiveVoice()
            else {
                joinVoiceAfterPermission = false
                toast("Microphone permission is required for a live voice call")
            }
            return
        }
        if (requestCode == REQUEST_PERMISSIONS) {
            val bluetoothRequired = mutableSetOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) bluetoothRequired.add(Manifest.permission.ACCESS_FINE_LOCATION)
            val bluetoothGranted = permissions.indices
                .filter { permissions[it] in bluetoothRequired }
                .all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
            if (!bluetoothGranted) {
                toast("Bluetooth permission denied; BLE is unavailable. MeshNet will still start so you can use another supported local radio.")
            }
            if (permissions.indices.any {
                (permissions[it] == Manifest.permission.NEARBY_WIFI_DEVICES ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION)) &&
                    grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
            }) toast("Wi-Fi proximity permission denied; Wi-Fi Direct/Aware discovery may be unavailable.")
            if (permissions.indices.any {
                permissions[it] == Manifest.permission.POST_NOTIFICATIONS &&
                    grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
            }) toast("Notification permission denied; Android may show the foreground notification only in system task controls")
            startMeshService()
        }
    }

    private fun startMeshService() {
        val intent = Intent(this, MeshForegroundService::class.java).apply {
            action = MeshForegroundService.ACTION_START
            putExtra(MeshForegroundService.EXTRA_PASSPHRASE, keyInput.text.toString())
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            if (!bound) bound = bindService(Intent(this, MeshForegroundService::class.java), connection, BIND_AUTO_CREATE)
            status.text = "Starting local mesh service…"
            keyInput.text.clear()
        } catch (error: Exception) {
            toast(error.message ?: "Unable to start foreground service")
        }
    }

    private fun runWifiAction(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            toast(error.message ?: "Wi-Fi Direct action failed")
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            log.append("\n$line")
            status.text = line.take(90)
            if (line.startsWith("File transfer progress:") ||
                line.startsWith("Incoming file progress:") ||
                line.startsWith("File transfer completed:") ||
                line.contains("File transfer") && (line.contains("cancelled") || line.contains("paused"))
            ) transferStatus.text = line
            synchronized(recentEvents) {
                recentEvents.add(line)
                while (recentEvents.size > 30) recentEvents.removeAt(0)
            }
            publishRuntimeStatus(line)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        walletScenario?.let { scenario -> runCatching { scenario.close() } }
        walletScenario = null
        runCatching { receivedFileAccess.close() }
        webView?.apply {
            stopLoading()
            removeJavascriptInterface("MeshNetNative")
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    override fun onStop() {
        voicePlayer?.release()
        voicePlayer = null
        if (isRecording) {
            isRecording = false
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            recordingFile?.delete()
            recordingFile = null
        }
        meshService?.setListener(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        meshService = null
        super.onStop()
    }

    companion object {
        private const val KEY_ONLINE_INTEGRATIONS = "online_integrations_enabled"
        private const val REQUEST_PERMISSIONS = 4102
        private const val REQUEST_FILE = 4103
        private const val REQUEST_PUBLISH_CONTENT = 4108
        private const val REQUEST_OFFLINE_VPN = 4109
        private const val REQUEST_AUDIO = 4104
        private const val REQUEST_WALLET = 4106
        private const val REQUEST_EXPORT_FILE = 4107
        private const val WALLET_CONNECT_TIMEOUT_SECONDS = 60L
        private const val WALLET_RPC_TIMEOUT_SECONDS = 60L
        private const val SOLANA_DEVNET_RPC = "https://api.devnet.solana.com"
        private const val WALLET_CLOSE_TIMEOUT_SECONDS = 3L
        private const val REQUEST_LIVE_VOICE = 4105
    }
}
