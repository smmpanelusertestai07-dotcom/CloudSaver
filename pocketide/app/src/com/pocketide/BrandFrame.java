package com.pocketide;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The first frame the app draws itself, and the moving edge that shows work is happening.
 *
 * Two things live here because they answer the same complaint: the app opened badly. Text
 * appeared that was not meant to be read, and a twenty-minute install showed nothing moving.
 *
 * THE FIRST FRAME. Android 12 took the splash away from apps and gave it to the system, and
 * what the system draws is an icon on a colour -- there is no slot for a name, and no API that
 * adds one. So the name is the first thing the app draws for itself, on the same ground and
 * with the mark in the same place, and it fades out into the interface. Done properly the join
 * is invisible: the owner sees one continuous opening rather than a splash, a flash, and then a
 * screen.
 *
 * The flash was real and had its own cause. The window's background comes from the phone's
 * night setting, and everything the app draws comes from the app's OWN theme setting -- so an
 * owner who set this app to Dark on a phone in Light mode got a white window for one frame and
 * then a dark interface painted over it. Theme.apply() now paints the window from the app's own
 * choice before the first frame, which is the fix; this screen covering the gap is the polish.
 *
 * THE MOVING EDGE. A progress bar that cannot know its percentage is a lie, and set-up genuinely
 * cannot: apt does not say how long it will take. What it can honestly show is that something is
 * still happening, which is what a travelling highlight around the edge of a card does -- it
 * moves while work moves, and it stops when work stops.
 *
 * Both respect the phone's animation setting. Someone who has turned animations off has said so
 * once, in the place the system provides, and an app that animates anyway is overruling them.
 */
final class BrandFrame {

    private BrandFrame() {}

    /** True unless the owner has turned animations off in the phone's own settings. */
    static boolean animationsOn(Context context) {
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable unreadable) {
            return true;
        }
    }

    /**
     * Covers the window with the mark and the name, then takes itself away.
     *
     * Shown once per process rather than once per screen: coming back from the editor is not a
     * new opening, and being shown a brand animation on the way back from a task is the kind of
     * thing that makes an app feel slow.
     */
    private static boolean shownThisProcess;

    static void openOver(Activity activity, FrameLayout root) {
        if (shownThisProcess) return;
        shownThisProcess = true;

        LinearLayout cover = Ui.column(activity);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(Brand.TILE_FLAT);
        cover.setClickable(true);

        cover.addView(Shell.mark(activity, 88));

        TextView name = Ui.bold(activity, "PocketIDE", 30f, Brand.ON_BRAND);
        name.setGravity(Gravity.CENTER);
        cover.addView(name, Ui.wide(activity, 18));

        TextView line = Ui.text(activity, activity.getString(R.string.tagline), 14f,
                Brand.ON_BRAND_MUTED);
        line.setGravity(Gravity.CENTER);
        cover.addView(line, Ui.wide(activity, 8));

        root.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (!animationsOn(activity)) {
            root.removeView(cover);
            return;
        }
        // The name arrives after the mark, because the system splash already showed the mark
        // and this is the part it could not.
        name.setAlpha(0f);
        line.setAlpha(0f);
        name.animate().alpha(1f).setStartDelay(60).setDuration(220).start();
        line.animate().alpha(1f).setStartDelay(140).setDuration(220).start();
        cover.animate().alpha(0f).setStartDelay(620).setDuration(260)
                .withEndAction(() -> {
                    if (cover.getParent() == root) root.removeView(cover);
                }).start();
    }

    /**
     * A card edge with a highlight travelling around it, for work whose length is not knowable.
     *
     * Drawn rather than animated as a view, because the shape is a rounded rectangle's outline
     * and there is no stock widget for that. The gradient is swept by translating the shader,
     * which is one matrix per frame and costs nothing.
     */
    static final class MovingEdge extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final float radius;
        private final int base;
        private final int glow;
        private ValueAnimator animator;
        private float phase;

        MovingEdge(Context context, boolean dark, float radiusDp) {
            super(context);
            this.radius = Ui.dp(context, radiusDp);
            this.base = Ui.line(dark);
            this.glow = Ui.accent(dark);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, Ui.dp(context, 1.5f)));
        }

        void start() {
            if (!animationsOn(getContext()) || animator != null) return;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(1600);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.setInterpolator(null);           // constant speed; an eased loop pulses
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        void stop() {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            stop();
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            float inset = stroke.getStrokeWidth() / 2f;
            bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
            if (bounds.width() <= 0 || bounds.height() <= 0) return;

            if (animator == null) {
                stroke.setShader(null);
                stroke.setColor(base);
                canvas.drawRoundRect(bounds, radius, radius, stroke);
                return;
            }
            // A highlight three-tenths of the width wide, slid along a gradient that is
            // otherwise the resting line colour. Travelling off one end and back on the other
            // is what makes it read as going round rather than flashing.
            float width = getWidth();
            float head = (phase * 2f - 0.5f) * width;
            stroke.setShader(new LinearGradient(head, 0, head + width * 0.3f, 0,
                    new int[]{base, glow, base}, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRoundRect(bounds, radius, radius, stroke);
        }
    }
}
