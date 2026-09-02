package com.sprich.app

import com.sprich.app.models.download.ModelRedirectPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ModelRedirectPolicyTest {
    @Test fun permitsGitHubAssetRedirectWithoutCredentials() {
        val from = "https://github.com/k2-fsa/sherpa-onnx/releases/download/models/model.tar.bz2".toHttpUrl()
        assertTrue(ModelRedirectPolicy.allowed(ModelRedirectPolicy.next(from, "https://release-assets.githubusercontent.com/github-production-release-asset/test?sig=asset", 0)))
    }
    @Test fun rejectsDowngradeCredentialsForeignHostAndUnboundedChains() {
        val from = "https://github.com/model".toHttpUrl()
        for (target in listOf("http://release-assets.githubusercontent.com/file", "https://github.com.evil.example/file", "https://user:password@github.com/file", "https://github.com:444/file", "https://example.com/file")) {
            assertThrows(IllegalArgumentException::class.java) { ModelRedirectPolicy.next(from, target, 0) }
        }
        assertThrows(IllegalArgumentException::class.java) { ModelRedirectPolicy.next(from, "/model", 4) }
    }
}
