package com.my.camerainfo;

import android.graphics.Bitmap;
import android.media.Image;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class LongExposureAccumulator {

    private static final String TAG = "LongExposureAcc";

    public enum Mode {
        SMOOTH_AVERAGE, // For motion blur, silky water, noise reduction
        LIGHT_TRAILS    // For star trails, car light painting
    }

    private final Mode mode;
    private final int width;
    private final int height;

    private int frameCount = 0;

    // Accumulation buffers for R, G, B channels
    private long[] sumR;
    private long[] sumG;
    private long[] sumB;

    private int[] maxR;
    private int[] maxG;
    private int[] maxB;

    public LongExposureAccumulator(Mode mode, int width, int height) {
        this.mode = mode;
        this.width = width;
        this.height = height;
        int numPixels = width * height;

        if (mode == Mode.SMOOTH_AVERAGE) {
            sumR = new long[numPixels];
            sumG = new long[numPixels];
            sumB = new long[numPixels];
        } else {
            maxR = new int[numPixels];
            maxG = new int[numPixels];
            maxB = new int[numPixels];
        }
    }

    public synchronized void addFrame(Image image) {
        if (image == null) return;

        try {
            // Handle YUV_420_888
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

            int pixelIndex = 0;
            for (int j = 0; j < height; j++) {
                int yOffset = j * yRowStride;
                int uvOffset = (j / 2) * uvRowStride;

                for (int i = 0; i < width; i++) {
                    int yVal = yBytes[yOffset + i] & 0xFF;
                    int uIndex = uvOffset + (i / 2) * uvPixelStride;
                    int vIndex = uvOffset + (i / 2) * uvPixelStride;

                    int uVal = (uBytes[uIndex] & 0xFF) - 128;
                    int vVal = (vBytes[vIndex] & 0xFF) - 128;

                    // YUV to RGB Conversion
                    int r = Math.min(255, Math.max(0, (int) (yVal + 1.370705f * vVal)));
                    int g = Math.min(255, Math.max(0, (int) (yVal - 0.337633f * uVal - 0.698001f * vVal)));
                    int b = Math.min(255, Math.max(0, (int) (yVal + 1.732446f * uVal)));

                    if (mode == Mode.SMOOTH_AVERAGE) {
                        sumR[pixelIndex] += r;
                        sumG[pixelIndex] += g;
                        sumB[pixelIndex] += b;
                    } else {
                        if (r > maxR[pixelIndex]) maxR[pixelIndex] = r;
                        if (g > maxG[pixelIndex]) maxG[pixelIndex] = g;
                        if (b > maxB[pixelIndex]) maxB[pixelIndex] = b;
                    }

                    pixelIndex++;
                }
            }

            frameCount++;
        } catch (Exception e) {
            Log.e(TAG, "Error adding frame to accumulator", e);
        }
    }

    public synchronized int getFrameCount() {
        return frameCount;
    }

    public synchronized byte[] getFinalJpegBytes() {
        if (frameCount == 0) return null;

        int[] pixels = new int[width * height];
        int numPixels = width * height;

        if (mode == Mode.SMOOTH_AVERAGE) {
            for (int i = 0; i < numPixels; i++) {
                int r = (int) (sumR[i] / frameCount);
                int g = (int) (sumG[i] / frameCount);
                int b = (int) (sumB[i] / frameCount);
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            for (int i = 0; i < numPixels; i++) {
                int r = maxR[i];
                int g = maxG[i];
                int b = maxB[i];
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        bitmap.recycle();

        return baos.toByteArray();
    }
}
