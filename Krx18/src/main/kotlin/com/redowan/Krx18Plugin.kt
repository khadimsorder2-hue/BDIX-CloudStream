package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Krx18Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Krx18Provider())
    }
}
