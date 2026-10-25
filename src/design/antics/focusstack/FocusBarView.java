package design.antics.focusstack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/**
 * Horizontal bar over the current lens' manual-focus range (0 = infinity
 * .. max = closest, in diopters — LENS_FOCUS_DISTANCE's own units).
 * Dragging moves the live "current" thumb and reports every intermediate
 * position via Listener.onCurrentChanged, so the caller can push it
 * straight to a manual-focus preview request for real-time focus-by-eye
 * feedback — only while {@link #setInteractive} is true (CaptureActivity
 * gates this on its manual-focus toggle; with it off, the AF-lock buttons
 * drive focus instead and the bar is a read-only display of where they
 * landed). Far/near markers and the planned bracket's interval ticks are
 * drawn once set but aren't interactive themselves.
 */
final class FocusBarView extends View {

    interface Listener {
        void onCurrentChanged(float distance);
    }

    private float minDistance = 0f, maxDistance = 1f;
    private float current = 0f;
    private Float farDistance, nearDistance;
    private float[] intervalSteps;
    private Listener listener;
    private boolean interactive;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint currentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint farPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    FocusBarView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;

        trackPaint.setColor(Color.DKGRAY);
        trackPaint.setStrokeWidth(4f * density);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setColor(Color.LTGRAY);
        tickPaint.setStrokeWidth(2f * density);

        currentPaint.setColor(Color.WHITE);
        farPaint.setColor(Color.CYAN);
        nearPaint.setColor(Color.YELLOW);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(13f * density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        setMinimumHeight((int) (64 * density));
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Whether dragging moves the current-focus thumb at all. */
    void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    /** min/max in diopters; min is normally 0 (infinity), max is LENS_INFO_MINIMUM_FOCUS_DISTANCE. */
    void setRange(float min, float max) {
        minDistance = min;
        maxDistance = Math.max(max, min + 0.0001f);
        current = clamp(current);
        invalidate();
    }

    void setCurrent(float distance) {
        current = clamp(distance);
        invalidate();
    }

    float getCurrent() {
        return current;
    }

    void setFar(Float distance) {
        farDistance = distance;
        invalidate();
    }

    void setNear(Float distance) {
        nearDistance = distance;
        invalidate();
    }

    void setIntervalSteps(float[] steps) {
        intervalSteps = steps;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Without this, WRAP_CONTENT next to a weight=1 sibling in a
        // vertical LinearLayout resolves to an AT_MOST spec covering the
        // whole remaining height, and the default View.getDefaultSize()
        // just takes it — this view then eats all the space meant for the
        // TextureView preview above it (which collapses to 0px) and every
        // control below it gets pushed off-screen. Confirmed on-device:
        // this was the actual cause of the "camera view doesn't work"
        // black screen, not a camera/HAL issue.
        int height = resolveSize((int) (64 * density), heightMeasureSpec);
        int width = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        setMeasuredDimension(width, height);
    }

    private float clamp(float d) {
        return Math.max(minDistance, Math.min(maxDistance, d));
    }

    private int trackPadding() {
        return (int) (16 * density);
    }

    private float xForDistance(float d) {
        int pad = trackPadding();
        float t = (d - minDistance) / (maxDistance - minDistance);
        return pad + t * (getWidth() - 2 * pad);
    }

    private float distanceForX(float x) {
        int pad = trackPadding();
        float t = (x - pad) / (float) (getWidth() - 2 * pad);
        return clamp(minDistance + t * (maxDistance - minDistance));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float midY = getHeight() / 2f;
        canvas.drawLine(trackPadding(), midY, getWidth() - trackPadding(), midY, trackPaint);

        if (intervalSteps != null) {
            for (float step : intervalSteps) {
                float x = xForDistance(step);
                canvas.drawLine(x, midY - 10 * density, x, midY + 10 * density, tickPaint);
            }
        }
        if (farDistance != null) {
            float x = xForDistance(farDistance);
            canvas.drawCircle(x, midY - 22 * density, 6 * density, farPaint);
            canvas.drawText("F", x, midY - 28 * density, textPaint);
        }
        if (nearDistance != null) {
            float x = xForDistance(nearDistance);
            canvas.drawCircle(x, midY - 22 * density, 6 * density, nearPaint);
            canvas.drawText("N", x, midY - 28 * density, textPaint);
        }

        float cx = xForDistance(current);
        canvas.drawCircle(cx, midY, 11 * density, currentPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                current = distanceForX(event.getX());
                invalidate();
                if (listener != null) listener.onCurrentChanged(current);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
