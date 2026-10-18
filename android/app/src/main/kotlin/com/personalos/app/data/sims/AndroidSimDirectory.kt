package com.personalos.app.data.sims

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.personalos.app.core.sms.Sim
import com.personalos.app.core.sms.SimDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The active SIMs via [SubscriptionManager]. Permission-guarded; a missing
 * `READ_PHONE_STATE` is an empty list, so the app silently keeps the default SIM
 * instead of erroring.
 */
class AndroidSimDirectory(
    private val context: Context,
) : SimDirectory {
    override suspend fun active(): List<Sim> =
        withContext(Dispatchers.IO) {
            if (!granted()) return@withContext emptyList()
            runCatching {
                context
                    .getSystemService(SubscriptionManager::class.java)
                    .activeSubscriptionInfoList
                    .orEmpty()
                    .map { info ->
                        val label =
                            info.displayName?.toString()?.takeIf { it.isNotBlank() }
                                ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                                ?: "SIM ${info.simSlotIndex + 1}"
                        Sim(info.subscriptionId, label, info.simSlotIndex, info.iccId)
                    }.sortedBy { it.slotIndex }
            }.getOrDefault(emptyList())
        }

    private fun granted(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
}
