package com.my.camerainfo;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContainerGalleryDialog {

    public interface OnProcessCallback {
        void onProcessContainers(List<File> selectedContainers, FrameStackingEngine.StackingMode mode, boolean enableAlignment);
    }

    public static void showContainerGallery(Context context, CaptureContainerManager containerManager, OnProcessCallback callback) {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_container_gallery, null);
        ListView lvContainers = dialogView.findViewById(R.id.lv_containers);
        Button btnSelectAll = dialogView.findViewById(R.id.btn_select_all);
        Button btnProcessSelected = dialogView.findViewById(R.id.btn_process_selected);
        Button btnDeleteSelected = dialogView.findViewById(R.id.btn_delete_selected);
        Button btnClose = dialogView.findViewById(R.id.btn_close_gallery);

        List<File> containers = containerManager.getAllSessionContainers();
        Set<File> selectedContainers = new HashSet<>();

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        ContainerAdapter adapter = new ContainerAdapter(context, containers, selectedContainers, containerManager, () -> {
            List<File> updated = containerManager.getAllSessionContainers();
            containers.clear();
            containers.addAll(updated);
        }, callback, dialog);

        lvContainers.setAdapter(adapter);

        btnSelectAll.setOnClickListener(v -> {
            if (selectedContainers.size() == containers.size()) {
                selectedContainers.clear();
            } else {
                selectedContainers.addAll(containers);
            }
            adapter.notifyDataSetChanged();
        });

        btnProcessSelected.setOnClickListener(v -> {
            if (selectedContainers.isEmpty()) {
                Toast.makeText(context, "Select at least one container to process", Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            showProcessPreferencesModal(context, new ArrayList<>(selectedContainers), callback);
        });

        btnDeleteSelected.setOnClickListener(v -> {
            if (selectedContainers.isEmpty()) {
                Toast.makeText(context, "Select at least one container to delete", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle("Delete Selected Containers")
                    .setMessage("Are you sure you want to delete " + selectedContainers.size() + " selected container(s)?")
                    .setPositiveButton("Delete All", (d, w) -> {
                        int count = selectedContainers.size();
                        for (File sessionDir : new ArrayList<>(selectedContainers)) {
                            File[] files = sessionDir.listFiles();
                            if (files != null) {
                                for (File f : files) {
                                    f.delete();
                                }
                            }
                            sessionDir.delete();
                        }
                        selectedContainers.clear();
                        List<File> updated = containerManager.getAllSessionContainers();
                        containers.clear();
                        containers.addAll(updated);
                        adapter.notifyDataSetChanged();
                        Toast.makeText(context, "Deleted " + count + " container(s)", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    public static void showProcessPreferencesModal(Context context, List<File> targets, OnProcessCallback callback) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_process_preferences, null);
        Spinner spMode = view.findViewById(R.id.sp_stacking_mode);
        CheckBox cbAlignment = view.findViewById(R.id.cb_enable_alignment);
        Button btnConfirm = view.findViewById(R.id.btn_confirm_process);
        Button btnCancel = view.findViewById(R.id.btn_cancel_process);

        String[] modes = {"SMOOTH_AVERAGE (Motion Blur / Silk)", "LIGHT_TRAILS (Star Trails / Lights)", "NIGHT_STACK (Low Light Noise Reduction)", "HDR_MERGE (Multi-Exposure)"};
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, modes);
        spMode.setAdapter(spinnerAdapter);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(view)
                .create();

        btnConfirm.setOnClickListener(v -> {
            int sel = spMode.getSelectedItemPosition();
            FrameStackingEngine.StackingMode mode = FrameStackingEngine.StackingMode.SMOOTH_AVERAGE;
            if (sel == 1) mode = FrameStackingEngine.StackingMode.LIGHT_TRAILS;
            else if (sel == 2) mode = FrameStackingEngine.StackingMode.NIGHT_STACK;
            else if (sel == 3) mode = FrameStackingEngine.StackingMode.HDR_MERGE;

            boolean alignment = cbAlignment.isChecked();
            dialog.dismiss();
            if (callback != null) {
                callback.onProcessContainers(targets, mode, alignment);
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    static class ContainerAdapter extends BaseAdapter {
        private final Context context;
        private final List<File> containers;
        private final Set<File> selected;
        private final CaptureContainerManager containerManager;
        private final Runnable onRefresh;
        private final OnProcessCallback processCallback;
        private final AlertDialog parentDialog;

        ContainerAdapter(Context context, List<File> containers, Set<File> selected, CaptureContainerManager containerManager, Runnable onRefresh, OnProcessCallback processCallback, AlertDialog parentDialog) {
            this.context = context;
            this.containers = containers;
            this.selected = selected;
            this.containerManager = containerManager;
            this.onRefresh = onRefresh;
            this.processCallback = processCallback;
            this.parentDialog = parentDialog;
        }

        @Override
        public int getCount() {
            return containers.size();
        }

        @Override
        public Object getItem(int position) {
            return containers.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            File file = containers.get(position);
            TextView text1 = view.findViewById(android.R.id.text1);
            TextView text2 = view.findViewById(android.R.id.text2);

            text1.setTextColor(Color.WHITE);
            text2.setTextColor(0xFFCCCCCC);

            boolean isSel = selected.contains(file);
            File[] frameFiles = file.listFiles((dir, name) -> name.startsWith("frame_"));
            int frameCount = frameFiles != null ? frameFiles.length : 0;

            text1.setText((isSel ? "[x] " : "[  ] ") + file.getName());
            text2.setText("Frames: " + frameCount + " | Tap: select | Long-press: manage");

            view.setOnClickListener(v -> {
                if (selected.contains(file)) {
                    selected.remove(file);
                } else {
                    selected.add(file);
                }
                notifyDataSetChanged();
            });

            view.setOnLongClickListener(v -> {
                showContainerOptionsDialog(file);
                return true;
            });

            return view;
        }

        private void showContainerOptionsDialog(File sessionDir) {
            String[] options = {"Browse & Manage Frames", "Rename Session Container", "Export Container as ZIP", "Delete Session Container"};
            new AlertDialog.Builder(context)
                    .setTitle(sessionDir.getName())
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                showFrameBrowser(sessionDir);
                                break;
                            case 1:
                                renameContainer(sessionDir);
                                break;
                            case 2:
                                exportZip(sessionDir);
                                break;
                            case 3:
                                deleteContainer(sessionDir);
                                break;
                        }
                    })
                    .show();
        }

        private void showFrameBrowser(File sessionDir) {
            View frameView = LayoutInflater.from(context).inflate(R.layout.dialog_frame_browser, null);
            TextView tvTitle = frameView.findViewById(R.id.tv_container_title);
            GridView gvFrames = frameView.findViewById(R.id.gv_frames);
            Button btnDelete = frameView.findViewById(R.id.btn_delete_selected_frames);
            Button btnProcess = frameView.findViewById(R.id.btn_reprocess_container);

            tvTitle.setText(sessionDir.getName());

            File[] frameFilesArr = sessionDir.listFiles((dir, name) -> name.startsWith("frame_"));
            List<File> frameFiles = new ArrayList<>();
            if (frameFilesArr != null) {
                Arrays.sort(frameFilesArr, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                frameFiles.addAll(Arrays.asList(frameFilesArr));
            }

            Set<File> selectedFrames = new HashSet<>();
            BaseAdapter frameAdapter = new BaseAdapter() {
                @Override
                public int getCount() {
                    return frameFiles.size();
                }

                @Override
                public Object getItem(int position) {
                    if (position >= 0 && position < frameFiles.size()) {
                        return frameFiles.get(position);
                    }
                    return null;
                }

                @Override
                public long getItemId(int position) {
                    return position;
                }

                @Override
                public View getView(int pos, View convert, ViewGroup parentGrid) {
                    TextView tv;
                    if (convert instanceof TextView) {
                        tv = (TextView) convert;
                    } else {
                        tv = new TextView(context);
                        tv.setPadding(12, 12, 12, 12);
                    }
                    if (pos < 0 || pos >= frameFiles.size()) return tv;
                    File f = frameFiles.get(pos);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(10);
                    boolean isSel = selectedFrames.contains(f);
                    tv.setText((isSel ? "[X] " : "") + f.getName());
                    tv.setBackgroundColor(isSel ? 0xFF880000 : 0xFF333333);
                    return tv;
                }
            };
            gvFrames.setAdapter(frameAdapter);

            gvFrames.setOnItemClickListener((parent, v, pos, id) -> {
                File f = frameFiles.get(pos);
                if (selectedFrames.contains(f)) {
                    selectedFrames.remove(f);
                } else {
                    selectedFrames.add(f);
                }
                frameAdapter.notifyDataSetChanged();
            });

            AlertDialog browserDialog = new AlertDialog.Builder(context)
                    .setView(frameView)
                    .create();

            btnDelete.setOnClickListener(v -> {
                if (selectedFrames.isEmpty()) {
                    Toast.makeText(context, "No frames selected to delete", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (File f : selectedFrames) {
                    if (f.exists()) {
                        f.delete();
                    }
                }
                Toast.makeText(context, "Deleted " + selectedFrames.size() + " frames", Toast.LENGTH_SHORT).show();
                browserDialog.dismiss();
                if (onRefresh != null) {
                    onRefresh.run();
                }
                notifyDataSetChanged();
            });

            btnProcess.setOnClickListener(v -> {
                browserDialog.dismiss();
                if (parentDialog != null) {
                    parentDialog.dismiss();
                }
                showProcessPreferencesModal(context, Collections.singletonList(sessionDir), processCallback);
            });

            browserDialog.show();
        }

        private void renameContainer(File sessionDir) {
            EditText input = new EditText(context);
            input.setText(sessionDir.getName());
            new AlertDialog.Builder(context)
                    .setTitle("Rename Session")
                    .setView(input)
                    .setPositiveButton("Rename", (d, w) -> {
                        String newName = input.getText().toString().trim();
                        if (!newName.isEmpty()) {
                            File target = new File(sessionDir.getParentFile(), newName);
                            boolean renamed = sessionDir.renameTo(target);
                            if (renamed) {
                                Toast.makeText(context, "Renamed to " + newName, Toast.LENGTH_SHORT).show();
                                if (onRefresh != null) {
                                    onRefresh.run();
                                }
                                notifyDataSetChanged();
                            } else {
                                Toast.makeText(context, "Failed to rename folder", Toast.LENGTH_SHORT).show();
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private void exportZip(File sessionDir) {
            try {
                File zipFile = containerManager.exportContainerToZip(sessionDir, context);
                if (zipFile != null) {
                    Toast.makeText(context, "Exported: RawExports/" + sessionDir.getName() + "/images", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, "Failed to export ZIP", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Toast.makeText(context, "ZIP Export Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        private void deleteContainer(File sessionDir) {
            new AlertDialog.Builder(context)
                    .setTitle("Delete Container")
                    .setMessage("Are you sure you want to delete " + sessionDir.getName() + " and all its raw frames?")
                    .setPositiveButton("Delete", (d, w) -> {
                        File[] files = sessionDir.listFiles();
                        if (files != null) {
                            for (File f : files) {
                                f.delete();
                            }
                        }
                        sessionDir.delete();
                        Toast.makeText(context, "Container deleted", Toast.LENGTH_SHORT).show();
                        if (onRefresh != null) {
                            onRefresh.run();
                        }
                        notifyDataSetChanged();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }
}
