package com.sprich.app.vocab

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.sprich.app.ui.MainActivity
import com.sprich.app.ui.vocab.LocalNamePicker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalNamePickerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun intentsDelegateListsToAndroidAndRequestOnlyOneSelection() {
        val app = LocalNamePicker.appIntent()
        @Suppress("DEPRECATION")
        val choices = app.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_PICK_ACTIVITY, app.action)
        assertEquals(Intent.ACTION_MAIN, choices?.action)
        assertTrue(choices?.categories?.contains(Intent.CATEGORY_LAUNCHER) == true)

        val contact = LocalNamePicker.contactIntent()
        assertEquals(Intent.ACTION_PICK, contact.action)
        assertEquals("vnd.android.cursor.dir/contact", contact.type)
        assertNull(contact.data)
    }

    @Test fun selectedAppAndContactExposeOnlyValidatedDisplayNames() = runBlocking {
        val appResult = Intent().setClass(context, MainActivity::class.java)
        assertEquals("Sprich", LocalNamePicker.appDisplayName(context, appResult))

        ShadowContentResolver.registerProviderInternal("local.test.contacts", NameProvider())
        assertEquals("Zoë Smith", LocalNamePicker.contactDisplayName(context, Uri.parse("content://local.test.contacts/1")))
        assertNull(LocalNamePicker.contactDisplayName(context, Uri.parse("https://example.com/contact/1")))
        assertNull(LocalNamePicker.clean("\u0000hidden"))
        assertNull(LocalNamePicker.clean("x".repeat(129)))
    }

    @Test fun manifestDoesNotRequestAddressBookOrAllPackagesAccess() {
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        assertFalse(Manifest.permission.READ_CONTACTS in permissions)
        assertFalse(Manifest.permission.WRITE_CONTACTS in permissions)
        assertFalse(Manifest.permission.QUERY_ALL_PACKAGES in permissions)
    }

    private class NameProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection?.map { it }.orEmpty().toTypedArray().ifEmpty { arrayOf("display_name") }
            return MatrixCursor(columns).apply { addRow(Array(columns.size) { "  Zoë   Smith  " }) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }
}
