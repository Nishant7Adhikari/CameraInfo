package com.my.camerainfo;

public class CameraControlState {

    public enum CaptureMode {
        SINGLE_PHOTO,
        LONG_EXPOSURE,
        MULTI_FRAME_BURST
    }

    public enum ImageOutputFormat {
        JPEG,
        RAW_DNG,
        YUV_ACCUMULATOR
    }

    public CaptureMode captureMode = CaptureMode.SINGLE_PHOTO;
    public ImageOutputFormat outputFormat = ImageOutputFormat.JPEG;

    public boolean isManualAe = false;
    public boolean isManualAf = false;
    public boolean isManualWb = false;

    public boolean aeLocked = false;
    public boolean awbLocked = false;

    public long shutterSpeedNs = 400_000_000L; // Default 400ms max per frame
    public int iso = 400;
    public float focusDistanceDiopters = 0.0f; // 0 = infinity
    public int wbKelvin = 5500;
    public int exposureCompensation = 0;

    public int targetDurationSec = 30; // 0 for BULB
    public int targetFrameCount = 0; // 0 for unlimited
    public long frameIntervalMs = 0; // 0 for max burst rate

    public LongExposureAccumulator.Mode effectMode = LongExposureAccumulator.Mode.SMOOTH_AVERAGE;
    public FrameStackingEngine.StackingMode stackingMode = FrameStackingEngine.StackingMode.SMOOTH_AVERAGE;
    public boolean alignmentEnabled = true;
}
