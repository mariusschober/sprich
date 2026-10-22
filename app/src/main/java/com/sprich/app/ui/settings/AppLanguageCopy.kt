package com.sprich.app.ui.settings

import com.sprich.app.R

/** Android only has app-specific locale settings from API 33; older versions open phone locale. */
internal data class AppLanguageCopy(val title: Int, val description: Int)

internal fun appLanguageCopy(sdk: Int): AppLanguageCopy =
    if (sdk >= 33) AppLanguageCopy(R.string.app_language, R.string.app_language_description)
    else AppLanguageCopy(R.string.phone_language, R.string.phone_language_description)
