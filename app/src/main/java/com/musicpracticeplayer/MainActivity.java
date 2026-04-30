package com.musicpracticeplayer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.Locale;

/**
 * Main activity for the Music Practice Player.
 *
 * <p>Handles the UI, keyboard shortcuts, and delegates audio work to
 * {@link PlaybackController}. The activity keeps the screen on while
 * the app is in the foreground so the user can operate it from a distance.
 */
public class MainActivity extends AppCompatActivity implements PlaybackController.Callbacks {

    private static final String TAG = "MainActivity";

    /** Seek increment in milliseconds for forward/backward buttons and shortcuts. */
    private static final int SEEK_INCREMENT_MS = 10_000;

    // -------------------------------------------------------------------------
    // Views
    // -------------------------------------------------------------------------

    private TextView tvTrackTitle;
    private TextView tvCurrentTime;
    private TextView tvTotalTime;
    private LoopSeekBar seekBar;
    private ImageButton btnPlayPause;
    private ImageButton btnSeekBack;
    private ImageButton btnSeekForward;
    private ImageButton btnReturnToStart;
    private MaterialButton btnSetLoopStart;
    private MaterialButton btnSetLoopEnd;
    private MaterialButton btnToggleLoop;
    private MaterialButton btnSpeedDown;
    private MaterialButton btnSpeedUp;
    private TextView tvSpeed;
    private MaterialButton btnOpenFile;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private PlaybackController playbackController;
    private boolean isUserSeekingOnBar = false;
    private String currentFileName = "";

    // -------------------------------------------------------------------------
    // Permission / file picker launchers
    // -------------------------------------------------------------------------

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchFilePicker();
                } else {
                    Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String[]> openFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    loadFile(uri);
                }
            });

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on so remote-button users can control from a distance
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        bindViews();
        setupSeekBar();
        setupButtons();

        playbackController = new PlaybackController(this);
        playbackController.setCallbacks(this);

        updateSpeedDisplay();
        updateLoopButtonState();

        // Handle file opened from external app (e.g. Files app)
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (playbackController != null) {
            playbackController.release();
        }
    }

    // -------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            loadFile(intent.getData());
        }
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private void bindViews() {
        tvTrackTitle = findViewById(R.id.tv_track_title);
        tvCurrentTime = findViewById(R.id.tv_current_time);
        tvTotalTime = findViewById(R.id.tv_total_time);
        seekBar = findViewById(R.id.seek_bar);
        btnPlayPause = findViewById(R.id.btn_play_pause);
        btnSeekBack = findViewById(R.id.btn_seek_back);
        btnSeekForward = findViewById(R.id.btn_seek_forward);
        btnReturnToStart = findViewById(R.id.btn_return_to_start);
        btnSetLoopStart = findViewById(R.id.btn_set_loop_start);
        btnSetLoopEnd = findViewById(R.id.btn_set_loop_end);
        btnToggleLoop = findViewById(R.id.btn_toggle_loop);
        btnSpeedDown = findViewById(R.id.btn_speed_down);
        btnSpeedUp = findViewById(R.id.btn_speed_up);
        tvSpeed = findViewById(R.id.tv_speed);
        btnOpenFile = findViewById(R.id.btn_open_file);
    }

    // -------------------------------------------------------------------------
    // Button / SeekBar setup
    // -------------------------------------------------------------------------

    private void setupSeekBar() {
        seekBar.setMax(1000);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && isUserSeekingOnBar) {
                    int targetMs = (int) ((long) progress * playbackController.getDurationMs() / 1000);
                    tvCurrentTime.setText(formatTime(targetMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isUserSeekingOnBar = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                isUserSeekingOnBar = false;
                int targetMs = (int) ((long) bar.getProgress() * playbackController.getDurationMs() / 1000);
                playbackController.seekTo(targetMs);
            }
        });
    }

    private void setupButtons() {
        btnPlayPause.setOnClickListener(v -> playbackController.togglePlayPause());
        btnSeekBack.setOnClickListener(v -> playbackController.seekBackward(SEEK_INCREMENT_MS));
        btnSeekForward.setOnClickListener(v -> playbackController.seekForward(SEEK_INCREMENT_MS));
        btnReturnToStart.setOnClickListener(v -> playbackController.returnToStart());
        btnSetLoopStart.setOnClickListener(v -> {
            playbackController.setLoopStart();
            updateLoopMarkers();
            Toast.makeText(this, R.string.loop_start_set, Toast.LENGTH_SHORT).show();
        });
        btnSetLoopEnd.setOnClickListener(v -> {
            playbackController.setLoopEnd();
            updateLoopMarkers();
            updateLoopButtonState();
            Toast.makeText(this, R.string.loop_end_set, Toast.LENGTH_SHORT).show();
        });
        btnToggleLoop.setOnClickListener(v -> {
            playbackController.toggleLoop();
            updateLoopButtonState();
            updateLoopMarkers();
        });
        btnSpeedDown.setOnClickListener(v -> {
            playbackController.speedDown();
            updateSpeedDisplay();
        });
        btnSpeedUp.setOnClickListener(v -> {
            playbackController.speedUp();
            updateSpeedDisplay();
        });
        btnOpenFile.setOnClickListener(v -> checkPermissionAndOpenFile());
    }

    // -------------------------------------------------------------------------
    // File opening
    // -------------------------------------------------------------------------

    private void checkPermissionAndOpenFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                launchFilePicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Android 6-12: READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                launchFilePicker();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        } else {
            launchFilePicker();
        }
    }

    private void launchFilePicker() {
        openFileLauncher.launch(new String[]{"audio/mpeg", "audio/mp3", "audio/*"});
    }

    private void loadFile(Uri uri) {
        try {
            currentFileName = resolveFileName(uri);
            tvTrackTitle.setText(currentFileName);
            tvCurrentTime.setText(formatTime(0));
            tvTotalTime.setText(formatTime(0));
            seekBar.setProgress(0);
            playbackController.openFile(uri);
            Log.i(TAG, "Loading file: " + uri);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load file: " + uri, e);
            showError(getString(R.string.error_load_file));
        }
    }

    private String resolveFileName(Uri uri) {
        // Try to get the display name from content resolver (works for content:// URIs)
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        String name = cursor.getString(nameIndex);
                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not resolve display name from ContentResolver", e);
            }
        }
        // Fall back to extracting from the URI path segment
        String path = uri.getLastPathSegment();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) {
                path = path.substring(slash + 1);
            }
            return path;
        }
        return uri.toString();
    }

    // -------------------------------------------------------------------------
    // Keyboard shortcuts
    // -------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                playbackController.togglePlayPause();
                return true;

            case KeyEvent.KEYCODE_PAGE_UP:
                playbackController.seekBackward(SEEK_INCREMENT_MS);
                return true;

            case KeyEvent.KEYCODE_PAGE_DOWN:
                playbackController.seekForward(SEEK_INCREMENT_MS);
                return true;

            case KeyEvent.KEYCODE_S:
                playbackController.setLoopStart();
                updateLoopMarkers();
                Toast.makeText(this, R.string.loop_start_set, Toast.LENGTH_SHORT).show();
                return true;

            case KeyEvent.KEYCODE_E:
                playbackController.setLoopEnd();
                updateLoopMarkers();
                updateLoopButtonState();
                Toast.makeText(this, R.string.loop_end_set, Toast.LENGTH_SHORT).show();
                return true;

            case KeyEvent.KEYCODE_L:
                playbackController.toggleLoop();
                updateLoopButtonState();
                updateLoopMarkers();
                return true;

            case KeyEvent.KEYCODE_PLUS:
            case KeyEvent.KEYCODE_EQUALS:
            case KeyEvent.KEYCODE_NUMPAD_ADD:
                playbackController.speedUp();
                updateSpeedDisplay();
                return true;

            case KeyEvent.KEYCODE_MINUS:
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT:
                playbackController.speedDown();
                updateSpeedDisplay();
                return true;

            case KeyEvent.KEYCODE_R:
                playbackController.returnToStart();
                return true;

            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    // -------------------------------------------------------------------------
    // PlaybackController.Callbacks
    // -------------------------------------------------------------------------

    @Override
    public void onPositionChanged(int positionMs) {
        if (!isUserSeekingOnBar) {
            tvCurrentTime.setText(formatTime(positionMs));
            int duration = playbackController.getDurationMs();
            if (duration > 0) {
                seekBar.setProgress((int) ((long) positionMs * 1000 / duration));
            }
        }
    }

    @Override
    public void onDurationChanged(int durationMs) {
        tvTotalTime.setText(formatTime(durationMs));
    }

    @Override
    public void onPlayStateChanged(boolean isPlaying) {
        btnPlayPause.setImageResource(
                isPlaying ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlayPause.setContentDescription(
                getString(isPlaying ? R.string.pause : R.string.play));
    }

    @Override
    public void onPrepared(int durationMs) {
        tvTotalTime.setText(formatTime(durationMs));
        tvCurrentTime.setText(formatTime(0));
        seekBar.setProgress(0);
        // Reset loop display
        seekBar.setLoopRegion(false, 0, 0);
        updateLoopButtonState();
    }

    @Override
    public void onError(String message) {
        Log.e(TAG, "Playback error: " + message);
        showError(message);
    }

    @Override
    public void onCompletion() {
        btnPlayPause.setImageResource(R.drawable.ic_play);
        btnPlayPause.setContentDescription(getString(R.string.play));
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void updateLoopButtonState() {
        boolean loopEnabled = playbackController.isLoopEnabled();
        btnToggleLoop.setSelected(loopEnabled);
        // Change visual state: filled/pressed look when active
        if (loopEnabled) {
            btnToggleLoop.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.loop_button_active));
        } else {
            btnToggleLoop.setBackgroundTintList(
                    ContextCompat.getColorStateList(this, R.color.loop_button_inactive));
        }
        updateLoopMarkers();
    }

    private void updateLoopMarkers() {
        boolean loopEnabled = playbackController.isLoopEnabled();
        int duration = playbackController.getDurationMs();
        if (duration <= 0) {
            seekBar.setLoopRegion(false, 0, 0);
            return;
        }
        int startProgress = (int) ((long) playbackController.getLoopStartMs() * 1000 / duration);
        int endProgress = (int) ((long) playbackController.getLoopEndMs() * 1000 / duration);
        seekBar.setLoopRegion(loopEnabled, startProgress, endProgress);
    }

    private void updateSpeedDisplay() {
        float speed = playbackController.getPlaybackSpeed();
        tvSpeed.setText(String.format(Locale.US, "%.0f%%", speed * 100));
    }

    private void showError(String message) {
        runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()
        );
    }

    /**
     * Formats a duration in milliseconds as {@code mm:ss} or {@code h:mm:ss}.
     */
    private static String formatTime(int totalMs) {
        int totalSeconds = totalMs / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(Locale.US, "%02d:%02d", minutes, seconds);
        }
    }
}
