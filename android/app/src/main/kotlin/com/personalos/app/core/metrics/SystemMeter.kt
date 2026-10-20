package com.personalos.app.core.metrics

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import android.os.SystemClock
import java.io.File

/** One point sample of device and process resource use. */
data class SystemSample(
    val cores: Int,
    val appCpuPercent: Float?,
    val ramUsedBytes: Long,
    val ramTotalBytes: Long,
    val appPssBytes: Long?,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val uptimeMs: Long,
    val threads: Int,
)

/**
 * CPU, RAM, storage and the app's own PSS, read cheaply enough to poll while a
 * diagnostics pane is open.
 *
 * `/proc/stat` and `/proc/loadavg` are blocked for apps on current Android, so CPU
 * is the app's own share: the utime+stime delta between two samples over wall
 * clock. It can exceed 100% when more than one thread is busy. The first sample
 * has no delta and reports null rather than a fabricated zero.
 */
class SystemMeter(
    private val context: Context,
) {
    private var lastCpuTicks: Long? = null
    private var lastCpuAt: Long? = null

    @Synchronized
    fun sample(): SystemSample {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity?.getMemoryInfo(memory)
        val pss = Debug.MemoryInfo()
        Debug.getMemoryInfo(pss)
        val stat = StatFs(context.filesDir.path)
        val now = SystemClock.elapsedRealtime()
        return SystemSample(
            cores = Runtime.getRuntime().availableProcessors(),
            appCpuPercent = appCpuPercent(now),
            ramUsedBytes = (memory.totalMem - memory.availMem).coerceAtLeast(0L),
            ramTotalBytes = memory.totalMem,
            appPssBytes =
                pss.totalPss
                    .takeIf { it > 0 }
                    ?.toLong()
                    ?.times(1024L),
            storageUsedBytes = (stat.blockCountLong - stat.availableBlocksLong).coerceAtLeast(0L) * stat.blockSizeLong,
            storageTotalBytes = stat.blockCountLong * stat.blockSizeLong,
            uptimeMs = now,
            threads = Thread.activeCount(),
        )
    }

    private fun appCpuPercent(now: Long): Float? {
        val ticks = readCpuTicks() ?: return null
        val previousTicks = lastCpuTicks
        val previousAt = lastCpuAt
        lastCpuTicks = ticks
        lastCpuAt = now
        if (previousTicks == null || previousAt == null || now <= previousAt) return null
        val tickPerSecond =
            runCatching {
                android.system.Os
                    .sysconf(android.system.OsConstants._SC_CLK_TCK)
                    .toFloat()
            }.getOrDefault(100f)
        val seconds = (now - previousAt) / 1000f
        return ((ticks - previousTicks) / tickPerSecond / seconds * 100f).coerceAtLeast(0f)
    }

    /** utime + stime from `/proc/self/stat`, in clock ticks. */
    private fun readCpuTicks(): Long? =
        runCatching {
            val stat = File("/proc/self/stat").readText()
            // The comm field may contain spaces and parentheses, so split after the
            // last ')'. The next field is state; utime/stime are 12th/13th of those.
            val fields = stat.substring(stat.lastIndexOf(')') + 1).trim().split(' ')
            fields[11].toLong() + fields[12].toLong()
        }.getOrNull()
}
