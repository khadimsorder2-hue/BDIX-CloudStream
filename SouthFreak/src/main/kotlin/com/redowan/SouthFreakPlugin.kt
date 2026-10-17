package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SouthFreakPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SouthFreakProvider())
    }
}
