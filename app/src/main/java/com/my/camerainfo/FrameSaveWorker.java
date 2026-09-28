package com.my.camerainfo;

import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class FrameSaveWorker {

    private static final String TAG = "FrameSaveWorker";

    private final File sessionDir;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    public FrameSaveWorker(File sessionDir) {
        this.sessionDir = sessionDir;
    }

    public void saveYuvFrameAsync(byte[] yuvBytes) {
        if (sessionDir == null || yuvBytes == null) return;
        int index = frameCounter.incrementAndGet();
        executorService.submit(() -> {
            try {
                String fileName = String.format(Locale.US, "frame_%04d.yuv", index);
                File file = new File(sessionDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(yuvBytes);
                }
            } catch (IOException e) {
                Log.e(TAG, "Error saving YUV frame " + index, e);
            }
        });
    }

    public void saveDngFrameAsync(DngCreator dngCreator, Image rawImage, TotalCaptureResult result) {
        if (sessionDir == null || dngCreator == null || rawImage == null || result == null) return;
        int index = frameCounter.incrementAndGet();
        executorService.submit(() -> {
            try {
                String fileName = String.format(Locale.US, "frame_%04d.dng", index);
                File file = new File(sessionDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    dngCreator.writeImage(fos, rawImage);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving DNG frame " + index, e);
            } finally {
                rawImage.close();
            }
        });
    }

    public void shutdown() {
        executorService.shutdown();
    }

    public int getSavedFrameCount() {
        return frameCounter.get();
    }
}
