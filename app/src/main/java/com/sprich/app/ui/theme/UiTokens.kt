package com.sprich.app.ui.theme

/**
 * Shared visual tokens from plan §9.1, expressed as plain dp/ms numbers so both
 * Compose screens and the native IME views resolve identical values without a
 * font or asset download. Colors live in [SprichTheme]; type baselines are
 * body 16sp, actionable labels ≥14sp, status 15–16sp medium.
 */
object UiTokens {
    /** Standard collapsed voice-bar minimum content height, excluding navigation insets. */
    const val BAR_MIN_CONTENT_HEIGHT_DP = 72f

    /** Transcript-preview bar height target at ordinary font scale; never clip accessibility text. */
    const val BAR_PREVIEW_MIN_HEIGHT_DP = 120f
    const val BAR_PREVIEW_MAX_HEIGHT_DP = 184f

    /** Minimum interactive target size. */
    const val MIN_TOUCH_TARGET_DP = 48f

    /** Card/bar corner radius. */
    const val CORNER_RADIUS_DP = 24f

    /** Spacing increment. */
    const val SPACING_UNIT_DP = 8f

    /** Horizontal phone margin range. */
    const val MARGIN_HORIZONTAL_MIN_DP = 12f
    const val MARGIN_HORIZONTAL_MAX_DP = 16f

    /** Completion feedback window: brief, and never gates the next utterance. */
    const val COMPLETION_FEEDBACK_MIN_MS = 400L
    const val COMPLETION_FEEDBACK_MAX_MS = 600L
}
