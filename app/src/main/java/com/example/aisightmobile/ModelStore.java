package com.example.aisightmobile;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Copies a bundled model out of the APK into app storage so TFLite can memory-map it.
 *
 * The old code copied it only if the file was missing, so after installing an UPDATED app (for example with a retrained
 * aisight.pt) the stale old model was kept forever. Now the copy is redone whenever the app was reinstalled/updated.
 */
final class ModelStore {
    private ModelStore() {}

    static File fileFor(Context context, String assetName) throws IOException {
        File target = new File(context.getFilesDir(), assetName);
        File stampFile = new File(context.getFilesDir(), assetName + ".stamp");
        String stamp = buildStamp(context);

        boolean fresh = target.exists() && target.length() > 0 && stampFile.exists() && stamp.equals(readText(stampFile));
        if (fresh) return target;

        File tmp = new File(context.getFilesDir(), assetName + ".tmp");
        try (InputStream in = context.getAssets().open(assetName); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) out.write(chunk, 0, n);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace old " + assetName);
        if (!tmp.renameTo(target)) throw new IOException("Could not install " + assetName);
        try (FileOutputStream out = new FileOutputStream(stampFile)) {
            out.write(stamp.getBytes(StandardCharsets.UTF_8));
        }
        return target;
    }

    /** Changes every time the app is installed or updated. */
    static String buildStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime + ":" + info.versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "unknown";
        }
    }

    private static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            int n = in.read(b);
            return new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
