package snd.sketchpilot.app

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File

/** Captures startup crashes to app storage so failures are diagnosable without logcat. */
class SketchPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    append("Thread: ").append(thread.name).append('\n')
                    append("Time: ").append(System.currentTimeMillis()).append('\n')
                    append(error.stackTraceToString())
                }
                val local = File(filesDir, "sketchpilot-crash.txt")
                local.parentFile?.mkdirs()
                local.writeText(report)
                getExternalFilesDir(null)?.let { external ->
                    File(external, "sketchpilot-crash.txt").writeText(report)
                }
                runCatching {
                    File(android.os.Environment.getExternalStorageDirectory(),
                        "Download/sketchpilot-crash.txt").writeText(report)
                }
            }.onFailure { Log.e(TAG, "could not write crash report", it) }
            previous?.uncaughtException(thread, error)
                ?: Log.e(TAG, "unhandled exception", error)
        }
    }

    private companion object {
        const val TAG = "SketchPilotCrash"
    }
}

object Diagnostics {
    fun lastCrash(context: Context): String? {
        val file = File(context.filesDir, "sketchpilot-crash.txt")
        return if (file.isFile) file.readText().take(4_000) else null
    }
}
