package com.sprich.app.speech.refinement

import com.sprich.app.speech.remote.ApiFailure

// Delegates to ai implementation to keep speech network-free (checked by scripts/check-apk.sh)
// Actual network code lives in com.sprich.app.ai.OpenAiCompatibleRefinementProvider
typealias OpenAiCompatibleRefinementProvider = com.sprich.app.ai.OpenAiCompatibleRefinementProvider
typealias RefinementException = com.sprich.app.ai.RefinementException
