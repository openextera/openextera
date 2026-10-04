package com.exteragram.messenger.debug

import android.app.Activity
import android.content.Intent
import androidx.core.content.FileProvider
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.FileLog
import java.io.File

fun debugFilesDir(name: String): File {
    val context = ApplicationLoader.applicationContext
    val base = context.getExternalFilesDir(null) ?: context.cacheDir
    return File(base, name)
}

fun shareDebugFile(activity: Activity, file: File, mimeType: String, title: String) {
    try {
        val uri = FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(Intent.createChooser(intent, title))
    } catch (e: Exception) {
        FileLog.e(e)
    }
}
