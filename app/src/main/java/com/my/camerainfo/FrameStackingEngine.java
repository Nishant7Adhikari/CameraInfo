package com.my.camerainfo;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class FrameStackingEngine {

    private static final String TAG = "FrameStackingEngine";

    public enum StackingMode {
        SMOOTH_AVERAGE,   // Silky water / motion blur
        LIGHT_TRAILS,     // Star trails / light painting
        NIGHT_STACK,      // Low-light noise reduction
        HDR_MERGE,        // Multi-exposure dynamic range expansion
        MOTION_DEGHOSTING // Movement-aware blending to avoid ghosts
    }

    private final StackingMode mode;
    private final int width;
    private final int height;
    private final boolean alignmentEnabled;

    private int frameCount = 0;

    // Accumulator Buffers (using int[] and float[] to save 50% RAM and avoid OOM)
    private int[] sumR;
    private int[] sumG;
    private int[] sumB;
    private float[] weightSum;

    private int[] maxR;
    private int[] maxG;
    private int[] maxB;

    // Reference frame luma for motion deghosting
    private byte[] refLuma;

    // First frame downsampled thumbnail for alignment (64x64)
    private byte[] refThumbLuma;
    private static final int THUMB_SIZE = 64;

    public FrameStackingEngine(StackingMode mode, int width, int height, boolean alignmentEnabled) {
        this.mode = mode;
        this.width = width;
        this.height = height;
        this.alignmentEnabled = alignmentEnabled;

        int numPixels = width * height;

        if (mode == StackingMode.LIGHT_TRAILS) {
            maxR = new int[numPixels];
            maxG = new int[numPixels];
            maxB = new int[numPixels];
        } else {
            sumR = new int[numPixels];
            sumG = new int[numPixels];
            sumB = new int[numPixels];
            if (mode == StackingMode.HDR_MERGE) {
                weightSum = new float[numPixels];
            }
            if (mode == StackingMode.MOTION_DEGHOSTING) {
                refLuma = new byte[numPixels];
            }
        }
    }

    public synchronized void processYuvFrame(Image image) {
        if (image == null) return;

        try {
            Image.Plane yPlane = image.getPlanes()[0];
            Image.Plane uPlane = image.getPlanes()[1];
            Image.Plane vPlane = image.getPlanes()[2];

            ByteBuffer yBuffer = yPlane.getBuffer();
            ByteBuffer uBuffer = uPlane.getBuffer();
            ByteBuffer vBuffer = vPlane.getBuffer();

            int yRowStride = yPlane.getRowStride();
            int uvRowStride = uPlane.getRowStride();
            int uvPixelStride = uPlane.getPixelStride();

            byte[] yBytes = new byte[yBuffer.remaining()];
            byte[] uBytes = new byte[uBuffer.remaining()];
            byte[] vBytes = new byte[vBuffer.remaining()];

            yBuffer.get(yBytes);
            uBuffer.get(uBytes);
            vBuffer.get(vBytes);

            int shiftX = 0;
            int shiftY = 0;

            if (alignmentEnabled) {
                byte[] currentThumbLuma = createThumbLuma(yBytes, yRowStride, width, height);
                if (refThumbLuma == null) {
                    refThumbLuma = currentThumbLuma;
                } else {
                    int[] shift = estimateShift(refThumbLuma, currentThumbLuma);
                    shiftX = shift[0] * (width / THUMB_SIZE);
                    shiftY = shift[1] * (height / THUMB_SIZE);
                }
            }

            boolean isFirstFrame = (frameCount == 0);

            for (int j = 0; j < height; j++) {
                int srcY = j - shiftY;
                if (srcY < 0 || srcY >= height) continue;

                int yOffset = srcY * yRowStride;
                int uvOffset = (srcY / 2) * uvRowStride;

                for (int i = 0; i < width; i++) {
                    int srcX = i - shiftX;
                    if (srcX < 0 || srcX >= width) continue;

                    int pixelIndex = j * width + i;

                    int yVal = yBytes[yOffset + srcX] & 0xFF;
                    int uIndex = uvOffset + (srcX / 2) * uvPixelStride;
                    int vIndex = uvOffset + (srcX / 2) * uvPixelStride;

                    int uVal = (uBytes[uIndex] & 0xFF) - 128;
                    int vVal = (vBytes[vIndex] & 0xFF) - 128;

                    // YUV to RGB Conversion
                    int r = Math.min(255, Math.max(0, (int) (yVal + 1.370705f * vVal)));
                    int g = Math.min(255, Math.max(0, (int) (yVal - 0.337633f * uVal - 0.698001f * vVal)));
                    int b = Math.min(255, Math.max(0, (int) (yVal + 1.732446f * uVal)));

                    if (mode == StackingMode.LIGHT_TRAILS) {
                        if (r > maxR[pixelIndex]) maxR[pixelIndex] = r;
                        if (g > maxG[pixelIndex]) maxG[pixelIndex] = g;
                        if (b > maxB[pixelIndex]) maxB[pixelIndex] = b;
                    } else if (mode == StackingMode.HDR_MERGE) {
                        float weight = 1.0f - (Math.abs(yVal - 128.0f) / 128.0f);
                        if (weight < 0.05f) weight = 0.05f;
                        sumR[pixelIndex] += (int) (r * weight);
                        sumG[pixelIndex] += (int) (g * weight);
                        sumB[pixelIndex] += (int) (b * weight);
                        weightSum[pixelIndex] += weight;
                    } else if (mode == StackingMode.MOTION_DEGHOSTING) {
                        if (isFirstFrame) {
                            refLuma[pixelIndex] = (byte) yVal;
                            sumR[pixelIndex] += r;
                            sumG[pixelIndex] += g;
                            sumB[pixelIndex] += b;
                        } else {
                            int refY = refLuma[pixelIndex] & 0xFF;
                            int diff = Math.abs(yVal - refY);
                            if (diff < 40) {
                                sumR[pixelIndex] += r;
                                sumG[pixelIndex] += g;
                                sumB[pixelIndex] += b;
                            } else {
                                sumR[pixelIndex] += (int) (r * 0.1f + (sumR[pixelIndex] / (float) frameCount) * 0.9f);
                                sumG[pixelIndex] += (int) (g * 0.1f + (sumG[pixelIndex] / (float) frameCount) * 0.9f);
                                sumB[pixelIndex] += (int) (b * 0.1f + (sumB[pixelIndex] / (float) frameCount) * 0.9f);
                            }
                        }
                    } else { // SMOOTH_AVERAGE & NIGHT_STACK
                        sumR[pixelIndex] += r;
                        sumG[pixelIndex] += g;
                        sumB[pixelIndex] += b;
                    }
                }
            }

            frameCount++;
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame in Stacking Engine", e);
        }
    }

    private byte[] createThumbLuma(byte[] yBytes, int yRowStride, int width, int height) {
        byte[] thumb = new byte[THUMB_SIZE * THUMB_SIZE];
        int xRatio = width / THUMB_SIZE;
        int yRatio = height / THUMB_SIZE;

        for (int ty = 0; ty < THUMB_SIZE; ty++) {
            int sy = ty * yRatio;
            int yOffset = sy * yRowStride;
            for (int tx = 0; tx < THUMB_SIZE; tx++) {
                int sx = tx * xRatio;
                thumb[ty * THUMB_SIZE + tx] = yBytes[yOffset + sx];
            }
        }
        return thumb;
    }

    private int[] estimateShift(byte[] ref, byte[] current) {
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

    public synchronized int getFrameCount() {
        return frameCount;
    }

    public synchronized byte[] getFinalJpegBytes(int quality) {
        if (frameCount == 0) return null;

        int[] pixels = new int[width * height];
        int numPixels = width * height;

        if (mode == StackingMode.LIGHT_TRAILS) {
            for (int i = 0; i < numPixels; i++) {
                int r = maxR[i];
                int g = maxG[i];
                int b = maxB[i];
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (mode == StackingMode.HDR_MERGE) {
            for (int i = 0; i < numPixels; i++) {
                float w = weightSum[i] > 0 ? weightSum[i] : 1.0f;
                int r = Math.min(255, Math.max(0, (int) (sumR[i] / w)));
                int g = Math.min(255, Math.max(0, (int) (sumG[i] / w)));
                int b = Math.min(255, Math.max(0, (int) (sumB[i] / w)));
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            for (int i = 0; i < numPixels; i++) {
                int r = Math.min(255, sumR[i] / frameCount);
                int g = Math.min(255, sumG[i] / frameCount);
                int b = Math.min(255, sumB[i] / frameCount);
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, Math.min(100, Math.max(50, quality)), baos);
        bitmap.recycle();

        return baos.toByteArray();
    }
}
