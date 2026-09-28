package com.my.camerainfo;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class CaptureContainerManager {

    private static final String TAG = "CaptureContainerMgr";
    private static final String CAPTURES_DIR_NAME = "captures";
    private static final String MANIFEST_FILE_NAME = "manifest.json";

    private final File baseCapturesDir;

    public CaptureContainerManager(Context context) {
        File externalFilesDir = context.getExternalFilesDir(null);
        if (externalFilesDir != null) {
            baseCapturesDir = new File(externalFilesDir, CAPTURES_DIR_NAME);
        } else {
            baseCapturesDir = new File(context.getFilesDir(), CAPTURES_DIR_NAME);
        }

        if (!baseCapturesDir.exists()) {
            boolean created = baseCapturesDir.mkdirs();
            Log.d(TAG, "Base captures directory created: " + created);
        }
    }

    public File getBaseCapturesDir() {
        return baseCapturesDir;
    }

    public File createSessionContainer(String prefix) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String sessionName = (prefix != null ? prefix : "SESSION") + "_" + timeStamp + "_" + (System.currentTimeMillis() % 10000);
        File sessionDir = new File(baseCapturesDir, sessionName);
        if (!sessionDir.exists()) {
            boolean created = sessionDir.mkdirs();
            Log.d(TAG, "Created session directory: " + sessionDir.getAbsolutePath() + " (" + created + ")");
        }
        return sessionDir;
    }

    public File saveManifest(File sessionDir, CaptureManifest manifest) {
        if (sessionDir == null || manifest == null) return null;
        File manifestFile = new File(sessionDir, MANIFEST_FILE_NAME);
        try {
            manifest.saveToFile(manifestFile);
            Log.d(TAG, "Saved manifest to: " + manifestFile.getAbsolutePath());
            return manifestFile;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save manifest", e);
            return null;
        }
    }

    public CaptureManifest loadManifest(File sessionDir) {
        if (sessionDir == null) return null;
        File manifestFile = new File(sessionDir, MANIFEST_FILE_NAME);
        if (!manifestFile.exists()) return null;
        try {
            return CaptureManifest.loadFromFile(manifestFile);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load manifest from " + manifestFile.getAbsolutePath(), e);
            return null;
        }
    }

    public File getManifestFile(File sessionDir) {
        return new File(sessionDir, MANIFEST_FILE_NAME);
    }

    public File saveFrameData(File sessionDir, int frameIndex, String extension, byte[] data) throws IOException {
        String fileName = String.format(Locale.US, "frame_%04d.%s", frameIndex, extension);
        File frameFile = new File(sessionDir, fileName);
        try (FileOutputStream fos = new FileOutputStream(frameFile)) {
            fos.write(data);
        }
        return frameFile;
    }

    public List<File> getAllSessionContainers() {
        List<File> sessions = new ArrayList<>();
        if (baseCapturesDir.exists() && baseCapturesDir.isDirectory()) {
            File[] files = baseCapturesDir.listFiles(File::isDirectory);
            if (files != null) {
                Arrays.sort(files, (f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
                sessions.addAll(Arrays.asList(files));
            }
        }
        return sessions;
    }

    public File exportContainerToZip(File sessionDir, Context context) throws IOException {
        if (sessionDir == null || !sessionDir.exists() || !sessionDir.isDirectory()) return null;

        File rawExportsBaseDir;
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            rawExportsBaseDir = new File(picturesDir, "CameraInfo/RawExports");
        } else {
            rawExportsBaseDir = new File(context.getFilesDir(), "RawExports");
        }

        String containerName = sessionDir.getName();
        File containerExportDir = new File(rawExportsBaseDir, containerName);
        File imagesExportDir = new File(containerExportDir, "images");

        if (!imagesExportDir.exists()) {
            imagesExportDir.mkdirs();
        }

        File[] files = sessionDir.listFiles();
        if (files == null || files.length == 0) return null;

        for (File file : files) {
            if (file.isDirectory()) continue;
            File destFile;
            if (file.getName().endsWith(".json")) {
                destFile = new File(containerExportDir, file.getName());
            } else {
                destFile = new File(imagesExportDir, file.getName());
            }
            copyFile(file, destFile);
        }

        File zipFile = new File(containerExportDir, containerName + ".zip");

        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            byte[] buffer = new byte[8192];

            File[] imageFiles = imagesExportDir.listFiles();
            if (imageFiles != null) {
                for (File img : imageFiles) {
                    if (img.isDirectory() || img.getName().endsWith(".zip")) continue;
                    try (FileInputStream fis = new FileInputStream(img)) {
                        ZipEntry zipEntry = new ZipEntry(containerName + "/images/" + img.getName());
                        zos.putNextEntry(zipEntry);
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                        zos.closeEntry();
                    }
                }
            }

            File[] rootFiles = containerExportDir.listFiles();
            if (rootFiles != null) {
                for (File rf : rootFiles) {
                    if (rf.isDirectory() || rf.getName().endsWith(".zip")) continue;
                    try (FileInputStream fis = new FileInputStream(rf)) {
                        ZipEntry zipEntry = new ZipEntry(containerName + "/" + rf.getName());
                        zos.putNextEntry(zipEntry);
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
        return zipFile;
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }
}
