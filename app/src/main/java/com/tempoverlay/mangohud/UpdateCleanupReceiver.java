package com.tempoverlay.mangohud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;

/** The package manager sends this after an app update has completed. The installer has finished reading the APK. */
public final class UpdateCleanupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        File folder = new File(context.getCacheDir(), "updates");
        deleteContents(folder);
    }

    static void deleteContents(File folder) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) deleteContents(file);
            // Only cache files belonging to this app are touched.
            if (!file.delete() && file.exists()) file.deleteOnExit();
        }
        folder.delete();
    }
}
