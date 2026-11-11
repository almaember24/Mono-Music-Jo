package com.prism.music.data

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** What kind of phone Prism is on, for offering Performance mode (1.7.6). Nothing here leaves the phone. */
object DevicePower {
    /** 4 GB phones report a little under 4 GB to apps; this takes them in. */
    private const val SMALL_MEMORY = 4_400_000_000L

    /**
     * Android 9 or older, a phone that calls itself low on memory, or one with 4 GB or less: the phones GitHub's
     * slowness reports since 1.7 came from (a Galaxy Note 8 on Android 9, an Honor X6a).
     */
    fun mayBeSlow(context: Context): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) return true
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        if (am.isLowRamDevice) return true
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return info.totalMem in 1 until SMALL_MEMORY
    }
}
