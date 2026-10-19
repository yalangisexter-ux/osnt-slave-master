package io.notrack.osint;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

final class AppUpdater {
    private static final String RELEASE_API = "https://api.github.com/repos/yalangisexter-ux/osnt-slave-master/releases/latest";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private AppUpdater() {}

    static void check(Activity activity, String apkName, TextView status) {
        status.setText("Checking for updates…");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASE_API).openConnection();
                c.setConnectTimeout(12000); c.setReadTimeout(12000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                try (InputStream in = c.getInputStream(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] b = new byte[8192]; int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    if (c.getResponseCode() != 200) throw new Exception("Release service returned HTTP " + c.getResponseCode());
                    JSONObject release = new JSONObject(out.toString("UTF-8"));
                    String tag = release.optString("tag_name", "");
                    int latestCode = versionFromTag(tag);
                    android.content.pm.PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                    int installedCode = Build.VERSION.SDK_INT >= 28 ? (int) info.getLongVersionCode() : info.versionCode;
                    JSONArray assets = release.optJSONArray("assets");
                    String downloadUrl = null;
                    if (assets != null) for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && apkName.equals(a.optString("name"))) { downloadUrl = a.optString("browser_download_url", null); break; }
                    }
                    if (latestCode <= installedCode) {
                        MAIN.post(() -> { status.setText("You’re up to date · " + tag); Toast.makeText(activity, "You already have the latest version.", Toast.LENGTH_SHORT).show(); });
                        return;
                    }
                    if (downloadUrl == null || downloadUrl.isEmpty()) throw new Exception("Matching APK was not found in the release");
                    // Check Android's install permission before downloading to avoid repeated large downloads.
                    // Standard Android apps cannot silently install APK updates on personal devices.
                    if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                        MAIN.post(() -> {
                            status.setText("Allow installs for this app, then tap Check for updates again.");
                            Toast.makeText(activity, "Enable Allow from this source. The APK will download after you return and retry.", Toast.LENGTH_LONG).show();
                            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                            activity.startActivity(settings);
                        });
                        return;
                    }
                    String finalUrl = downloadUrl;
                    MAIN.post(() -> status.setText("Downloading update " + tag + "…"));
                    HttpURLConnection d = (HttpURLConnection) new URL(finalUrl).openConnection();
                    d.setConnectTimeout(15000); d.setReadTimeout(30000); d.setInstanceFollowRedirects(true);
                    if (d.getResponseCode() != 200) throw new Exception("APK download failed");
                    File dir = new File(activity.getExternalFilesDir(null), "updates");
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cannot create update folder");
                    File apk = new File(dir, apkName);
                    try (InputStream input = d.getInputStream(); FileOutputStream output = new FileOutputStream(apk)) {
                        byte[] downloadBuffer = new byte[16384]; int bytesRead;
                        while ((bytesRead = input.read(downloadBuffer)) != -1) output.write(downloadBuffer, 0, bytesRead);
                        output.getFD().sync();
                    } finally { d.disconnect(); }
                    MAIN.post(() -> {
                        if (!sameSigningCertificate(activity, apk)) {
                            status.setText("Update downloaded, but its signing certificate differs from this installation.");
                            new android.app.AlertDialog.Builder(activity)
                                .setTitle("One-time reinstall required")
                                .setMessage("Android cannot install this APK over the current app because it was signed with a different certificate. This can happen when moving from older builds to the stable signing identity. Back up anything important, uninstall the current app, then install the downloaded APK again. Uninstalling may remove local app data. Future releases using the same signing identity should update normally.")
                                .setPositiveButton("OK", null)
                                .show();
                            return;
                        }
                        status.setText("Update downloaded · opening Android installer");
                        install(activity, apk);
                    });
                } finally { c.disconnect(); }
            } catch (Exception e) {
                MAIN.post(() -> {
                    status.setText("Update check failed. Check internet and retry.");
                    Toast.makeText(activity, "Update failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private static int versionFromTag(String tag) {
        try { String[] p = tag.replaceFirst("^[^0-9]*", "").split("\\."); return Integer.parseInt(p[p.length - 1]); }
        catch (Exception e) { return -1; }
    }

    private static boolean sameSigningCertificate(Activity activity, File apk) {
        try {
            android.content.pm.PackageManager pm = activity.getPackageManager();
            android.content.pm.PackageInfo installed;
            android.content.pm.PackageInfo candidate;
            if (Build.VERSION.SDK_INT >= 28) {
                installed = pm.getPackageInfo(activity.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
                if (candidate == null || installed.signingInfo == null || candidate.signingInfo == null) return true;
                android.content.pm.Signature[] current = installed.signingInfo.getApkContentsSigners();
                android.content.pm.Signature[] downloaded = candidate.signingInfo.getApkContentsSigners();
                return sameSignatures(current, downloaded);
            } else {
                installed = pm.getPackageInfo(activity.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
                candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
                if (candidate == null) return true;
                return sameSignatures(installed.signatures, candidate.signatures);
            }
        } catch (Exception ignored) {
            // If Android cannot expose archive signing metadata, let the platform installer decide.
            return true;
        }
    }

    private static boolean sameSignatures(android.content.pm.Signature[] first, android.content.pm.Signature[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        java.util.Set<String> a = new java.util.HashSet<>();
        java.util.Set<String> b = new java.util.HashSet<>();
        for (android.content.pm.Signature signature : first) a.add(signature.toCharsString());
        for (android.content.pm.Signature signature : second) b.add(signature.toCharsString());
        return a.equals(b);
    }

    private static void install(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
    }
}
