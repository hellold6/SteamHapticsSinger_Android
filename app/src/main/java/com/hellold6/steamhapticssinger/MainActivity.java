package com.hellold6.steamhapticssinger;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_MIDI_FILE = 1001;
    private static final String USB_PERMISSION_ACTION = "com.hellold6.steamhapticssinger.USB_PERMISSION";
    private static final int DEFAULT_INTERVAL_USEC = 10_000;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final SteamHapticsPlayer player = new SteamHapticsPlayer();
    private final StringBuilder logBuffer = new StringBuilder();
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) {
                return;
            }
            appendLog("USB permission updated.");
            refreshDeviceStatus();
        }
    };

    private UsbSteamDeviceManager deviceManager;
    private Uri selectedMidiUri;
    private String selectedMidiName = "No MIDI selected";

    private TextView selectedFileView;
    private TextView deviceStatusView;
    private TextView playbackStatusView;
    private TextView noteStatusView;
    private TextView logView;
    private EditText intervalInput;
    private EditText debugInput;
    private CheckBox repeatCheckbox;
    private CheckBox directVelocityCheckbox;
    private CheckBox tritonLimitCheckbox;
    private CheckBox tritonSwapCheckbox;
    private Button playButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceManager = new UsbSteamDeviceManager((UsbManager) getSystemService(Context.USB_SERVICE));
        registerReceiver(usbPermissionReceiver, new IntentFilter(USB_PERMISSION_ACTION));
        setContentView(createContentView());
        refreshDeviceStatus();
        updatePlaybackButtons(false);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbPermissionReceiver);
        player.stop();
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MIDI_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some providers do not grant persistable access; temporary read access is still enough for this session.
        }

        selectedMidiUri = uri;
        selectedMidiName = resolveDisplayName(uri);
        selectedFileView.setText(selectedMidiName);
        appendLog("Selected MIDI file: " + selectedMidiName);
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        root.addView(title("Steam Haptics Singer"));
        root.addView(body("Choose a MIDI file, connect a supported Steam device over USB, and play it directly from Android."));

        root.addView(section("MIDI file"));
        selectedFileView = body(selectedMidiName);
        root.addView(selectedFileView);
        Button chooseFileButton = button("Choose MIDI file", view -> openMidiPicker());
        root.addView(chooseFileButton);

        root.addView(section("Device"));
        deviceStatusView = body("Checking for supported devices...");
        root.addView(deviceStatusView);
        Button refreshButton = button("Refresh device", view -> refreshDeviceStatus());
        root.addView(refreshButton);

        root.addView(section("Playback options"));
        intervalInput = numberInput(String.valueOf(DEFAULT_INTERVAL_USEC), "Sleep interval in microseconds");
        debugInput = numberInput("0", "Debug log level");
        repeatCheckbox = checkbox("Repeat song");
        directVelocityCheckbox = checkbox("Direct velocity to gain (-e)");
        tritonLimitCheckbox = checkbox("Steam Controller (2026): limit to two channels (-t)");
        tritonSwapCheckbox = checkbox("Steam Controller (2026): swap rumble and trackpad channels (-s)");

        root.addView(labeledField("Interval", intervalInput));
        root.addView(labeledField("Debug level", debugInput));
        root.addView(repeatCheckbox);
        root.addView(directVelocityCheckbox);
        root.addView(tritonLimitCheckbox);
        root.addView(tritonSwapCheckbox);

        root.addView(section("Controls"));
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        playButton = button("Play", view -> startPlayback());
        stopButton = button("Stop", view -> stopPlayback());
        buttonRow.addView(playButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttonRow.addView(stopButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttonRow);

        root.addView(section("Status"));
        playbackStatusView = body("Idle");
        noteStatusView = body("LEFT haptic : OFF, RIGHT haptic : OFF");
        root.addView(playbackStatusView);
        root.addView(noteStatusView);

        root.addView(section("Log"));
        logView = body("Ready.");
        root.addView(logView);

        return scrollView;
    }

    private void openMidiPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/midi");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
            "audio/midi",
            "audio/x-midi",
            "application/octet-stream"
        });
        startActivityForResult(intent, REQUEST_MIDI_FILE);
    }

    private void refreshDeviceStatus() {
        UsbSteamDeviceManager.SupportedDevice device = deviceManager.findBestSupportedDevice();
        if (device == null) {
            deviceStatusView.setText("No supported Steam device connected.");
            return;
        }

        String readiness = deviceManager.hasPermission(device) ? "ready" : "permission required";
        deviceStatusView.setText(device.describe() + " - " + readiness);
    }

    private void startPlayback() {
        if (player.isPlaying()) {
            appendLog("Playback is already running.");
            return;
        }
        if (selectedMidiUri == null) {
            appendLog("Choose a MIDI file first.");
            return;
        }

        PlaybackOptions options;
        try {
            options = readOptions();
        } catch (IllegalArgumentException error) {
            appendLog(error.getMessage());
            return;
        }

        UsbSteamDeviceManager.SupportedDevice device = deviceManager.findBestSupportedDevice();
        if (device == null) {
            appendLog("No supported Steam device connected.");
            refreshDeviceStatus();
            return;
        }
        if (!deviceManager.hasPermission(device)) {
            deviceManager.requestPermission(this, device, USB_PERMISSION_ACTION);
            appendLog("Requested USB permission for " + device.describe() + ".");
            refreshDeviceStatus();
            return;
        }

        appendLog("Preparing playback for " + selectedMidiName + " on " + device.describe() + ".");
        playbackStatusView.setText("Preparing playback...");
        updatePlaybackButtons(true);

        backgroundExecutor.execute(() -> {
            try (InputStream inputStream = getContentResolver().openInputStream(selectedMidiUri)) {
                if (inputStream == null) {
                    throw new IllegalStateException("Unable to open the selected MIDI file.");
                }

                PlaybackPlan plan = MidiFileParser.parse(inputStream, selectedMidiName);
                SteamNoteOutput output = deviceManager.open(device);
                player.start(plan, options, output, new UiPlaybackListener());
            } catch (Exception error) {
                runOnUiThread(() -> {
                    appendLog("Unable to start playback: " + error.getMessage());
                    playbackStatusView.setText("Idle");
                    noteStatusView.setText("LEFT haptic : OFF, RIGHT haptic : OFF");
                    updatePlaybackButtons(false);
                });
            }
        });
    }

    private void stopPlayback() {
        player.stop();
        appendLog("Stop requested.");
    }

    private PlaybackOptions readOptions() {
        int intervalUsec = parseInteger(intervalInput.getText().toString(), DEFAULT_INTERVAL_USEC, "Interval");
        int debugLevel = parseInteger(debugInput.getText().toString(), 0, "Debug level");
        return new PlaybackOptions(
            intervalUsec,
            debugLevel,
            repeatCheckbox.isChecked(),
            directVelocityCheckbox.isChecked(),
            tritonLimitCheckbox.isChecked(),
            tritonSwapCheckbox.isChecked()
        );
    }

    private int parseInteger(String value, int fallback, String label) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            if (parsed < 0) {
                throw new IllegalArgumentException(label + " cannot be negative.");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }

    private void updatePlaybackButtons(boolean playing) {
        playButton.setEnabled(!playing);
        stopButton.setEnabled(playing);
    }

    private String resolveDisplayName(Uri uri) {
        Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) {
                        return cursor.getString(column);
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment();
    }

    private void appendLog(String message) {
        if (logBuffer.length() > 8_000) {
            logBuffer.delete(0, 2_000);
        }
        if (logBuffer.length() > 0) {
            logBuffer.append('\n');
        }
        logBuffer.append(message);
        logView.setText(logBuffer.toString());
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(24f);
        return view;
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18f);
        view.setPadding(0, dp(16), 0, dp(8));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        return button;
    }

    private CheckBox checkbox(String text) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(text);
        return checkBox;
    }

    private EditText numberInput(String value, String hint) {
        EditText editText = new EditText(this);
        editText.setText(value);
        editText.setHint(hint);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        return editText;
    }

    private View labeledField(String label, EditText editText) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView textView = new TextView(this);
        textView.setText(label);
        layout.addView(textView);
        layout.addView(editText);
        return layout;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class UiPlaybackListener implements SteamHapticsPlayer.Listener {
        @Override
        public void onPlaybackStarted(String sourceName, SteamControllerType type, int channelCount) {
            runOnUiThread(() -> {
                appendLog("Playback started: " + sourceName + " on " + type.getDisplayName() + ".");
                playbackStatusView.setText("Playing on " + type.getDisplayName());
                noteStatusView.setText(NoteDisplayFormatter.format(new int[] {
                    SteamHapticsCommandEncoder.NOTE_STOP,
                    SteamHapticsCommandEncoder.NOTE_STOP,
                    SteamHapticsCommandEncoder.NOTE_STOP,
                    SteamHapticsCommandEncoder.NOTE_STOP
                }, channelCount));
                updatePlaybackButtons(true);
            });
        }

        @Override
        public void onPlaybackStateChanged(String stateText) {
            runOnUiThread(() -> noteStatusView.setText(stateText));
        }

        @Override
        public void onPlaybackLog(String message) {
            runOnUiThread(() -> appendLog(message));
        }

        @Override
        public void onPlaybackFinished(String message) {
            runOnUiThread(() -> {
                appendLog(message);
                playbackStatusView.setText("Idle");
                updatePlaybackButtons(false);
            });
        }

        @Override
        public void onPlaybackError(String message, Exception error) {
            runOnUiThread(() -> {
                appendLog(message);
                playbackStatusView.setText("Error");
                updatePlaybackButtons(false);
            });
        }
    }
}
