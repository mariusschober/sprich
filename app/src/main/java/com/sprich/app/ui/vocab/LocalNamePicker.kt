package com.sprich.app.ui.vocab

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.ContactsContract
import com.sprich.app.vocab.VocabularyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads only the one app or contact the person explicitly chooses in an Android picker.
 * No package/contact list is queried, cached or persisted by Sprich.
 */
internal object LocalNamePicker {
    fun appIntent(): Intent = Intent(Intent.ACTION_PICK_ACTIVITY).putExtra(
        Intent.EXTRA_INTENT,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
    )

    fun contactIntent(): Intent = Intent(Intent.ACTION_PICK).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
    }

    suspend fun appDisplayName(context: Context, result: Intent?): String? = withContext(Dispatchers.IO) {
        val component = selectedComponent(result) ?: return@withContext null
        try {
            val manager = context.packageManager
            clean(manager.getActivityInfo(component, 0).loadLabel(manager))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun contactDisplayName(context: Context, uri: android.net.Uri?): String? = withContext(Dispatchers.IO) {
        if (uri?.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                null,
                null,
                null,
            )?.use { cursor ->
                val column = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                if (column >= 0 && cursor.moveToFirst()) clean(cursor.getString(column)) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    internal fun selectedComponent(result: Intent?): ComponentName? =
        result?.component ?: result?.intentExtra(Intent.EXTRA_INTENT)?.component

    internal fun clean(label: CharSequence?): String? = label?.toString()?.let(VocabularyText::clean)
        ?.takeIf { VocabularyText.validTerm(it) }

    private fun Intent.intentExtra(name: String): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(name, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
