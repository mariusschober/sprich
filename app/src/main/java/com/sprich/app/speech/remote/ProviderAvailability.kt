package com.sprich.app.speech.remote

import com.sprich.app.api.ApiCatalog

/** Adapter availability is distinct from permission: release runtime also requires a successful per-capability check. */
object ProviderAvailability {
    fun isEnabled(id: String) = ApiCatalog.supports(id) || (com.sprich.app.BuildConfig.DEBUG && id == "meta-muse")
}
