package com.sprich.app.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val ReadableContentMaxWidth = 640.dp

/** Keep cards and body text scannable on tablets, foldables and desktop windows. */
internal fun Modifier.readableWidth(): Modifier = widthIn(max = ReadableContentMaxWidth)
