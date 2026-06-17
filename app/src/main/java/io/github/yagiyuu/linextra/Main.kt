package io.github.yagiyuu.linextra

import android.app.Activity
import android.content.res.Resources
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.yagiyuu.linextra.Util.hideView

class Main : IXposedHookLoadPackage {
    companion object {
        private const val PACKAGE_NAME = "jp.naver.line.android"

        // bnb_timeline=VOOM, bnb_news=News, bnb_wallet=Wallet, bnb_home=Home, bnb_chat=Talk
        private val HIDDEN_TAB_IDS = arrayOf("bnb_timeline", "bnb_news", "bnb_wallet")

        private val hideOnAttachHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as? View ?: return
                if (view.visibility != View.GONE) {
                    view.hideView()
                }
            }
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        if (loadPackageParam.packageName != PACKAGE_NAME) return

        val classLoader = loadPackageParam.classLoader

        // ── Layout inflation hook ──────────────────────────────────────
        // Intercepts all ladsdk_* and ladsdk_v2_* layouts on inflation,
        // covering all 8+ placements (Smart Channel, Home, Timeline,
        // Album, Wallet, Open Chat, GCS, etc.)
        XposedHelpers.findAndHookMethod(
            LayoutInflater::class.java,
            "inflate",
            Int::class.javaPrimitiveType,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val resId = param.args[0] as Int
                    val context = (param.thisObject as LayoutInflater).context
                    val resName = try {
                        context.resources.getResourceEntryName(resId)
                    } catch (_: Exception) { return }
                    if (!resName.startsWith("ladsdk_") &&
                        resName != "home_tab_top_banner_row" &&
                        resName != "home_gcs_performance_ad_banner_row"
                    ) return
                    val view = param.result as? View ?: return
                    view.hideView()
                }
            }
        )

        // ── v2 LyadAdView fallback ─────────────────────────────────────
        try {
            val lyadAdView = classLoader.loadClass(
                "com.linecorp.line.ladsdk.p100ui.p103v2.common.lifecycle.LyadAdView"
            )
            XposedBridge.hookAllMethods(lyadAdView, "onAttachedToWindow", hideOnAttachHook)
        } catch (_: Throwable) { }

        // ── Google AdMob ───────────────────────────────────────────────
        try {
            val admobView = classLoader.loadClass("com.google.android.gms.ads.NativeAdView")
            XposedBridge.hookAllMethods(admobView, "onAttachedToWindow", hideOnAttachHook)
        } catch (_: Throwable) { }

        // ── Taboola ────────────────────────────────────────────────────
        try {
            val taboolaActivity = classLoader.loadClass(
                "com.taboola.lite_sdk.TBLWebViewActivity"
            )
            XposedHelpers.findAndHookMethod(
                taboolaActivity,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        (param.thisObject as Activity).finish()
                    }
                }
            )
        } catch (_: Throwable) { }

        // ── Thrift RPC ad request blocking ─────────────────────────────
        try {
            val thriftRequest = classLoader.loadClass("org.apache.thrift.l")
            XposedBridge.hookAllMethods(thriftRequest, "b", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val request = param.args[0]?.toString() ?: return
                    if (request == "getBanners" || request == "getPrefetchableBanners") {
                        param.result = null
                    }
                }
            })
        } catch (_: Throwable) { }

        // ── Home tab content recommendations (bottom sticker promo) ────
        XposedHelpers.findAndHookMethod(
            View::class.java,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (view.id == view.context.resources.getIdentifier(
                            "home_tab_contents_recommendation_placement", "id", PACKAGE_NAME
                        )
                    ) {
                        view.hideView()
                    }
                }
            }
        )

        // ── SmartChannel (existing) ────────────────────────────────────
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.linecorp.line.admolin.smartch.v2.view.SmartChannelViewLayout",
                classLoader
            ),
            "dispatchDraw",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.thisObject as? View)?.hideView()
                }
            }
        )

        // ── v1 LadAdView (existing) ────────────────────────────────────
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "com.linecorp.line.ladsdk.ui.common.view.lifecycle.LadAdView",
                classLoader
            ),
            "onAttachedToWindow",
            hideOnAttachHook
        )

        // ── Tab removal (existing) ─────────────────────────────────────
        XposedBridge.hookAllMethods(
            XposedHelpers.findClass(
                "jp.naver.line.android.activity.main.MainActivity",
                classLoader
            ),
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    val tabContainer = activity.findViewById<ViewGroup>(
                        activity.resources.getIdentifier(
                            "main_tab_container", "id", PACKAGE_NAME
                        )
                    ) ?: return
                    for (i in 0 until tabContainer.childCount) {
                        val childView = tabContainer.getChildAt(i)
                        val name = try {
                            activity.resources.getResourceEntryName(childView.id)
                        } catch (_: Resources.NotFoundException) { continue }
                        if (HIDDEN_TAB_IDS.any { name.startsWith(it) }) {
                            childView.hideView()
                        }
                    }
                }
            }
        )
    }
}
