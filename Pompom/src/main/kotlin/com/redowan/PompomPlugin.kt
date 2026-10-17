package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PompomPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PompomProvider())
    }
}
