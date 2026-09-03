package com.sprich.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** The smiling voice mark, drawn at any display size without a bitmap or idle animation. */
@Composable
fun VoiceMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val d = size.minDimension
        val c = center
        drawCircle(Brush.radialGradient(listOf(Color(0x22FF4D76), Color.Transparent), c, d / 2), d / 2)
        drawLine(Color(0xFFE94379), Offset(c.x, c.y + d * .22f), Offset(c.x, c.y + d * .37f), d * .07f, StrokeCap.Round)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFFFAA89), Color(0xFFFF647A), Color(0xFFE43C83)),
            Offset(c.x - d * .3f, c.y - d * .3f), Offset(c.x + d * .3f, c.y + d * .3f)), d * .34f, c)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFFFFF), Color.Transparent),
            Offset(c.x - d * .12f, c.y - d * .18f), d * .35f), d * .33f, c)
        drawArc(Color.White.copy(alpha = .94f), 28f, 124f, false,
            Offset(c.x - d * .14f, c.y - d * .08f), Size(d * .28f, d * .23f),
            style = Stroke(d * .025f, cap = StrokeCap.Round))
    }
}
