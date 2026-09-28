package com.my.camerainfo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "CameraInfoMain";
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private AutoFitTextureView textureView;
    private TextView textStatus;
    private Button captureButton;

    // Navigation Tabs
    private Button btnTabNormal;
    private Button btnTabPro;
    private Button btnSwitchCamera;

    private LinearLayout panelNormalMode;
    private LinearLayout panelProMode;

    // Pro Controls Toggles & Sliders
    private Button btnAeMode;
    private Button btnAfMode;
    private Button btnAeLock;
    private Button btnAwbLock;
    private Button btnAlignment;

    private LinearLayout slidersContainer;

    private TextView tvShutterLabel;
    private SeekBar sbShutter;

    private TextView tvIsoLabel;
    private SeekBar sbIso;

    private TextView tvFocusLabel;
    private SeekBar sbFocus;

    // Computational Settings Buttons
    private Button btnDurSingle, btnDur5s, btnDur10s, btnDur15s, btnDur30s, btnDurBulb;
    private Button btnEffectSmooth, btnEffectTrails, btnEffectNight, btnEffectHdr;
    private Button btnModeInfo;
    private Button btnReprocess;
    private ProgressBar progressBarExposure;

    private String cameraId;
    private int lensFacing = CameraCharacteristics.LENS_FACING_BACK;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;

    private Size previewSize;
    private Size captureSize;

    private ImageReader imageReader; // Still photo reader
    private ImageReader yuvImageReader; // Long exposure continuous reader

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private int sensorOrientation;

    // Architecture & Session Infrastructure
    private CameraCapabilities capabilities;
    private CaptureContainerManager containerManager;
    private CameraControlState cameraControlState = new CameraControlState();

    private File activeSessionDir;
    private CaptureManifest activeManifest;

    private boolean isLongExposureActive = false;
    private long captureStartTimeMs = 0;

    private FrameStackingEngine stackingEngine;
    private FrameSaveWorker frameSaveWorker;

    private enum State {
        PREVIEW,
        WAITING_LOCK,
        WAITING_PRECAPTURE,
        WAITING_NON_PRECAPTURE,
        PICTURE_TAKEN
    }
    private State state = State.PREVIEW;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
                }
            };

    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            finish();
        }
    };

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult result) {
                    switch (state) {
                        case PREVIEW:
                            break;
                        case WAITING_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureStillPicture();
                            } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                                    CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    state = State.PICTURE_TAKEN;
                                    captureStillPicture();
                                } else {
                                    runPrecaptureSequence();
                                }
                            }
                            break;
                        }
                        case WAITING_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                                    aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                state = State.WAITING_NON_PRECAPTURE;
                            }
                            break;
                        }
                        case WAITING_NON_PRECAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                state = State.PICTURE_TAKEN;
                                captureStillPicture();
                            }
                            break;
                        }
                        case PICTURE_TAKEN:
                            break;
                    }
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    process(result);
                }
            };

    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            reader -> backgroundHandler.post(() -> {
                Image image = reader.acquireNextImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    image.close();
                    saveImageToMediaStore(bytes);
                }
            });

    private final ImageReader.OnImageAvailableListener onYuvImageAvailableListener =
            reader -> {
                Image image = reader.acquireNextImage();
                if (image != null) {
                    if (isLongExposureActive && stackingEngine != null) {
                        byte[] nv21Bytes = yuv420888ToNv21(image);
                        if (frameSaveWorker != null) {
                            frameSaveWorker.saveYuvFrameAsync(nv21Bytes);
                        }
                        stackingEngine.processYuvFrame(image);
                        int count = stackingEngine.getFrameCount();
                        float elapsedSec = (System.currentTimeMillis() - captureStartTimeMs) / 1000.0f;

                        if (activeManifest != null && activeSessionDir != null) {
                            activeManifest.capturedFramesCount = count;
                            containerManager.saveManifest(activeSessionDir, activeManifest);
                        }

                        String durationLabel = cameraControlState.targetDurationSec > 0 ? cameraControlState.targetDurationSec + "s" : "BULB";
                        updateStatus(String.format(Locale.US, "Exposing: %.1fs / %s (%d frames)", elapsedSec, durationLabel, count));

                        if (cameraControlState.targetDurationSec > 0) {
                            int pct = Math.min(100, (int) ((elapsedSec / cameraControlState.targetDurationSec) * 100));
                            runOnUiThread(() -> {
                                if (progressBarExposure != null) {
                                    progressBarExposure.setVisibility(View.VISIBLE);
                                    progressBarExposure.setProgress(pct);
                                }
                            });
                        }

                        if (cameraControlState.targetDurationSec > 0 && elapsedSec >= cameraControlState.targetDurationSec) {
                            runOnUiThread(this::stopAndSaveLongExposure);
                        }
                    }
                    image.close();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        containerManager = new CaptureContainerManager(this);

        textureView = findViewById(R.id.texture_view);
        textStatus = findViewById(R.id.text_status);
        captureButton = findViewById(R.id.btn_capture);

        btnTabNormal = findViewById(R.id.btn_tab_normal);
        btnTabPro = findViewById(R.id.btn_tab_pro);
        btnSwitchCamera = findViewById(R.id.btn_switch_camera);

        panelNormalMode = findViewById(R.id.panel_normal_mode);
        panelProMode = findViewById(R.id.panel_pro_mode);

        btnAeMode = findViewById(R.id.btn_ae_mode);
        btnAfMode = findViewById(R.id.btn_af_mode);
        btnAeLock = findViewById(R.id.btn_ae_lock);
        btnAwbLock = findViewById(R.id.btn_awb_lock);
        btnAlignment = findViewById(R.id.btn_alignment);

        slidersContainer = findViewById(R.id.sliders_container);

        tvShutterLabel = findViewById(R.id.tv_shutter_label);
        sbShutter = findViewById(R.id.sb_shutter);

        tvIsoLabel = findViewById(R.id.tv_iso_label);
        sbIso = findViewById(R.id.sb_iso);

        tvFocusLabel = findViewById(R.id.tv_focus_label);
        sbFocus = findViewById(R.id.sb_focus);

        btnDurSingle = findViewById(R.id.btn_dur_single);
        btnDur5s = findViewById(R.id.btn_dur_5s);
        btnDur10s = findViewById(R.id.btn_dur_10s);
        btnDur15s = findViewById(R.id.btn_dur_15s);
        btnDur30s = findViewById(R.id.btn_dur_30s);
        btnDurBulb = findViewById(R.id.btn_dur_bulb);

        btnEffectSmooth = findViewById(R.id.btn_effect_smooth);
        btnEffectTrails = findViewById(R.id.btn_effect_trails);
        btnEffectNight = findViewById(R.id.btn_effect_night);
        btnEffectHdr = findViewById(R.id.btn_effect_hdr);

        btnModeInfo = findViewById(R.id.btn_mode_info);
        btnReprocess = findViewById(R.id.btn_reprocess);
        progressBarExposure = findViewById(R.id.progress_bar_exposure);

        setupTabs();
        setupProControlsUI();
        setupLongExposureUI();

        captureButton.setOnClickListener(v -> handleCaptureButtonClick());
    }

    private void setupTabs() {
        btnTabNormal.setOnClickListener(v -> {
            if (isLongExposureActive) return;
            btnTabNormal.setBackgroundResource(R.drawable.bg_pill_active);
            btnTabPro.setBackgroundResource(R.drawable.bg_pill_inactive);
            panelNormalMode.setVisibility(View.VISIBLE);
            panelProMode.setVisibility(View.GONE);
            cameraControlState.targetDurationSec = 0;
            updatePreviewSession();
        });

        btnTabPro.setOnClickListener(v -> {
            if (isLongExposureActive) return;
            btnTabPro.setBackgroundResource(R.drawable.bg_pill_active);
            btnTabNormal.setBackgroundResource(R.drawable.bg_pill_inactive);
            panelProMode.setVisibility(View.VISIBLE);
            panelNormalMode.setVisibility(View.GONE);
            updatePreviewSession();
        });

        btnSwitchCamera.setOnClickListener(v -> switchCamera());

        btnModeInfo.setOnClickListener(v -> showModeInfoDialog());

        btnReprocess.setOnClickListener(v -> {
            if (isLongExposureActive) return;
            ContainerGalleryDialog.showContainerGallery(this, containerManager, (targets, mode, enableAlignment) -> {
                for (File dir : targets) {
                    PostProcessingEngine.reprocessSessionContainerAsync(dir, mode, enableAlignment, new PostProcessingEngine.ProgressCallback() {
                        @Override
                        public void onProgress(int currentFrame, int totalFrames, String statusMsg) {
                            updateStatus(statusMsg);
                        }

                        @Override
                        public void onCompleted(File outputFile) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Processed: " + outputFile.getName(), Toast.LENGTH_LONG).show();
                                updateStatus("Reprocessed session into: " + outputFile.getName());
                            });
                        }

                        @Override
                        public void onError(String errorMsg) {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Processing Error: " + errorMsg, Toast.LENGTH_SHORT).show());
                        }
                    });
                }
            });
        });
    }

    private void showModeInfoDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_mode_info, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        Button btnClose = dialogView.findViewById(R.id.btn_close_info);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void switchCamera() {
        if (isLongExposureActive) return;
        lensFacing = (lensFacing == CameraCharacteristics.LENS_FACING_BACK) ?
                CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        closeCamera();
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        }
    }

    private void setupLongExposureUI() {
        View.OnClickListener durationListener = v -> {
            if (isLongExposureActive) return;
            resetDurationButtons();
            v.setBackgroundResource(R.drawable.bg_pill_active);
            int id = v.getId();
            if (id == R.id.btn_dur_single) cameraControlState.targetDurationSec = 0;
            else if (id == R.id.btn_dur_5s) cameraControlState.targetDurationSec = 5;
            else if (id == R.id.btn_dur_10s) cameraControlState.targetDurationSec = 10;
            else if (id == R.id.btn_dur_15s) cameraControlState.targetDurationSec = 15;
            else if (id == R.id.btn_dur_30s) cameraControlState.targetDurationSec = 30;
            else if (id == R.id.btn_dur_bulb) cameraControlState.targetDurationSec = -1; // Manual BULB
        };

        btnDurSingle.setOnClickListener(durationListener);
        btnDur5s.setOnClickListener(durationListener);
        btnDur10s.setOnClickListener(durationListener);
        btnDur15s.setOnClickListener(durationListener);
        btnDur30s.setOnClickListener(durationListener);
        btnDurBulb.setOnClickListener(durationListener);

        btnEffectSmooth.setOnClickListener(v -> setStackingMode(FrameStackingEngine.StackingMode.SMOOTH_AVERAGE, btnEffectSmooth));
        btnEffectTrails.setOnClickListener(v -> setStackingMode(FrameStackingEngine.StackingMode.LIGHT_TRAILS, btnEffectTrails));
        btnEffectNight.setOnClickListener(v -> setStackingMode(FrameStackingEngine.StackingMode.NIGHT_STACK, btnEffectNight));
        btnEffectHdr.setOnClickListener(v -> setStackingMode(FrameStackingEngine.StackingMode.HDR_MERGE, btnEffectHdr));

        btnAlignment.setOnClickListener(v -> {
            if (isLongExposureActive) return;
            cameraControlState.alignmentEnabled = !cameraControlState.alignmentEnabled;
            btnAlignment.setText(cameraControlState.alignmentEnabled ? "ALIGN: ON" : "ALIGN: OFF");
            btnAlignment.setBackgroundResource(cameraControlState.alignmentEnabled ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
        });
    }

    private void setStackingMode(FrameStackingEngine.StackingMode mode, Button activeBtn) {
        if (isLongExposureActive) return;
        cameraControlState.stackingMode = mode;
        btnEffectSmooth.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnEffectTrails.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnEffectNight.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnEffectHdr.setBackgroundResource(R.drawable.bg_pill_inactive);
        activeBtn.setBackgroundResource(R.drawable.bg_pill_active);
    }

    private void resetDurationButtons() {
        btnDurSingle.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnDur5s.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnDur10s.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnDur15s.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnDur30s.setBackgroundResource(R.drawable.bg_pill_inactive);
        btnDurBulb.setBackgroundResource(R.drawable.bg_pill_inactive);
    }

    private void setupProControlsUI() {
        btnAeMode.setOnClickListener(v -> {
            cameraControlState.isManualAe = !cameraControlState.isManualAe;
            btnAeMode.setText(cameraControlState.isManualAe ? "AE: MANUAL" : "AE: AUTO");
            btnAeMode.setBackgroundResource(cameraControlState.isManualAe ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
            updateSlidersVisibility();
            updatePreviewSession();
        });

        btnAfMode.setOnClickListener(v -> {
            cameraControlState.isManualAf = !cameraControlState.isManualAf;
            btnAfMode.setText(cameraControlState.isManualAf ? "AF: MANUAL" : "AF: AUTO");
            btnAfMode.setBackgroundResource(cameraControlState.isManualAf ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
            updateSlidersVisibility();
            updatePreviewSession();
        });

        btnAeLock.setOnClickListener(v -> {
            if (capabilities == null || !capabilities.supportsAeLock) {
                Toast.makeText(this, "AE Lock not supported on this camera", Toast.LENGTH_SHORT).show();
                return;
            }
            cameraControlState.aeLocked = !cameraControlState.aeLocked;
            btnAeLock.setText(cameraControlState.aeLocked ? "AE LOCK: ON" : "AE LOCK: OFF");
            btnAeLock.setBackgroundResource(cameraControlState.aeLocked ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
            updatePreviewSession();
        });

        btnAwbLock.setOnClickListener(v -> {
            if (capabilities == null || !capabilities.supportsAwbLock) {
                Toast.makeText(this, "AWB Lock not supported on this camera", Toast.LENGTH_SHORT).show();
                return;
            }
            cameraControlState.awbLocked = !cameraControlState.awbLocked;
            btnAwbLock.setText(cameraControlState.awbLocked ? "AWB LOCK: ON" : "AWB LOCK: OFF");
            btnAwbLock.setBackgroundResource(cameraControlState.awbLocked ? R.drawable.bg_pill_active : R.drawable.bg_pill_inactive);
            updatePreviewSession();
        });

        sbShutter.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (capabilities != null && capabilities.exposureTimeRange != null) {
                    cameraControlState.shutterSpeedNs = sliderToExposureTime(progress, capabilities.exposureTimeRange.getLower(), capabilities.exposureTimeRange.getUpper());
                    tvShutterLabel.setText("Shutter: " + formatExposureTime(cameraControlState.shutterSpeedNs));
                    if (cameraControlState.isManualAe && fromUser) {
                        updatePreviewSession();
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbIso.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (capabilities != null && capabilities.isoRange != null) {
                    cameraControlState.iso = sliderToIso(progress, capabilities.isoRange.getLower(), capabilities.isoRange.getUpper());
                    tvIsoLabel.setText("ISO: " + cameraControlState.iso);
                    if (cameraControlState.isManualAe && fromUser) {
                        updatePreviewSession();
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        sbFocus.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (capabilities != null && capabilities.minFocusDistance > 0) {
                    cameraControlState.focusDistanceDiopters = sliderToFocusDistance(progress, capabilities.minFocusDistance);
                    tvFocusLabel.setText("Focus: " + formatFocusDistance(cameraControlState.focusDistanceDiopters));
                    if (cameraControlState.isManualAf && fromUser) {
                        updatePreviewSession();
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void updateSlidersVisibility() {
        if (cameraControlState.isManualAe || cameraControlState.isManualAf) {
            slidersContainer.setVisibility(View.VISIBLE);
            sbShutter.setVisibility(cameraControlState.isManualAe ? View.VISIBLE : View.GONE);
            tvShutterLabel.setVisibility(cameraControlState.isManualAe ? View.VISIBLE : View.GONE);
            sbIso.setVisibility(cameraControlState.isManualAe ? View.VISIBLE : View.GONE);
            tvIsoLabel.setVisibility(cameraControlState.isManualAe ? View.VISIBLE : View.GONE);

            sbFocus.setVisibility(cameraControlState.isManualAf ? View.VISIBLE : View.GONE);
            tvFocusLabel.setVisibility(cameraControlState.isManualAf ? View.VISIBLE : View.GONE);
        } else {
            slidersContainer.setVisibility(View.GONE);
        }
    }

    private void handleCaptureButtonClick() {
        if (cameraControlState.targetDurationSec != 0) {
            if (isLongExposureActive) {
                stopAndSaveLongExposure();
            } else {
                startLongExposure();
            }
        } else {
            takePicture();
        }
    }

    private void startLongExposure() {
        if (previewSize == null || captureSession == null || yuvImageReader == null) return;

        isLongExposureActive = true;
        captureStartTimeMs = System.currentTimeMillis();

        stackingEngine = new FrameStackingEngine(cameraControlState.stackingMode, previewSize.getWidth(), previewSize.getHeight(), cameraControlState.alignmentEnabled);

        activeSessionDir = containerManager.createSessionContainer("LONG_EXP");
        frameSaveWorker = new FrameSaveWorker(activeSessionDir);

        activeManifest = new CaptureManifest();
        activeManifest.sessionId = activeSessionDir.getName();
        activeManifest.cameraId = cameraId;
        activeManifest.captureMode = "COMPUTATIONAL_STACKING";
        activeManifest.targetDurationSec = cameraControlState.targetDurationSec;
        activeManifest.shutterNs = cameraControlState.shutterSpeedNs;
        activeManifest.iso = cameraControlState.iso;
        activeManifest.focusDistanceDiopters = cameraControlState.focusDistanceDiopters;
        activeManifest.effectMode = cameraControlState.stackingMode.name();
        activeManifest.frameWidth = previewSize.getWidth();
        activeManifest.frameHeight = previewSize.getHeight();
        activeManifest.frameFormat = "YUV_420_888";
        activeManifest.status = "RECORDING";

        containerManager.saveManifest(activeSessionDir, activeManifest);

        try {
            previewRequestBuilder.addTarget(yuvImageReader.getSurface());

            if (cameraControlState.isManualAe) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cameraControlState.shutterSpeedNs);
                previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, cameraControlState.iso);
            } else {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                previewRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 400_000_000L); // 0.4s default per frame
                previewRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, cameraControlState.iso);
            }

            if (cameraControlState.isManualAf) {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, cameraControlState.focusDistanceDiopters);
            }

            captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            Toast.makeText(this, "Long Exposure Started...", Toast.LENGTH_SHORT).show();

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting long exposure session", e);
            isLongExposureActive = false;
        }
    }

    private void stopAndSaveLongExposure() {
        if (!isLongExposureActive) return;

        isLongExposureActive = false;

        if (progressBarExposure != null) {
            progressBarExposure.setVisibility(View.GONE);
        }

        updateStatus("Processing Long Exposure Image...");

        backgroundHandler.post(() -> {
            try {
                if (previewRequestBuilder != null && yuvImageReader != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        previewRequestBuilder.removeTarget(yuvImageReader.getSurface());
                    }
                }

                if (frameSaveWorker != null) {
                    frameSaveWorker.shutdown();
                }

                if (stackingEngine != null) {
                    byte[] jpegBytes = stackingEngine.getFinalJpegBytes(95);
                    if (jpegBytes != null) {
                        saveImageToMediaStore(jpegBytes);

                        if (activeManifest != null && activeSessionDir != null) {
                            activeManifest.capturedFramesCount = stackingEngine.getFrameCount();
                            activeManifest.status = "COMPLETED";

                            File outputFile = new File(activeSessionDir, "output_final.jpg");
                            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                                fos.write(jpegBytes);
                            }
                            activeManifest.outputFiles.add("output_final.jpg");
                            containerManager.saveManifest(activeSessionDir, activeManifest);
                        }
                    } else {
                        updateStatus("Long exposure cancelled or no frames captured.");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error stopping long exposure", e);
            } finally {
                runOnUiThread(this::updatePreviewSession);
            }
        });
    }

    private byte[] yuv420888ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] nv21 = new byte[width * height * 3 / 2];

        ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
        ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
        ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] yBytes = new byte[ySize];
        byte[] uBytes = new byte[uSize];
        byte[] vBytes = new byte[vSize];

        yBuffer.get(yBytes);
        uBuffer.get(uBytes);
        vBuffer.get(vBytes);

        System.arraycopy(yBytes, 0, nv21, 0, Math.min(ySize, width * height));

        int nv21Idx = width * height;
        for (int i = 0; i < Math.min(uSize, vSize); i += image.getPlanes()[1].getPixelStride()) {
            if (nv21Idx < nv21.length - 1) {
                nv21[nv21Idx++] = vBytes[i];
                nv21[nv21Idx++] = uBytes[i];
            }
        }
        return nv21;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }

        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        if (isLongExposureActive) {
            stopAndSaveLongExposure();
        }
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while stopping background thread", e);
            }
        }
    }

    private void openCamera(int width, int height) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == lensFacing) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null && manager.getCameraIdList().length > 0) {
                cameraId = manager.getCameraIdList()[0];
            }

            if (cameraId == null) {
                updateStatus("No camera found");
                return;
            }

            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            capabilities = new CameraCapabilities(cameraId, characteristics);

            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                updateStatus("StreamConfigurationMap is null");
                return;
            }

            Integer sensorOrient = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = sensorOrient != null ? sensorOrient : 0;

            if (capabilities.exposureTimeRange != null) {
                cameraControlState.shutterSpeedNs = sliderToExposureTime(sbShutter.getProgress(), capabilities.exposureTimeRange.getLower(), capabilities.exposureTimeRange.getUpper());
                tvShutterLabel.setText("Shutter: " + formatExposureTime(cameraControlState.shutterSpeedNs));
            }

            if (capabilities.isoRange != null) {
                cameraControlState.iso = sliderToIso(sbIso.getProgress(), capabilities.isoRange.getLower(), capabilities.isoRange.getUpper());
                tvIsoLabel.setText("ISO: " + cameraControlState.iso);
            }

            if (capabilities.minFocusDistance > 0) {
                cameraControlState.focusDistanceDiopters = sliderToFocusDistance(sbFocus.getProgress(), capabilities.minFocusDistance);
                tvFocusLabel.setText("Focus: " + formatFocusDistance(cameraControlState.focusDistanceDiopters));
            } else {
                tvFocusLabel.setText("Focus: Fixed / Auto");
                btnAfMode.setEnabled(false);
            }

            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (jpegSizes != null && jpegSizes.length > 0) {
                captureSize = Collections.max(Arrays.asList(jpegSizes), new CompareSizesByArea());
            } else {
                captureSize = new Size(1920, 1080);
            }

            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);

            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            previewSize = chooseOptimalSize(previewSizes, width, height, captureSize);

            yuvImageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 3);
            yuvImageReader.setOnImageAvailableListener(onYuvImageAvailableListener, backgroundHandler);

            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();

            if (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270) {
                textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }

            configureTransform(width, height);

            String statusInfo = "Camera " + cameraId + " [" + capabilities.getHardwareLevelName() + "] (" + captureSize.getWidth() + "x" + captureSize.getHeight() + ")";
            if (capabilities.exposureTimeRange != null && capabilities.isoRange != null) {
                long maxExpMs = capabilities.exposureTimeRange.getUpper() / 1_000_000;
                statusInfo += " | Exp: " + (capabilities.exposureTimeRange.getLower()/1000) + "µs-" + maxExpMs + "ms | ISO: " + capabilities.isoRange.getLower() + "-" + capabilities.isoRange.getUpper();
            }
            updateStatus(statusInfo);

            manager.openCamera(cameraId, deviceStateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
            updateStatus("Camera access error: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (yuvImageReader != null) {
            yuvImageReader.close();
            yuvImageReader = null;
        }
    }

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            applyManualParameters(previewRequestBuilder);

            List<Surface> outputSurfaces = Arrays.asList(surface, imageReader.getSurface(), yuvImageReader.getSurface());

            cameraDevice.createCaptureSession(
                    outputSurfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error setting repeating request", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            updateStatus("Preview session configuration failed");
                        }
                    },
                    backgroundHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating preview session", e);
        }
    }

    private void applyManualParameters(CaptureRequest.Builder builder) {
        if (builder == null) return;

        if (cameraControlState.isManualAe) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            if (cameraControlState.shutterSpeedNs > 0) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cameraControlState.shutterSpeedNs);
            }
            if (cameraControlState.iso > 0) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, cameraControlState.iso);
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (capabilities != null && capabilities.supportsAeLock) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, cameraControlState.aeLocked);
            }
        }

        if (cameraControlState.isManualAf) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, cameraControlState.focusDistanceDiopters);
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }

        if (capabilities != null && capabilities.supportsAwbLock) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, cameraControlState.awbLocked);
        }
    }

    private void updatePreviewSession() {
        if (captureSession == null || previewRequestBuilder == null) return;
        try {
            applyManualParameters(previewRequestBuilder);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error updating preview session", e);
        }
    }

    private void takePicture() {
        if (cameraControlState.isManualAe || cameraControlState.isManualAf) {
            captureStillPicture();
        } else {
            lockFocus();
        }
    }

    private void lockFocus() {
        try {
            if (previewRequestBuilder == null || captureSession == null) return;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            state = State.WAITING_LOCK;
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error locking focus, forcing capture", e);
            captureStillPicture();
        }
    }

    private void runPrecaptureSequence() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            state = State.WAITING_PRECAPTURE;
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error running precapture", e);
        }
    }

    private void captureStillPicture() {
        try {
            if (cameraDevice == null || captureSession == null) return;

            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());

            applyManualParameters(captureBuilder);

            int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
            int jpegRotation = getOrientation(displayRotation);
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation);

            CameraCaptureSession.CaptureCallback stillCaptureCallback =
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            Log.d(TAG, "Still picture captured successfully");
                            if (!cameraControlState.isManualAe && !cameraControlState.isManualAf) {
                                unlockFocus();
                            } else {
                                state = State.PREVIEW;
                                updatePreviewSession();
                            }
                        }
                    };

            captureSession.stopRepeating();
            captureSession.abortCaptures();
            captureSession.capture(captureBuilder.build(), stillCaptureCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error capturing still picture", e);
        }
    }

    private void unlockFocus() {
        try {
            if (previewRequestBuilder == null || captureSession == null) return;
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), captureCallback, backgroundHandler);
            state = State.PREVIEW;
            applyManualParameters(previewRequestBuilder);
            captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error unlocking focus", e);
        }
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    private void saveImageToMediaStore(byte[] bytes) {
        String filename = "IMG_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CameraInfo");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri != null) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out != null) {
                    out.write(bytes);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error writing image bytes", e);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            }

            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "Saved to Gallery: Pictures/CameraInfo/" + filename, Toast.LENGTH_SHORT).show();
                updateStatus("Saved to Gallery: " + filename);
            });
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == previewSize) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= 1920 && option.getHeight() <= 1080 &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (!notBigEnough.isEmpty()) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    private long sliderToExposureTime(int progress, long minNs, long maxNs) {
        double minLog = Math.log(minNs);
        double maxLog = Math.log(maxNs);
        double logValue = minLog + (maxLog - minLog) * (progress / 100.0);
        return Math.round(Math.exp(logValue));
    }

    private String formatExposureTime(long ns) {
        double seconds = ns / 1_000_000_000.0;
        if (seconds >= 0.1) {
            return String.format(Locale.US, "%.2fs (%dms)", seconds, Math.round(ns / 1_000_000.0));
        } else if (seconds > 0) {
            int denominator = (int) Math.round(1.0 / seconds);
            return String.format(Locale.US, "1/%ds", denominator);
        } else {
            return ns + "ns";
        }
    }

    private int sliderToIso(int progress, int minIso, int maxIso) {
        return minIso + (int) Math.round((maxIso - minIso) * (progress / 100.0));
    }

    private float sliderToFocusDistance(int progress, float maxDiopters) {
        return (progress / 100.0f) * maxDiopters;
    }

    private String formatFocusDistance(float diopters) {
        if (diopters <= 0.01f) {
            return "Infinity";
        }
        float meters = 1.0f / diopters;
        return String.format(Locale.US, "%.2fm", meters);
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> {
            if (textStatus != null) {
                textStatus.setText(msg);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable()) {
                    openCamera(textureView.getWidth(), textureView.getHeight());
                }
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
