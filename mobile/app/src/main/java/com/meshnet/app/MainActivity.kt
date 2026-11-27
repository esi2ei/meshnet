package com.meshnet.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var keyInput: EditText
    private lateinit var messageInput: EditText
    private var transport: MeshBleTransport? = null
    private var router: MeshRouter? = null
    private val nodeId = UUID.randomUUID().toString().take(8)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
        }
        root.addView(TextView(this).apply {
            text = "MeshNet · Native Android"
            textSize = 24f
        })
        status = TextView(this).apply { text = "Stopped · Node $nodeId" }
        root.addView(status)
        keyInput = EditText(this).apply {
            hint = "Shared group passphrase (24+ characters)"
            inputType = 0x00000081
        }
        root.addView(keyInput)
        root.addView(Button(this).apply {
            text = "Start BLE mesh"
            setOnClickListener { requestAndStart() }
        })
        messageInput = EditText(this).apply { hint = "Message (max 280 UTF-8 bytes)" }
        root.addView(messageInput)
        root.addView(Button(this).apply {
            text = "Send encrypted message"
            setOnClickListener {
                try {
                    router?.sendMessage(messageInput.text.toString())
                    messageInput.text.clear()
                } catch (error: Exception) {
                    toast(error.message ?: "Unable to send")
                }
            }
        })
        val scroll = ScrollView(this)
        log = TextView(this).apply { textSize = 13f }
        scroll.addView(log)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(TextView(this).apply {
            text = "Prototype limitation: all devices must share the same out-of-band passphrase. This is group AES-GCM encryption, not Signal, not forward-secret, and not production-audited."
            textSize = 11f
        })
        setContentView(root)
    }

    private fun requestAndStart() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return
        }
        startMesh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS &&
            grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startMesh()
        } else if (requestCode == REQUEST_PERMISSIONS) {
            toast("Bluetooth permissions are required for mesh discovery")
        }
    }

    private fun startMesh() {
        if (transport != null) return
        val crypto = try { MeshCrypto(keyInput.text.toString().toCharArray()) } catch (error: Exception) {
            toast(error.message ?: "Invalid passphrase")
            return
        }
        router = MeshRouter(nodeId, crypto, { bytes ->
            transport?.sendToPeers(bytes) ?: false
        }, { line -> append(line) })
        transport = MeshBleTransport(
            this,
            { address, connected -> append("${if (connected) "Connected" else "Disconnected"}: $address") },
            { _, payload -> router?.receive(payload) },
            { message -> append(message) }
        )
        try {
            transport?.start()
            status.text = "BLE mesh starting · Node $nodeId"
        } catch (error: Exception) {
            transport?.stop()
            transport = null
            router = null
            toast(error.message ?: "Unable to start BLE")
        }
    }

    private fun append(line: String) {
        runOnUiThread {
            log.append("\n$line")
            status.text = "BLE mesh active · Node $nodeId"
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        transport?.stop()
        transport = null
        super.onDestroy()
    }

    companion object { private const val REQUEST_PERMISSIONS = 4102 }
}
