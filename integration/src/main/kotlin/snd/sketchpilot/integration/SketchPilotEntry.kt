package snd.sketchpilot.integration

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Thin, defensive entry loaded inside Sketchware Pro.
 * Every operation is guarded: a SketchPilot failure must never break the host.
 */
class SketchPilotEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != HOST_PACKAGE) return
        runCatching {
            hookApplication(lpparam)
            hookDesignActivity(lpparam)
            Log.d(TAG, "integration installed for $HOST_PACKAGE")
        }.onFailure { Log.w(TAG, "integration install failed", it) }
    }

    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Application::class.java.name,
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val app = param.thisObject as Application
                        val info = app.packageManager.getPackageInfo(HOST_PACKAGE, 0)
                        Log.d(TAG, "host detected: $HOST_PACKAGE v${info.versionName} (${info.versionCode})")
                    }.onFailure { Log.w(TAG, "host detection failed", it) }
                }
            },
        )
    }

    private fun hookDesignActivity(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            DESIGN_ACTIVITY,
            lpparam.classLoader,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { attachEntryPoint(param.thisObject as Activity) }
                        .onFailure { Log.w(TAG, "entry point attach failed", it) }
                }
            },
        )
    }

    private fun attachEntryPoint(activity: Activity) {
        if (activity.isFinishing) return
        val toolbarId = activity.resources.getIdentifier("toolbar", "id", activity.packageName)
        if (toolbarId == 0) {
            Log.w(TAG, "toolbar not found; entry point skipped")
            return
        }
        val toolbar = activity.findViewById<ViewGroup>(toolbarId) ?: return
        if (toolbar.findViewWithTag<View>(ENTRY_TAG) != null) return

        val button = ImageView(activity).apply {
            tag = ENTRY_TAG
            setImageResource(android.R.drawable.ic_menu_manage)
            contentDescription = "Open SketchPilot"
            val size = (48 * activity.resources.displayMetrics.density).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
            setOnClickListener { openCompanion(activity) }
        }
        toolbar.addView(button)
    }

    private fun openCompanion(activity: Activity) {
        listOf("$COMPANION_PACKAGE.fresh", COMPANION_PACKAGE).forEach { packageName ->
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).setComponent(
                    ComponentName(packageName, COMPANION_ACTIVITY),
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            }.onFailure { Log.w(TAG, "could not open companion $packageName", it) }
        }
    }

    private companion object {
        const val TAG = "SketchPilot"
        const val HOST_PACKAGE = "pro.sketchware"
        const val COMPANION_PACKAGE = "snd.sketchpilot"
        const val COMPANION_ACTIVITY = "snd.sketchpilot.app.MainActivity"
        const val DESIGN_ACTIVITY = "com.besome.sketch.design.DesignActivity"
        const val ENTRY_TAG = "sketchpilot_entry"
    }
}
