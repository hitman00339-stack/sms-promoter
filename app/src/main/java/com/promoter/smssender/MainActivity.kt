package com.promoter.smssender

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var etApiUrl: EditText
    private lateinit var spinnerSim: Spinner
    private lateinit var etDelay: EditText
    private lateinit var btnStart: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val simList = ArrayList<SubscriptionInfo>()
    private var isSending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiUrl = findViewById(R.id.etApiUrl)
        spinnerSim = findViewById(R.id.spinnerSim)
        etDelay = findViewById(R.id.etDelay)
        btnStart = findViewById(R.id.btnStart)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        checkPermissions()
        loadSimCards()

        btnStart.setOnClickListener {
            if (!isSending) {
                val apiUrl = etApiUrl.text.toString().trim()
                if (apiUrl.isEmpty()) {
                    Toast.makeText(this, "Enter Admin API URL", Toast.LENGTH_SHORT).show()
                } else {
                    startCampaign(apiUrl)
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE
        )
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 100)
                break
            }
        }
    }

    private fun loadSimCards() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return

        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
        val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList

        val simNames = ArrayList<String>()
        simList.clear()

        if (activeSubscriptions != null && activeSubscriptions.isNotEmpty()) {
            for (i in activeSubscriptions.indices) {
                val info = activeSubscriptions[i]
                simList.add(info)
                simNames.add("SIM ${i + 1}: ${info.carrierName} (${info.displayName})")
            }
        } else {
            simNames.add("No Active SIM Detected")
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, simNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSim.adapter = adapter
    }

    private fun startCampaign(apiUrl: String) {
        isSending = true
        btnStart.isEnabled = false
        tvStatus.text = "Fetching data from Admin Backend..."
        appendLog("\n[SYSTEM] Fetching campaign payload from API...")

        Thread {
            try {
                val url = URL(apiUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()

                    val json = JSONObject(response.toString())
                    val template = json.getString("template")
                    val barbers = json.getJSONArray("barbers")

                    runOnUiThread {
                        appendLog("[SUCCESS] Loaded: ${barbers.length()} barbers.")
                        processSending(template, barbers)
                    }
                } else {
                    runOnUiThread {
                        appendLog("[ERROR] API Code: HTTP ${conn.responseCode}")
                        resetUi()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendLog("[CRITICAL] Connection Error: ${e.message}")
                    resetUi()
                }
            }
        }.start()
    }

    private fun processSending(template: String, barbers: org.json.JSONArray) {
        // Defaults to 5-second delay if input is empty
        val delaySec = etDelay.text.toString().toLongOrNull() ?: 5L
        val selectedSimIndex = spinnerSim.selectedItemPosition

        if (simList.isEmpty()) {
            appendLog("[ERROR] No active SIM selected.")
            resetUi()
            return
        }

        val subId = simList[selectedSimIndex].subscriptionId

        Thread {
            for (i in 0 until barbers.length()) {
                if (i >= 100) break // Safety Hard Cap (100 free daily SMS limit)

                val item = barbers.getJSONObject(i)
                val phone = item.getString("phone")
                val name = item.getString("barber_name")
                val shop = item.getString("shop_name")

                val personalized = template
                    .replace("{barber_name}", name)
                    .replace("{shop_name}", shop)

                runOnUiThread {
                    tvStatus.text = "Sending ${i + 1} / ${barbers.length()}"
                    appendLog("[${i + 1}/${barbers.length()}] Sending to $name ($phone)...")
                }

                val success = sendSmsViaSim(subId, phone, personalized)

                runOnUiThread {
                    if (success) {
                        appendLog("   ✅ Delivered to SIM Network")
                    } else {
                        appendLog("   ❌ Failed to deliver")
                    }
                }

                // ⏱️ EXACT 5-SECOND DELAY SLEEP TIMER
                Thread.sleep(delaySec * 1000)
            }

            runOnUiThread {
                appendLog("[COMPLETE] All messages sent with ${delaySec}s delay!")
                tvStatus.text = "Status: Completed"
                resetUi()
            }
        }.start()
    }

    private fun sendSmsViaSim(subId: Int, phone: String, message: String): Boolean {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                this.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phone, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun appendLog(msg: String) {
        tvLog.append("\n$msg")
    }

    private fun resetUi() {
        isSending = false
        btnStart.isEnabled = true
    }
}