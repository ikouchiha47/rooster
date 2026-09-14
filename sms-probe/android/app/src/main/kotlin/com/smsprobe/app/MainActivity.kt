package com.smsprobe.app

import android.app.Activity
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.Telephony
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var probeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(layout)

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(statusText)

        probeButton = Button(this).apply {
            text = "Read SMS"
            setOnClickListener { requestReadSmsPermission() }
        }
        layout.addView(probeButton)

        setContentView(scrollView)

        logInitialInfo()
        checkPermissionAndProbe()
    }

    private fun logInitialInfo() {
        val pkg = packageName
        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(this)
        val hasPermission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        Log.i("SmsProbe", "package=$pkg defaultSmsPkg=$defaultSmsPkg READ_SMS_granted=$hasPermission")
    }

    private fun checkPermissionAndProbe() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            probeButton.visibility = android.view.View.GONE
            runProbe()
        } else {
            updateStatus("READ_SMS not granted. Tap button to request.")
        }
    }

    private fun requestReadSmsPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.READ_SMS), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            probeButton.visibility = android.view.View.GONE
            runProbe()
        } else {
            updateStatus("READ_SMS denied. Cannot probe.")
            Log.e("SmsProbe", "READ_SMS permission denied by user")
        }
    }

    private fun runProbe() {
        updateStatus("Probing content://sms...")
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            if (cursor == null) {
                val msg = "probe failed: cursor is null (provider returned null)"
                Log.e("SmsProbe", msg)
                updateStatus(msg)
                return
            }

            val count = cursor.count
            if (count == 0) {
                val msg = "rows=0 (cursor empty — access may be silently filtered)"
                Log.i("SmsProbe", msg)
                updateStatus(msg)
                return
            }

            val sb = StringBuilder()
            sb.append("rows=$count\n")
            Log.i("SmsProbe", "rows=$count")

            val sampleLimit = minOf(5, count)
            for (i in 0 until sampleLimit) {
                cursor.moveToPosition(i)
                val addr = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)) ?: "null"
                val date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE))
                val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                val body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)) ?: ""
                val addrMasked = if (addr.length > 4) "${addr.substring(0, 4)}***" else addr
                val bodyClean = body.replace("\n", " ").replace("\r", " ")
                val bodyShort = if (bodyClean.length > 40) "${bodyClean.substring(0, 40)}..." else bodyClean
                val line = "addr=$addrMasked date=$date type=$type body=$bodyShort"
                sb.append(line).append("\n")
                Log.i("SmsProbe", line)
            }

            updateStatus(sb.toString())

        } catch (e: Exception) {
            val msg = "probe failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e("SmsProbe", msg, e)
            updateStatus(msg)
        } finally {
            cursor?.close()
        }
    }

    private fun updateStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }
}