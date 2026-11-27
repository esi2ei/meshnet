package com.meshnet.app

import android.Manifest
import android.app.Activity
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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var keyInput: EditText
    private lateinit var messageInput: EditText
    private var meshService: MeshForegroundService? = null
    private var bound = false

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
            text = "Stop BLE mesh"
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

    private fun requestAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE)
        } else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
        if (requestCode == REQUEST_PERMISSIONS) {
            val bluetoothGranted = permissions.indices
                .filter { permissions[it] != Manifest.permission.POST_NOTIFICATIONS }
                .all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
            if (bluetoothGranted) {
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

    private fun append(line: String) {
        runOnUiThread {
            log.append("\n$line")
            status.text = line.take(90)
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStop() {
        meshService?.setListener(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        meshService = null
        super.onStop()
    }

    companion object { private const val REQUEST_PERMISSIONS = 4102 }
}
