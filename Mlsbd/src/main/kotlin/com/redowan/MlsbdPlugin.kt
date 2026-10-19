package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MlsbdPlugin : Plugin() {
    override fun load(context: Context) {
        SourceStats.init(context)
        registerMainAPI(MlsbdProvider())
        registerExtractorAPI(GDFlix)
        registerExtractorAPI(GDxFiles)
        registerExtractorAPI(MultiCloudLinks)
        registerExtractorAPI(FilePress)
        registerExtractorAPI(HubCloud)
        registerExtractorAPI(Shikshakdaak)
        registerExtractorAPI(Gofile)
        registerExtractorAPI(Filesdl)
        registerExtractorAPI(M4ulinks)
    }
}
