package com.redowan

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MlsbdPlugin : Plugin() {
    override fun load(context: Context) {
        // Activity context — required for the Cloudflare WebView solve dialog.
        MlsbdProvider.appContext = context
        MlsbdStorage.init(context)
        registerMainAPI(MlsbdProvider())
        registerExtractorAPI(GDFlix)
        registerExtractorAPI(GDxFiles)
        registerExtractorAPI(MultiCloudLinks)
        registerExtractorAPI(FilePress)
        registerExtractorAPI(HubCloud)
        registerExtractorAPI(Gofile)
        registerExtractorAPI(Filesdl)
        registerExtractorAPI(Shikshakdaak)
    }
}
