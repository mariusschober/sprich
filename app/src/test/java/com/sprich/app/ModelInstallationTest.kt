package com.sprich.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.models.download.DownloadManager
import com.sprich.app.models.manager.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Exercises the real downloader, hash verification, tar extraction, receipt and atomic replacement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelInstallationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun availableStorage() {
        // Robolectric otherwise reports zero free bytes; exercise the real storage gate.
        ShadowStatFs.registerStats(context.filesDir.path, 100_000, 90_000, 90_000)
    }
    private fun archive(vararg files: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(output)).use { tar ->
            for ((name, value) in files) {
                val bytes = value.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }
    private fun entry(bytes: ByteArray) = ModelEntry("accurate", "unit fixture", "unit fixture", "test", "1",
        "https://github.com/example/release/model.tar.bz2", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        listOf("model.bin"), 4096, listOf("en"), "test", "test")
    private fun client(bytes: ByteArray, seen: MutableList<Request> = mutableListOf()) = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
        seen += chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(if (chain.request().url.host == "github.com") 302 else 200).message("fixture")
            .header("Location", "https://release-assets.githubusercontent.com/fixture/model")
            .body(if (chain.request().url.host == "github.com") ByteArray(0).toResponseBody() else bytes.toResponseBody()).build()
    }.build()
    @Test fun normalizedDotPrefixAndOnlyRequiredFilesInstallThroughAllowedRedirect() = runBlocking {
        val bytes = archive("./release/model.bin" to "verified weights", "./release/unused.bin" to "unused")
        val manager = ModelManager(context, ModelManifest(listOf(entry(bytes)))); manager.deleteAll()
        val seen = mutableListOf<Request>()
        DownloadManager(context, manager, client(bytes, seen)).downloadCanary()
        assertTrue(manager.verifyInstalled("accurate"))
        assertEquals("verified weights", File(manager.canaryDir(), "model.bin").readText())
        assertFalse(File(manager.canaryDir(), "unused.bin").exists())
        assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), seen.map { it.url.host })
        assertTrue(seen.all { it.header("Authorization") == null && it.header("Range") == null })
    }
    @Test fun ambiguousOrTraversingArchiveCannotBecomeReady() = runBlocking {
        for (files in listOf(arrayOf("a/model.bin" to "one", "b/model.bin" to "two"), arrayOf("../model.bin" to "escape"))) {
            val bytes = archive(*files); val manager = ModelManager(context, ModelManifest(listOf(entry(bytes)))); manager.deleteAll()
            assertTrue(runCatching { DownloadManager(context, manager, client(bytes)).downloadCanary() }.isFailure)
            assertFalse(manager.isCanaryReady())
            assertFalse(File(context.filesDir, "model.bin").exists())
        }
    }
    @Test fun corruptReplacementPreservesVerifiedInstallation() = runBlocking {
        val bytes = archive("release/model.bin" to "working")
        val manager = ModelManager(context, ModelManifest(listOf(entry(bytes)))); manager.deleteAll()
        DownloadManager(context, manager, client(bytes)).downloadCanary()
        val corrupt = bytes.clone().apply { this[100] = (this[100].toInt() xor 1).toByte() }
        assertTrue(runCatching { DownloadManager(context, manager, client(corrupt)).downloadCanary() }.isFailure)
        assertTrue(manager.isCanaryReady())
        assertEquals("working", File(manager.canaryDir(), "model.bin").readText())
    }
    @Test fun cancellationCleansTemporaryDataAndKeepsWorkingInstallation() = runBlocking {
        val bytes = archive("release/model.bin" to "working")
        val manager = ModelManager(context, ModelManifest(listOf(entry(bytes)))); manager.deleteAll()
        val downloader = DownloadManager(context, manager, client(bytes))
        downloader.downloadCanary()
        val result = runCatching { downloader.downloadCanary { downloader.cancel() } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(manager.isCanaryReady())
        assertFalse(File(context.cacheDir, "model-accurate.tar.bz2").exists())
        assertFalse(File(context.filesDir, "canary.staging").exists())
    }
    @Test fun insufficientSpaceDoesNotTouchWorkingInstallation() = runBlocking {
        val bytes = archive("release/model.bin" to "working")
        val model = entry(bytes)
        val manager = ModelManager(context, ModelManifest(listOf(model))); manager.deleteAll()
        DownloadManager(context, manager, client(bytes)).downloadCanary()
        val noSpace = ModelManager(context, ModelManifest(listOf(model.copy(requiredFreeBytes = Long.MAX_VALUE))))
        assertTrue(runCatching { DownloadManager(context, noSpace, client(bytes)).downloadCanary() }.isFailure)
        assertTrue(manager.isCanaryReady())
        assertEquals("working", File(manager.canaryDir(), "model.bin").readText())
    }
    @Test fun cancellingBlockingHttpReadDoesNotPublishAnError() = runBlocking {
        val bytes = archive("release/model.bin" to "working")
        val manager = ModelManager(context, ModelManifest(listOf(entry(bytes)))); manager.deleteAll()
        val entered = CompletableDeferred<Unit>()
        val blocking = OkHttpClient.Builder().addInterceptor { chain ->
            entered.complete(Unit)
            while (!chain.call().isCanceled()) Thread.sleep(5)
            throw java.io.IOException("Canceled")
        }.build()
        val downloader = DownloadManager(context, manager, blocking)
        val pending = async { runCatching { downloader.downloadCanary() } }
        withTimeout(2000) { entered.await() }
        downloader.cancel()
        assertTrue(withTimeout(2000) { pending.await() }.exceptionOrNull() is CancellationException)
        assertSame(ModelStatus.NotDownloaded, manager.canaryStatus.value)
        assertFalse(File(context.cacheDir, "model-accurate.tar.bz2").exists())
    }
}
