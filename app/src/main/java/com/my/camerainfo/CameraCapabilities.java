package com.my.camerainfo;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Rational;
import android.util.Size;

import java.util.Locale;

public class CameraCapabilities {

    public final String cameraId;
    public final int hardwareLevel; // LEVEL_3, FULL, LIMITED, LEGACY
    public final boolean supportsRaw;
    public final boolean supportsManualSensor; // Exposure & ISO manual control
    public final boolean supportsManualPostProcessing; // WB, tone curve
    public final boolean supportsAeLock;
    public final boolean supportsAwbLock;

    public final Range<Long> exposureTimeRange; // in nanoseconds
    public final Range<Integer> isoRange;
    public final Float minFocusDistance; // 0 means fixed focus, >0 in diopters
    public final Range<Integer> aeCompensationRange;
    public final float aeCompensationStep;

    public final Size[] rawSizes;
    public final Size[] jpegSizes;
    public final Size[] yuvSizes;

    public CameraCapabilities(String cameraId, CameraCharacteristics characteristics) {
        this.cameraId = cameraId;

        Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        this.hardwareLevel = hwLevel != null ? hwLevel : CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;

        int[] caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasRaw = false;
        boolean hasManualSensor = false;
        boolean hasManualPost = false;
        if (caps != null) {
            for (int cap : caps) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    hasRaw = true;
                }
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                    hasManualSensor = true;
                }
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) {
                    hasManualPost = true;
                }
            }
        }
        this.supportsRaw = hasRaw;
        this.supportsManualSensor = hasManualSensor;
        this.supportsManualPostProcessing = hasManualPost;

        Boolean aeLock = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE);
        this.supportsAeLock = aeLock != null && aeLock;

        Boolean awbLock = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE);
        this.supportsAwbLock = awbLock != null && awbLock;

        this.exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        this.isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

        Float minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        this.minFocusDistance = minFocus != null ? minFocus : 0.0f;

        this.aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational compStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        this.aeCompensationStep = compStep != null ? compStep.floatValue() : 1.0f;

        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] raw = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            this.rawSizes = raw != null ? raw : new Size[0];

            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            this.jpegSizes = jpeg != null ? jpeg : new Size[0];

            Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
            this.yuvSizes = yuv != null ? yuv : new Size[0];
        } else {
            this.rawSizes = new Size[0];
            this.jpegSizes = new Size[0];
            this.yuvSizes = new Size[0];
        }
    }

    public String getHardwareLevelName() {
        switch (hardwareLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return "UNKNOWN (" + hardwareLevel + ")";
        }
    }

    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Camera ID: ").append(cameraId).append(" [").append(getHardwareLevelName()).append("]\n");
        sb.append("RAW Support: ").append(supportsRaw ? "YES" : "NO").append(" | ");
        sb.append("Manual Controls: ").append(supportsManualSensor ? "YES" : "NO").append("\n");

        if (exposureTimeRange != null) {
            long maxMs = exposureTimeRange.getUpper() / 1_000_000;
            sb.append("Shutter: ").append(exposureTimeRange.getLower() / 1000).append("µs - ").append(maxMs).append("ms\n");
        }
        if (isoRange != null) {
            sb.append("ISO Range: ").append(isoRange.getLower()).append(" - ").append(isoRange.getUpper()).append("\n");
        }
        if (minFocusDistance > 0) {
            sb.append("Focus Range: Infinity to ").append(String.format(Locale.US, "%.2fm", 1.0f / minFocusDistance)).append("\n");
        } else {
            sb.append("Focus: Fixed Focus\n");
        }
        return sb.toString();
    }
}
