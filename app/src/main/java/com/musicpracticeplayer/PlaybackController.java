package com.musicpracticeplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Controls audio playback using MediaPlayer.
 * Manages play/pause, seeking, loop region, and playback speed.
 * All public methods must be called from the main thread.
 */
public class PlaybackController {

    private static final String TAG = "PlaybackController";

    /** Minimum playback speed (10% of normal). */
    public static final float SPEED_MIN = 0.1f;
    /** Maximum playback speed (200% of normal). */
    public static final float SPEED_MAX = 2.0f;
    /** Step size for speed adjustments. */
    public static final float SPEED_STEP = 0.1f;
    /** Default playback speed (100%). */
    public static final float SPEED_DEFAULT = 1.0f;

    /** How often the UI position is refreshed (ms). */
    private static final int POSITION_UPDATE_INTERVAL_MS = 100;
    /** How often the loop boundary is checked (ms). */
    private static final int LOOP_CHECK_INTERVAL_MS = 50;

    private final Context context;
    private final Handler mainHandler;

    private MediaPlayer mediaPlayer;
    private Uri currentUri;

    private boolean isLoopEnabled = false;
    private int loopStartMs = 0;
    private int loopEndMs = 0;

    private float playbackSpeed = SPEED_DEFAULT;

    private Callbacks callbacks;

    private final Runnable positionUpdateRunnable = this::updatePosition;
    private final Runnable loopCheckRunnable = this::checkLoopBoundary;

    /**
     * Callbacks that the UI registers to receive updates from the controller.
     */
    public interface Callbacks {
        /** Called periodically with the current playback position in milliseconds. */
        void onPositionChanged(int positionMs);
        /** Called when the total duration becomes known. */
        void onDurationChanged(int durationMs);
        /** Called when playback starts or pauses. */
        void onPlayStateChanged(boolean isPlaying);
        /** Called when the file is fully prepared and ready. */
        void onPrepared(int durationMs);
        /** Called when a non-recoverable error occurs. */
        void onError(String message);
        /** Called when playback reaches the natural end of the file. */
        void onCompletion();
    }

    public PlaybackController(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    // -------------------------------------------------------------------------
    // File loading
    // -------------------------------------------------------------------------

    /**
     * Opens the given URI and prepares the media player asynchronously.
     * Any previously loaded file is released first.
     */
    public void openFile(Uri uri) {
        releasePlayer();
        currentUri = uri;

        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            );
            mediaPlayer.setDataSource(context, uri);
            mediaPlayer.setOnPreparedListener(mp -> {
                applyPlaybackSpeed();
                int duration = mp.getDuration();
                // Reset loop to full track
                loopStartMs = 0;
                loopEndMs = duration;
                isLoopEnabled = false;
                if (callbacks != null) {
                    callbacks.onPrepared(duration);
                    callbacks.onDurationChanged(duration);
                }
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                stopPositionUpdates();
                if (callbacks != null) {
                    callbacks.onCompletion();
                    callbacks.onPlayStateChanged(false);
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer error: what=" + what + " extra=" + extra);
                if (callbacks != null) {
                    callbacks.onError("Playback error (code " + what + ")");
                }
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open file: " + uri, e);
            if (callbacks != null) {
                callbacks.onError("Cannot open file: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Transport controls
    // -------------------------------------------------------------------------

    /** Toggles between playing and paused. */
    public void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    /** Starts or resumes playback. */
    public void play() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.start();
            startPositionUpdates();
            if (callbacks != null) {
                callbacks.onPlayStateChanged(true);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot start playback", e);
        }
    }

    /** Pauses playback. */
    public void pause() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.pause();
            stopPositionUpdates();
            if (callbacks != null) {
                callbacks.onPlayStateChanged(false);
                callbacks.onPositionChanged(mediaPlayer.getCurrentPosition());
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot pause playback", e);
        }
    }

    /**
     * Seeks forward by the given number of milliseconds.
     * Clamped to [0, duration].
     */
    public void seekForward(int deltaMs) {
        if (mediaPlayer == null) return;
        int newPos = Math.min(mediaPlayer.getCurrentPosition() + deltaMs,
                mediaPlayer.getDuration());
        seekTo(newPos);
    }

    /**
     * Seeks backward by the given number of milliseconds.
     * Clamped to [0, duration].
     */
    public void seekBackward(int deltaMs) {
        if (mediaPlayer == null) return;
        int newPos = Math.max(mediaPlayer.getCurrentPosition() - deltaMs, 0);
        seekTo(newPos);
    }

    /** Seeks to an absolute position in milliseconds. */
    public void seekTo(int positionMs) {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.seekTo(positionMs);
            if (callbacks != null) {
                callbacks.onPositionChanged(positionMs);
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Cannot seek", e);
        }
    }

    /**
     * Returns to the start of the track, or to the loop start when loop is active.
     */
    public void returnToStart() {
        if (mediaPlayer == null) return;
        int targetPos = isLoopEnabled ? loopStartMs : 0;
        seekTo(targetPos);
    }

    // -------------------------------------------------------------------------
    // Loop controls
    // -------------------------------------------------------------------------

    /** Sets the loop start point to the current playback position. */
    public void setLoopStart() {
        if (mediaPlayer == null) return;
        loopStartMs = mediaPlayer.getCurrentPosition();
        // Ensure start never exceeds end (when end was already set)
        if (loopEndMs > 0 && loopStartMs >= loopEndMs) {
            loopEndMs = mediaPlayer.getDuration();
        }
        Log.d(TAG, "Loop start set to " + loopStartMs + " ms");
    }

    /** Sets the loop end point to the current playback position and activates the loop. */
    public void setLoopEnd() {
        if (mediaPlayer == null) return;
        int currentPos = mediaPlayer.getCurrentPosition();
        // End must be after start
        if (currentPos <= loopStartMs) {
            Log.w(TAG, "Loop end must be after loop start");
            return;
        }
        loopEndMs = currentPos;
        // Setting the end also activates the loop per the requirements
        setLoopEnabled(true);
        Log.d(TAG, "Loop end set to " + loopEndMs + " ms, loop activated");
    }

    /** Toggles the loop on or off. */
    public void toggleLoop() {
        setLoopEnabled(!isLoopEnabled);
    }

    /** Explicitly sets the loop enabled state. */
    public void setLoopEnabled(boolean enabled) {
        isLoopEnabled = enabled;
        Log.d(TAG, "Loop " + (enabled ? "enabled" : "disabled"));
    }

    // -------------------------------------------------------------------------
    // Speed controls
    // -------------------------------------------------------------------------

    /**
     * Increases the playback speed by one step (10%), up to SPEED_MAX.
     */
    public void speedUp() {
        setPlaybackSpeed(Math.min(playbackSpeed + SPEED_STEP, SPEED_MAX));
    }

    /**
     * Decreases the playback speed by one step (10%), down to SPEED_MIN.
     */
    public void speedDown() {
        setPlaybackSpeed(Math.max(playbackSpeed - SPEED_STEP, SPEED_MIN));
    }

    /** Sets an explicit playback speed. Clamped to [SPEED_MIN, SPEED_MAX]. */
    public void setPlaybackSpeed(float speed) {
        playbackSpeed = Math.max(SPEED_MIN, Math.min(SPEED_MAX, speed));
        applyPlaybackSpeed();
    }

    private void applyPlaybackSpeed() {
        if (mediaPlayer == null) return;
        try {
            boolean wasPlaying = mediaPlayer.isPlaying();
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(playbackSpeed);
            mediaPlayer.setPlaybackParams(params);
            // On some Android versions setPlaybackParams() resumes a paused player;
            // restore the previous state if that happened unexpectedly.
            if (!wasPlaying && mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set playback speed", e);
        }
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    public boolean isFileLoaded() {
        return mediaPlayer != null;
    }

    public int getCurrentPositionMs() {
        if (mediaPlayer == null) return 0;
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int getDurationMs() {
        if (mediaPlayer == null) return 0;
        try {
            return mediaPlayer.getDuration();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public boolean isLoopEnabled() {
        return isLoopEnabled;
    }

    public int getLoopStartMs() {
        return loopStartMs;
    }

    public int getLoopEndMs() {
        return loopEndMs;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    // -------------------------------------------------------------------------
    // Position update loop
    // -------------------------------------------------------------------------

    private void startPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable);
        mainHandler.removeCallbacks(loopCheckRunnable);
        mainHandler.post(positionUpdateRunnable);
        mainHandler.post(loopCheckRunnable);
    }

    private void stopPositionUpdates() {
        mainHandler.removeCallbacks(positionUpdateRunnable);
        mainHandler.removeCallbacks(loopCheckRunnable);
    }

    private void updatePosition() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            if (callbacks != null) {
                callbacks.onPositionChanged(mediaPlayer.getCurrentPosition());
            }
            mainHandler.postDelayed(positionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS);
        }
    }

    private void checkLoopBoundary() {
        if (mediaPlayer != null && mediaPlayer.isPlaying() && isLoopEnabled) {
            int currentPos = mediaPlayer.getCurrentPosition();
            if (loopEndMs > loopStartMs && currentPos >= loopEndMs) {
                seekTo(loopStartMs);
            }
        }
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mainHandler.postDelayed(loopCheckRunnable, LOOP_CHECK_INTERVAL_MS);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Releases all resources. Call from Activity.onDestroy(). */
    public void release() {
        stopPositionUpdates();
        releasePlayer();
    }

    private void releasePlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped or not prepared
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }
}
