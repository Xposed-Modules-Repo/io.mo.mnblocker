package io.mo.mnblocker;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;

/**
 * Rounded rectangle whose corners are superellipse quadrants (|x|^n + |y|^n = 1,
 * n = 4) instead of circular arcs.
 *
 * A circular corner is only G1-continuous: curvature jumps from 0 on the straight
 * edge to 1/r on the arc, which reads as a faint crease on a large card. The
 * superellipse's curvature grows from 0, so edge and corner blend (G2) — the
 * iOS "continuous corner" look.
 *
 * Its visual radius is smaller than a circle's of the same extent, so the
 * radius passed in is the circular radius it should look like, and the corner
 * extent is {@link #EXTENT_FACTOR} times that.
 *
 * Small elements gain nothing visible from this; {@link Ui} only uses it from
 * the medium radius up.
 */
final class SmoothCornerDrawable extends Drawable {

    static final float EXTENT_FACTOR = 1.3f;
    private static final float EXPONENT = 4f;
    private static final int SEGMENTS_PER_CORNER = 16;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path strokePath = new Path();
    private final float radius;
    private float strokeWidth;

    SmoothCornerDrawable(int fillColor, float radiusPx) {
        this.radius = radiusPx;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(fillColor);
        stroke.setStyle(Paint.Style.STROKE);
    }

    SmoothCornerDrawable stroke(int color, float widthPx) {
        stroke.setColor(color);
        strokeWidth = widthPx;
        stroke.setStrokeWidth(widthPx);
        rebuild(getBounds());
        invalidateSelf();
        return this;
    }

    void setFillColor(int color) {
        if (fill.getColor() != color) {
            fill.setColor(color);
            invalidateSelf();
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rebuild(bounds);
    }

    private void rebuild(Rect b) {
        build(path, new RectF(b));
        if (strokeWidth > 0f) {
            RectF inset = new RectF(b);
            // Centre the stroke inside the bounds so it is not clipped by the view.
            inset.inset(strokeWidth / 2f, strokeWidth / 2f);
            build(strokePath, inset);
        }
    }

    private void build(Path out, RectF r) {
        out.reset();
        float w = r.width();
        float h = r.height();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float ext = Math.min(radius * EXTENT_FACTOR, Math.min(w, h) / 2f);
        if (ext <= 0f) {
            out.addRect(r, Path.Direction.CW);
            return;
        }
        float p = 2f / EXPONENT;
        // Corner centres, walked counter-clockwise in maths terms starting top-right.
        float[][] centres = {
                {r.right - ext, r.top + ext},
                {r.left + ext, r.top + ext},
                {r.left + ext, r.bottom - ext},
                {r.right - ext, r.bottom - ext},
        };
        boolean first = true;
        for (int i = 0; i < 4; i++) {
            float cx = centres[i][0];
            float cy = centres[i][1];
            for (int s = 0; s <= SEGMENTS_PER_CORNER; s++) {
                double t = (i + s / (double) SEGMENTS_PER_CORNER) * (Math.PI / 2);
                float x = cx + ext * spow((float) Math.cos(t), p);
                float y = cy - ext * spow((float) Math.sin(t), p);
                if (first) {
                    out.moveTo(x, y);
                    first = false;
                } else {
                    out.lineTo(x, y);
                }
            }
        }
        out.close();
    }

    private static float spow(float v, float p) {
        return Math.signum(v) * (float) Math.pow(Math.abs(v), p);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(path, fill);
        if (strokeWidth > 0f) {
            canvas.drawPath(strokePath, stroke);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void getOutline(Outline outline) {
        if (path.isEmpty()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(path);
        } else {
            // A superellipse is convex, which is all the pre-R API asks for.
            outline.setConvexPath(path);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        stroke.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
