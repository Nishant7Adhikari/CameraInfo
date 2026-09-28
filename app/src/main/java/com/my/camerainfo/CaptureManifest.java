package com.my.camerainfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CaptureManifest {

    public String sessionId;
    public long timestamp;
    public String cameraId;
    public String captureMode; // SINGLE, LONG_EXPOSURE, MULTI_FRAME_BURST, NIGHT_STACK
    public int targetDurationSec;
    public long shutterNs;
    public int iso;
    public float focusDistanceDiopters;
    public String effectMode; // SMOOTH_AVERAGE, LIGHT_TRAILS
    public int frameWidth;
    public int frameHeight;
    public String frameFormat; // YUV_420_888, RAW_SENSOR, JPEG
    public int capturedFramesCount;
    public int droppedFramesCount;
    public String status; // RECORDING, PENDING_PROCESSING, PROCESSING, COMPLETED, INTERRUPTED
    public List<String> outputFiles = new ArrayList<>();
    public String notes = "";

    public CaptureManifest() {
        this.timestamp = System.currentTimeMillis();
        this.status = "RECORDING";
    }

    public JSONObject toJsonObject() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("sessionId", sessionId);
        json.put("timestamp", timestamp);
        json.put("cameraId", cameraId);
        json.put("captureMode", captureMode);
        json.put("targetDurationSec", targetDurationSec);
        json.put("shutterNs", shutterNs);
        json.put("iso", iso);
        json.put("focusDistanceDiopters", focusDistanceDiopters);
        json.put("effectMode", effectMode);
        json.put("frameWidth", frameWidth);
        json.put("frameHeight", frameHeight);
        json.put("frameFormat", frameFormat);
        json.put("capturedFramesCount", capturedFramesCount);
        json.put("droppedFramesCount", droppedFramesCount);
        json.put("status", status);
        json.put("notes", notes);

        JSONArray outputsArray = new JSONArray();
        if (outputFiles != null) {
            for (String file : outputFiles) {
                outputsArray.put(file);
            }
        }
        json.put("outputFiles", outputsArray);

        return json;
    }

    public static CaptureManifest fromJsonObject(JSONObject json) throws JSONException {
        CaptureManifest manifest = new CaptureManifest();
        manifest.sessionId = json.optString("sessionId", "");
        manifest.timestamp = json.optLong("timestamp", System.currentTimeMillis());
        manifest.cameraId = json.optString("cameraId", "0");
        manifest.captureMode = json.optString("captureMode", "SINGLE");
        manifest.targetDurationSec = json.optInt("targetDurationSec", 0);
        manifest.shutterNs = json.optLong("shutterNs", 0);
        manifest.iso = json.optInt("iso", 0);
        manifest.focusDistanceDiopters = (float) json.optDouble("focusDistanceDiopters", 0.0);
        manifest.effectMode = json.optString("effectMode", "SMOOTH_AVERAGE");
        manifest.frameWidth = json.optInt("frameWidth", 0);
        manifest.frameHeight = json.optInt("frameHeight", 0);
        manifest.frameFormat = json.optString("frameFormat", "YUV_420_888");
        manifest.capturedFramesCount = json.optInt("capturedFramesCount", 0);
        manifest.droppedFramesCount = json.optInt("droppedFramesCount", 0);
        manifest.status = json.optString("status", "RECORDING");
        manifest.notes = json.optString("notes", "");

        JSONArray outputsArray = json.optJSONArray("outputFiles");
        manifest.outputFiles = new ArrayList<>();
        if (outputsArray != null) {
            for (int i = 0; i < outputsArray.length(); i++) {
                manifest.outputFiles.add(outputsArray.getString(i));
            }
        }
        return manifest;
    }

    public void saveToFile(File file) throws IOException, JSONException {
        String jsonStr = toJsonObject().toString(2);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(jsonStr.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static CaptureManifest loadFromFile(File file) throws IOException, JSONException {
        byte[] buffer = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(buffer);
            if (read != buffer.length) {
                throw new IOException("Could not read full manifest file");
            }
        }
        String jsonStr = new String(buffer, StandardCharsets.UTF_8);
        return fromJsonObject(new JSONObject(jsonStr));
    }
}
