package com.sprich.app

import com.sprich.app.ui.settings.appLanguageCopy
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageCopyTest {
    @Test fun olderAndroidIsLabelledAsPhoneLanguage() {
        for (sdk in listOf(26, 27, 29, 30, 31, 32)) {
            val copy = appLanguageCopy(sdk)
            assertEquals(R.string.phone_language, copy.title)
            assertEquals(R.string.phone_language_description, copy.description)
        }
    }

    @Test fun android13AndNewerUseAppSpecificLanguage() {
        for (sdk in listOf(33, 34, 35, 36)) {
            val copy = appLanguageCopy(sdk)
            assertEquals(R.string.app_language, copy.title)
            assertEquals(R.string.app_language_description, copy.description)
        }
    }
}
