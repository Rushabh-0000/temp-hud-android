package com.tempoverlay.mangohud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Runs after Android has completed replacing the installed package. */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        deleteContents(File(context.cacheDir, "updates"))
    }

    private fun deleteContents(folder: File) {
        val files = folder.listFiles() ?: return
        files.forEach { file ->
            if (file.isDirectory) deleteContents(file)
            // Only app-owned cache files are removed.
            file.delete()
        }
        folder.delete()
    }
}
