package com.meshnet.app

import android.Manifest
import android.app.Activity
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var keyInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var wifiAddress: EditText
    private var meshService: MeshForegroundService? = null
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
    @Volatile private var walletScenario: LocalAssociationScenario? = null
    @Volatile private var walletBusy = false
    @Volatile private var walletAuthToken: String? = null
    @Volatile private var walletAddress: String? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            meshService = (binder as? MeshForegroundService.LocalBinder)?.service()
            meshService?.setListener(::append)
            status.text = if (meshService?.isRunning() == true) "BLE mesh running" else "Service connected · mesh stopped"
            append("Connected to MeshNet foreground service")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService?.setListener(null)
            meshService = null
            append("MeshNet service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, MeshForegroundService::class.java), connection, BIND_AUTO_CREATE)
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
        }
        root.addView(TextView(this).apply { text = "MeshNet · Native Android"; textSize = 24f })
        status = TextView(this).apply { text = "Stopped" }
        root.addView(status)
        walletStatus = TextView(this).apply { text = "Solana wallet: not connected · Devnet only" }
        root.addView(walletStatus)
        root.addView(Button(this).apply {
            text = "Connect Solana wallet (Devnet)"
            setOnClickListener { connectSolanaWalletDevnet() }
        })
        root.addView(Button(this).apply {
            text = "Disconnect Solana wallet"
            setOnClickListener { disconnectSolanaWallet() }
        })
        keyInput = EditText(this).apply {
            hint = "Group passphrase (24+ chars); blank reuses saved key"
            inputType = 0x00000081
        }
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = "Start / resume BLE mesh"
            setOnClickListener { requestAndStart() }
        })
        root.addView(Button(this).apply {
            text = "Create Wi-Fi Direct group"
            setOnClickListener { runWifiAction { meshService?.createWifiDirectGroup() } }
        })
        root.addView(Button(this).apply {
            text = "Discover Wi-Fi Direct peers"
            setOnClickListener { runWifiAction { meshService?.discoverWifiDirect() } }
        })
        root.addView(Button(this).apply {
            text = "Switch back to Wi-Fi Aware"
            setOnClickListener { runWifiAction { meshService?.resumeWifiAware() } }
        })
        wifiAddress = EditText(this).apply { hint = "Wi-Fi Direct device address (AA:BB:CC:DD:EE:FF)" }
        root.addView(wifiAddress)
        root.addView(Button(this).apply {
            text = "Connect to Wi-Fi Direct peer"
            setOnClickListener {
                val address = wifiAddress.text.toString().trim()
                runWifiAction { meshService?.connectWifiDirect(address) }
            }
        })
        root.addView(Button(this).apply {
            text = "Stop BLE / Wi-Fi mesh"
            setOnClickListener {
                startService(Intent(this@MainActivity, MeshForegroundService::class.java).apply {
                    action = MeshForegroundService.ACTION_STOP
                })
                status.text = "Stopping"
            }
        })
        root.addView(Button(this).apply {
            text = "Forget saved group credentials"
            setOnClickListener {
                startService(Intent(this@MainActivity, MeshForegroundService::class.java).apply {
                    action = MeshForegroundService.ACTION_FORGET_CREDENTIALS
                })
                status.text = "Deleting saved credentials"
            }
        })
        messageInput = EditText(this).apply { hint = "Message (max 120 UTF-8 bytes)" }
        root.addView(messageInput)
        root.addView(Button(this).apply {
            text = "Queue encrypted message"
            setOnClickListener {
                try {
                    val accepted = meshService?.sendMessage(messageInput.text.toString())
                        ?: throw IllegalStateException("Mesh service is not connected")
                    if (accepted) messageInput.text.clear()
                    else toast("Message was not accepted into the durable outbox")
                } catch (error: Exception) {
                    toast(error.message ?: "Unable to queue message")
                }
            }
        })
        voiceButton = Button(this).apply {
            text = "Record voice note (up to 7 seconds)"
            setOnClickListener {
                if (isRecording) stopVoiceRecording() else requestVoiceRecording()
            }
        }
        root.addView(voiceButton)
        root.addView(Button(this).apply {
            text = "Play latest received voice note"
            setOnClickListener { playLatestVoiceNote() }
        })
        liveVoiceButton = Button(this).apply {
            text = "Start live group voice"
            setOnClickListener {
                if (meshService?.isLiveVoiceActive() == true) {
                    runCatching { meshService?.endLiveVoiceCall() }
                        .onFailure { toast(it.message ?: "Unable to end live call") }
                    liveVoiceButton?.text = "Start live group voice"
                } else {
                    joinVoiceAfterPermission = false
                    requestLiveVoice()
                }
            }
        }
        root.addView(liveVoiceButton)
        joinVoiceButton = Button(this).apply {
            text = "Join incoming live voice"
            setOnClickListener {
                joinVoiceAfterPermission = true
                requestLiveVoice()
            }
        }
        root.addView(joinVoiceButton)
        root.addView(Button(this).apply {
            text = "Send file (max 256 KiB)"
            setOnClickListener {
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(picker, REQUEST_FILE)
            }
        })
        val scroll = ScrollView(this)
        log = TextView(this).apply { textSize = 13f }
        scroll.addView(log)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(TextView(this).apply {
            text = "Outbox and replay cache persist locally. Group receipts confirm reception by at least one member sharing the key, not a named recipient. Shared group AES-GCM is a prototype, not Signal or forward-secret."
            textSize = 11f
        })
        setContentView(root)
    }

    @Deprecated("Uses the platform document picker for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                queueFile(uri)
            } catch (error: Exception) {
                toast(error.message ?: "Unable to send file")
            }
        }
        if (requestCode == REQUEST_WALLET && resultCode == RESULT_CANCELED) {
            append("Wallet activity returned; checking the local association result")
        }
    }


    /**
     * Connects to a user-selected MWA-compatible wallet on Solana Devnet.
     * Authorization data is deliberately held only in memory. No payment transaction is
     * constructed or sent: the escrow program is still a non-deployed, unaudited draft.
     */
    private fun connectSolanaWalletDevnet() {
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
                    val account = authorization.accounts.firstOrNull()
                        ?: throw IllegalStateException("Wallet returned no authorized account")
                    val address = encodeBase58(account.publicKey)
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

    private fun disconnectSolanaWallet() {
        val authToken = walletAuthToken
        if (authToken == null) {
            walletAddress = null
            walletStatus.text = "Solana wallet: not connected · Devnet only"
            toast("No in-memory wallet authorization is active")
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
        val file = meshService?.latestReceivedVoiceNote()
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
        Thread({
            try {
                val accepted = activeService.sendFile(uri)
                runOnUiThread {
                    if (accepted) toast("File queued for encrypted mesh transfer")
                    else toast("File transfer could not be fully queued; check outbox capacity")
                }
            } catch (error: Exception) {
                runOnUiThread { toast(error.message ?: "Unable to queue file transfer") }
            }
        }, "meshnet-file-send").start()
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
            if (bluetoothGranted) {
                if (permissions.indices.any {
                    (permissions[it] == Manifest.permission.NEARBY_WIFI_DEVICES ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION)) &&
                        grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
                }) toast("Wi-Fi proximity permission denied; Wi-Fi Direct may be unavailable, BLE can still run")
                if (permissions.indices.any {
                    permissions[it] == Manifest.permission.POST_NOTIFICATIONS &&
                        grantResults.getOrNull(it) != PackageManager.PERMISSION_GRANTED
                }) toast("Notification permission denied; Android may show the foreground notification only in system task controls")
                startMeshService()
            } else toast("Bluetooth permissions are required for mesh discovery")
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
            status.text = "Starting foreground BLE service…"
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
            liveVoiceButton?.text = if (meshService?.isLiveVoiceActive() == true) "End live group voice" else "Start live group voice"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        walletScenario?.let { scenario -> runCatching { scenario.close() } }
        walletScenario = null
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
        private const val REQUEST_PERMISSIONS = 4102
        private const val REQUEST_FILE = 4103
        private const val REQUEST_AUDIO = 4104
        private const val REQUEST_WALLET = 4106
        private const val WALLET_CONNECT_TIMEOUT_SECONDS = 60L
        private const val WALLET_RPC_TIMEOUT_SECONDS = 60L
        private const val WALLET_CLOSE_TIMEOUT_SECONDS = 3L
        private const val REQUEST_LIVE_VOICE = 4105
    }
}
