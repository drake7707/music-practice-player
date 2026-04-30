package com.musicpracticeplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatSeekBar;
import androidx.core.content.ContextCompat;

/**
 * A custom SeekBar that visually highlights the active loop region on the track.
 * The loop region is drawn as a tinted overlay between the loop-start and loop-end markers.
 * Two vertical tick marks indicate the exact loop-start and loop-end positions.
 */
public class LoopSeekBar extends AppCompatSeekBar {

    private static final float TICK_WIDTH_DP = 3f;
    private static final float TICK_HEIGHT_FRACTION = 0.8f;

    private final Paint loopRegionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loopTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF loopRegionRect = new RectF();

    private boolean loopEnabled = false;
    private int loopStartProgress = 0;
    private int loopEndProgress = 0;

    private OnSeekBarChangeListener externalListener;

    public LoopSeekBar(Context context) {
        super(context);
        init(context);
    }

    public LoopSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public LoopSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        loopRegionPaint.setColor(ContextCompat.getColor(context, R.color.loop_region_fill));
        loopRegionPaint.setAlpha(120);
        loopRegionPaint.setStyle(Paint.Style.FILL);

        loopTickPaint.setColor(ContextCompat.getColor(context, R.color.loop_tick));
        loopTickPaint.setStyle(Paint.Style.FILL);
        loopTickPaint.setStrokeWidth(dpToPx(context, TICK_WIDTH_DP));

        // Intercept touch events for seeking, pass through to super
        super.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (externalListener != null) {
                    externalListener.onProgressChanged(seekBar, progress, fromUser);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (externalListener != null) {
                    externalListener.onStartTrackingTouch(seekBar);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (externalListener != null) {
                    externalListener.onStopTrackingTouch(seekBar);
                }
            }
        });
    }

    @Override
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
        // Route external listeners through our internal one
        externalListener = listener;
    }

    /**
     * Updates the loop region display.
     *
     * @param enabled         Whether the loop is currently active.
     * @param loopStartProgress  Loop start as a value in [0, getMax()].
     * @param loopEndProgress    Loop end as a value in [0, getMax()].
     */
    public void setLoopRegion(boolean enabled, int loopStartProgress, int loopEndProgress) {
        this.loopEnabled = enabled;
        this.loopStartProgress = loopStartProgress;
        this.loopEndProgress = loopEndProgress;
        invalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!loopEnabled || loopEndProgress <= loopStartProgress) {
            return;
        }

        int max = getMax();
        if (max <= 0) return;

        // The track spans from paddingLeft+thumbOffset to width-paddingRight-thumbOffset
        int thumbOffset = getThumb() != null ? getThumb().getIntrinsicWidth() / 2 : 0;
        float trackLeft = getPaddingLeft() + thumbOffset;
        float trackRight = getWidth() - getPaddingRight() - thumbOffset;
        float trackWidth = trackRight - trackLeft;

        float startX = trackLeft + (float) loopStartProgress / max * trackWidth;
        float endX = trackLeft + (float) loopEndProgress / max * trackWidth;

        float centerY = getHeight() / 2f;
        float halfHeight = (getHeight() - getPaddingTop() - getPaddingBottom()) / 2f * TICK_HEIGHT_FRACTION;

        // Draw the filled loop region
        loopRegionRect.set(startX, centerY - halfHeight, endX, centerY + halfHeight);
        canvas.drawRect(loopRegionRect, loopRegionPaint);

        // Draw the loop start and end tick marks
        float tickHalfWidth = dpToPx(getContext(), TICK_WIDTH_DP) / 2f;
        canvas.drawRect(startX - tickHalfWidth, centerY - halfHeight,
                startX + tickHalfWidth, centerY + halfHeight, loopTickPaint);
        canvas.drawRect(endX - tickHalfWidth, centerY - halfHeight,
                endX + tickHalfWidth, centerY + halfHeight, loopTickPaint);
    }

    private static float dpToPx(Context context, float dp) {
        return dp * context.getResources().getDisplayMetrics().density;
    }
}
