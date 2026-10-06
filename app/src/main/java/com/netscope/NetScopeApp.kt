package com.netscope

import android.app.Application
import android.os.Build
import com.netscope.core.util.NsLog
import dagger.hilt.android.HiltAndroidApp


@HiltAndroidApp
class NetScopeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NsLog.i(
            "NetScope 启动：apiLevel=${Build.VERSION.SDK_INT} " +
                "release=${Build.VERSION.RELEASE} " +
                "model=${Build.MANUFACTURER}/${Build.MODEL}",
        )
    }
}
