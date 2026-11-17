package com.victorcrypty.max

import android.content.Context
import java.io.File

object CrashReporter {
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(context.filesDir, "last_crash.txt").writeText(e.stackTraceToString())
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(context: Context): String? {
        val f = File(context.filesDir, "last_crash.txt")
        return if (f.exists()) f.readText() else null
    }

    fun clear(context: Context) {
        File(context.filesDir, "last_crash.txt").delete()
    }
}
