package com.my.camerainfo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Locale;

public class PostProcessingEngine {

    private static final String TAG = "PostProcessingEngine";
    private static final int THUMB_SIZE = 64;

    public interface ProgressCallback {
        void onProgress(int currentFrame, int totalFrames, String statusMsg);
        void onCompleted(File outputFile);
        void onError(String errorMsg);
    }

    public static void reprocessSessionContainerAsync(File sessionDir, FrameStackingEngine.StackingMode mode, boolean enableAlignment, ProgressCallback callback) {
        new Thread(() -> {
            try {
                if (sessionDir == null || !sessionDir.exists() || !sessionDir.isDirectory()) {
                    if (callback != null) callback.onError("Invalid session directory");
                    return;
                }

                File[] frameFiles = sessionDir.listFiles((dir, name) -> name.startsWith("frame_") && (name.endsWith(".yuv") || name.endsWith(".jpg") || name.endsWith(".dng")));
                if (frameFiles == null || frameFiles.length == 0) {
                    if (callback != null) callback.onError("No frame files found in container");
                    return;
                }

                Arrays.sort(frameFiles, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                int totalFrames = frameFiles.length;

                if (callback != null) {
                    callback.onProgress(0, totalFrames, "Starting reprocessing of " + totalFrames + " frames...");
                }

                System.gc();

                Bitmap firstBitmap = decodeFrameToBitmap(frameFiles[0]);
                if (firstBitmap == null) {
                    if (callback != null) callback.onError("Failed to decode first frame in container");
                    return;
                }

                int width = firstBitmap.getWidth();
                int height = firstBitmap.getHeight();
                firstBitmap.recycle();

                int numPixels = width * height;

                // Memory-efficient int[] accumulators
                int[] sumR = (mode != FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;
                int[] sumG = (mode != FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;
                int[] sumB = (mode != FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;

                float[] weightSum = (mode == FrameStackingEngine.StackingMode.HDR_MERGE) ? new float[numPixels] : null;

                int[] maxR = (mode == FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;
                int[] maxG = (mode == FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;
                int[] maxB = (mode == FrameStackingEngine.StackingMode.LIGHT_TRAILS) ? new int[numPixels] : null;

                byte[] refLuma = (mode == FrameStackingEngine.StackingMode.MOTION_DEGHOSTING) ? new byte[numPixels] : null;
                byte[] refThumb = null;

                int processedCount = 0;

                for (int idx = 0; idx < frameFiles.length; idx++) {
                    File frameFile = frameFiles[idx];
                    Bitmap bmp = decodeFrameToBitmap(frameFile);
                    if (bmp != null) {
                        // Ensure all frames match target dimensions exactly to prevent getPixels bounds crashes
                        if (bmp.getWidth() != width || bmp.getHeight() != height) {
                            Bitmap scaled = Bitmap.createScaledBitmap(bmp, width, height, true);
                            bmp.recycle();
                            bmp = scaled;
                        }

                        int[] pixels = new int[numPixels];
                        bmp.getPixels(pixels, 0, width, 0, 0, width, height);
                        bmp.recycle();

                        int shiftX = 0;
                        int shiftY = 0;

                        if (enableAlignment) {
                            byte[] currentThumb = createThumbLumaFromPixels(pixels, width, height);
                            if (refThumb == null) {
                                refThumb = currentThumb;
                            } else {
                                int[] shift = estimateShift(refThumb, currentThumb);
                                shiftX = shift[0] * (width / THUMB_SIZE);
                                shiftY = shift[1] * (height / THUMB_SIZE);
                            }
                        }

                        boolean isFirst = (processedCount == 0);

                        for (int j = 0; j < height; j++) {
                            int srcY = j - shiftY;
                            if (srcY < 0 || srcY >= height) continue;

                            for (int i = 0; i < width; i++) {
                                int srcX = i - shiftX;
                                if (srcX < 0 || srcX >= width) continue;

                                int targetIndex = j * width + i;
                                int srcIndex = srcY * width + srcX;

                                int c = pixels[srcIndex];
                                int r = (c >> 16) & 0xFF;
                                int g = (c >> 8) & 0xFF;
                                int b = c & 0xFF;

                                if (mode == FrameStackingEngine.StackingMode.LIGHT_TRAILS) {
                                    if (r > maxR[targetIndex]) maxR[targetIndex] = r;
                                    if (g > maxG[targetIndex]) maxG[targetIndex] = g;
                                    if (b > maxB[targetIndex]) maxB[targetIndex] = b;
                                } else if (mode == FrameStackingEngine.StackingMode.HDR_MERGE) {
                                    int yVal = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                                    float w = 1.0f - (Math.abs(yVal - 128.0f) / 128.0f);
                                    if (w < 0.05f) w = 0.05f;
                                    sumR[targetIndex] += (int) (r * w);
                                    sumG[targetIndex] += (int) (g * w);
                                    sumB[targetIndex] += (int) (b * w);
                                    weightSum[targetIndex] += w;
                                } else if (mode == FrameStackingEngine.StackingMode.MOTION_DEGHOSTING) {
                                    int yVal = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                                    if (isFirst) {
                                        refLuma[targetIndex] = (byte) yVal;
                                        sumR[targetIndex] += r;
                                        sumG[targetIndex] += g;
                                        sumB[targetIndex] += b;
                                    } else {
                                        int refY = refLuma[targetIndex] & 0xFF;
                                        int diff = Math.abs(yVal - refY);
                                        if (diff < 40) {
                                            sumR[targetIndex] += r;
                                            sumG[targetIndex] += g;
                                            sumB[targetIndex] += b;
                                        } else {
                                            sumR[targetIndex] += (int) (r * 0.1f + (sumR[targetIndex] / (float) processedCount) * 0.9f);
                                            sumG[targetIndex] += (int) (g * 0.1f + (sumG[targetIndex] / (float) processedCount) * 0.9f);
                                            sumB[targetIndex] += (int) (b * 0.1f + (sumB[targetIndex] / (float) processedCount) * 0.9f);
                                        }
                                    }
                                } else { // SMOOTH_AVERAGE & NIGHT_STACK
                                    sumR[targetIndex] += r;
                                    sumG[targetIndex] += g;
                                    sumB[targetIndex] += b;
                                }
                            }
                        }
                        processedCount++;
                    }

                    if (callback != null) {
                        callback.onProgress(idx + 1, totalFrames, "Processing frame " + (idx + 1) + "/" + totalFrames);
                    }
                }

                if (processedCount == 0) {
                    if (callback != null) callback.onError("No valid frames could be decoded");
                    return;
                }

                int[] finalPixels = new int[numPixels];
                if (mode == FrameStackingEngine.StackingMode.LIGHT_TRAILS) {
                    for (int i = 0; i < finalPixels.length; i++) {
                        finalPixels[i] = 0xFF000000 | (maxR[i] << 16) | (maxG[i] << 8) | maxB[i];
                    }
                } else if (mode == FrameStackingEngine.StackingMode.HDR_MERGE) {
                    for (int i = 0; i < finalPixels.length; i++) {
                        float w = weightSum[i] > 0 ? weightSum[i] : 1.0f;
                        int r = Math.min(255, Math.max(0, (int) (sumR[i] / w)));
                        int g = Math.min(255, Math.max(0, (int) (sumG[i] / w)));
                        int b = Math.min(255, Math.max(0, (int) (sumB[i] / w)));
                        finalPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                } else {
                    for (int i = 0; i < finalPixels.length; i++) {
                        int r = Math.min(255, sumR[i] / processedCount);
                        int g = Math.min(255, sumG[i] / processedCount);
                        int b = Math.min(255, sumB[i] / processedCount);
                        finalPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }

                Bitmap outBmp = Bitmap.createBitmap(finalPixels, width, height, Bitmap.Config.ARGB_8888);
                File outputFile = new File(sessionDir, "output_reprocessed_" + mode.name().toLowerCase(Locale.US) + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    outBmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                }
                outBmp.recycle();

                if (callback != null) {
                    callback.onCompleted(outputFile);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error reprocessing session container", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        }).start();
    }

    private static Bitmap decodeFrameToBitmap(File frameFile) {
        if (frameFile == null || !frameFile.exists()) return null;
        String name = frameFile.getName().toLowerCase(Locale.US);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".dng")) {
            return BitmapFactory.decodeFile(frameFile.getAbsolutePath(), options);
        }

        if (name.endsWith(".yuv")) {
            try (FileInputStream fis = new FileInputStream(frameFile)) {
                byte[] yuvBytes = new byte[(int) frameFile.length()];
                int read = fis.read(yuvBytes);
                if (read <= 0) return null;

                File sessionDir = frameFile.getParentFile();
                int width = 1920;
                int height = 1080;
                if (sessionDir != null) {
                    File manifestFile = new File(sessionDir, "manifest.json");
                    if (manifestFile.exists()) {
                        CaptureManifest manifest = CaptureManifest.loadFromFile(manifestFile);
                        if (manifest != null && manifest.frameWidth > 0 && manifest.frameHeight > 0) {
                            width = manifest.frameWidth;
                            height = manifest.frameHeight;
                        }
                    }
                }

                // Check NV21 byte length against dimensions to avoid YuvImage bounds error
                int expectedLen = width * height * 3 / 2;
                if (expectedLen != yuvBytes.length) {
                    int expectedPixels = (yuvBytes.length * 2) / 3;
                    if (width > 0 && expectedPixels % width == 0) {
                        height = expectedPixels / width;
                    } else {
                        width = 1920;
                        height = 1080;
                    }
                }

                YuvImage yuvImage = new YuvImage(yuvBytes, ImageFormat.NV21, width, height, null);
                ByteArrayOutputStream outStream = new ByteArrayOutputStream();
                yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, outStream);
                byte[] jpegBytes = outStream.toByteArray();
                return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, options);
            } catch (Exception e) {
                Log.e(TAG, "Error decoding YUV file " + frameFile.getName(), e);
            }
        }
        return null;
    }

    private static byte[] createThumbLumaFromPixels(int[] pixels, int width, int height) {
        byte[] thumb = new byte[THUMB_SIZE * THUMB_SIZE];
        int xRatio = width / THUMB_SIZE;
        int yRatio = height / THUMB_SIZE;

        for (int ty = 0; ty < THUMB_SIZE; ty++) {
            int sy = ty * yRatio;
            int yOffset = sy * width;
            for (int tx = 0; tx < THUMB_SIZE; tx++) {
                int sx = tx * xRatio;
                int c = pixels[yOffset + sx];
                int r = (c >> 16) & 0xFF;
                int g = (c >> 8) & 0xFF;
                int b = c & 0xFF;
                thumb[ty * THUMB_SIZE + tx] = (byte) ((int) (0.299f * r + 0.587f * g + 0.114f * b));
            }
        }
        return thumb;
    }

    private static int[] estimateShift(byte[] ref, byte[] current) {
        int bestShiftX = 0;
        int bestShiftY = 0;
        long minDiff = Long.MAX_VALUE;

        for (int sy = -3; sy <= 3; sy++) {
            for (int sx = -3; sx <= 3; sx++) {
                long diff = 0;
                for (int ty = 3; ty < THUMB_SIZE - 3; ty++) {
                    int cy = ty + sy;
                    for (int tx = 3; tx < THUMB_SIZE - 3; tx++) {
                        int cx = tx + sx;
                        int refVal = ref[ty * THUMB_SIZE + tx] & 0xFF;
                        int curVal = current[cy * THUMB_SIZE + cx] & 0xFF;
                        diff += Math.abs(refVal - curVal);
                    }
                }
                if (diff < minDiff) {
                    minDiff = diff;
                    bestShiftX = sx;
                    bestShiftY = sy;
                }
            }
        }
        return new int[]{bestShiftX, bestShiftY};
    }
}
