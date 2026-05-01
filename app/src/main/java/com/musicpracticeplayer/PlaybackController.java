package com.musicpracticeplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * Controls audio playback using ExoPlayer.
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

    private ExoPlayer exoPlayer;

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
     * Opens the given URI and prepares the player asynchronously.
     * Any previously loaded file is released first.
     */
    public void openFile(Uri uri) {
        releasePlayer();

        try {
            exoPlayer = new ExoPlayer.Builder(context)
                    .build();
            exoPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                    /* handleAudioFocus= */ true
            );
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        int duration = (int) exoPlayer.getDuration();
                        loopStartMs = 0;
                        loopEndMs = duration;
                        isLoopEnabled = false;
                        applyPlaybackSpeed();
                        if (callbacks != null) {
                            callbacks.onPrepared(duration);
                            callbacks.onDurationChanged(duration);
                        }
                    } else if (state == Player.STATE_ENDED) {
                        stopPositionUpdates();
                        if (callbacks != null) {
                            callbacks.onCompletion();
                            callbacks.onPlayStateChanged(false);
                        }
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        startPositionUpdates();
                    } else {
                        stopPositionUpdates();
                    }
                    if (callbacks != null) {
                        callbacks.onPlayStateChanged(isPlaying);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error: " + error.getMessage(), error);
                    if (callbacks != null) {
                        callbacks.onError("Playback error: " + error.getMessage());
                    }
                }
            });
            exoPlayer.setMediaItem(MediaItem.fromUri(uri));
            exoPlayer.prepare();
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
        if (exoPlayer == null) return;
        if (exoPlayer.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    /** Starts or resumes playback. */
    public void play() {
        if (exoPlayer == null) return;
        exoPlayer.play();
    }

    /** Pauses playback. */
    public void pause() {
        if (exoPlayer == null) return;
        exoPlayer.pause();
    }

    /**
     * Seeks forward by the given number of milliseconds.
     * Clamped to [0, duration].
     */
    public void seekForward(int deltaMs) {
        if (exoPlayer == null) return;
        long newPos = Math.min(exoPlayer.getCurrentPosition() + deltaMs, exoPlayer.getDuration());
        seekTo((int) newPos);
    }

    /**
     * Seeks backward by the given number of milliseconds.
     * Clamped to [0, duration].
     */
    public void seekBackward(int deltaMs) {
        if (exoPlayer == null) return;
        long newPos = Math.max(exoPlayer.getCurrentPosition() - deltaMs, 0);
        seekTo((int) newPos);
    }

    /** Seeks to an absolute position in milliseconds. */
    public void seekTo(int positionMs) {
        if (exoPlayer == null) return;
        exoPlayer.seekTo(positionMs);
        if (callbacks != null) {
            callbacks.onPositionChanged(positionMs);
        }
    }

    /**
     * Returns to the start of the track, or to the loop start when loop is active.
     */
    public void returnToStart() {
        if (exoPlayer == null) return;
        int targetPos = isLoopEnabled ? loopStartMs : 0;
        seekTo(targetPos);
    }

    // -------------------------------------------------------------------------
    // Loop controls
    // -------------------------------------------------------------------------

    /** Sets the loop start point to the current playback position. */
    public void setLoopStart() {
        if (exoPlayer == null) return;
        loopStartMs = (int) exoPlayer.getCurrentPosition();
        // Ensure start never exceeds end (when end was already set)
        if (loopEndMs > 0 && loopStartMs >= loopEndMs) {
            loopEndMs = (int) exoPlayer.getDuration();
        }
        Log.d(TAG, "Loop start set to " + loopStartMs + " ms");
    }

    /** Sets the loop end point to the current playback position and activates the loop. */
    public void setLoopEnd() {
        if (exoPlayer == null) return;
        int currentPos = (int) exoPlayer.getCurrentPosition();
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
        if (exoPlayer == null) return;
        exoPlayer.setPlaybackParameters(new PlaybackParameters(playbackSpeed));
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    public boolean isPlaying() {
        return exoPlayer != null && exoPlayer.isPlaying();
    }

    public boolean isFileLoaded() {
        return exoPlayer != null;
    }

    public int getCurrentPositionMs() {
        if (exoPlayer == null) return 0;
        return (int) exoPlayer.getCurrentPosition();
    }

    public int getDurationMs() {
        if (exoPlayer == null) return 0;
        long duration = exoPlayer.getDuration();
        return duration == C.TIME_UNSET ? 0 : (int) duration;
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
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            if (callbacks != null) {
                callbacks.onPositionChanged((int) exoPlayer.getCurrentPosition());
            }
            mainHandler.postDelayed(positionUpdateRunnable, POSITION_UPDATE_INTERVAL_MS);
        }
    }

    private void checkLoopBoundary() {
        if (exoPlayer != null && exoPlayer.isPlaying() && isLoopEnabled) {
            int currentPos = (int) exoPlayer.getCurrentPosition();
            if (loopEndMs > loopStartMs && currentPos >= loopEndMs) {
                seekTo(loopStartMs);
            }
        }
        if (exoPlayer != null && exoPlayer.isPlaying()) {
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
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }
}
