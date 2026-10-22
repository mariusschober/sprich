package com.sprich.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.sprich.app.ui.ReadableContentMaxWidth
import com.sprich.app.ui.readableWidth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h800dp", application = android.app.Application::class)
class ReadableWidthTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wideScreensCapReadableContent() {
        compose.setContent {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.readableWidth().fillMaxWidth().height(10.dp).testTag("content"))
            }
        }
        val width = compose.onNodeWithTag("content").fetchSemanticsNode().size.width
        val cap = with(compose.density) { ReadableContentMaxWidth.roundToPx() }
        assertTrue("width=$width cap=$cap", width in 1..cap)
    }
}
