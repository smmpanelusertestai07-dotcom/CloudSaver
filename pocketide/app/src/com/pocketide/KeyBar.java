package com.pocketide;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The keys a touch screen cannot otherwise reach, and a trackpad for the ones a finger cannot
 * place.
 *
 * Both are hidden by default, and that is the point. The agent panel is a webview -- ordinary
 * HTML with the publisher's own buttons -- so chatting, reading a plan, accepting a diff and
 * changing a setting are all done by tapping, and a permanent row of Ctrl and Esc keys under
 * them would be clutter that says "this is really a desktop".
 *
 * They exist because the editor is not a webview. Monaco has no touch text selection at all --
 * that is Microsoft's own open issue, not something this app can fix -- so placing a cursor
 * precisely needs something other than a fingertip. The trackpad is that something: dragging on
 * it sends arrow keys, which is the one input Monaco does understand. VSCodroid, whose entire
 * purpose is VS Code on Android, ships the same two controls, which is a fair signal that there
 * is no cleverer answer available.
 */
final class KeyBar extends LinearLayout {

    interface Target {
        /** Sends one key, with modifiers, to whatever has focus. */
        void key(int keyCode, int metaState);
    }

    private final Target target;
    private final boolean dark;
    private final LinearLayout keyRow;
    private final Trackpad trackpad;
    private boolean ctrlLatched;
    private TextView ctrlButton;

    KeyBar(Context context, Target target) {
        super(context);
        this.target = target;
        this.dark = Ui.dark(context);
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.bg(dark));

        trackpad = new Trackpad(context, dark, target);
        trackpad.setVisibility(GONE);
        LayoutParams padParams = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 96));
        int side = Ui.dp(context, 12);
        padParams.setMargins(side, Ui.dp(context, 6), side, 0);
        addView(trackpad, padParams);

        keyRow = new LinearLayout(context);
        keyRow.setOrientation(HORIZONTAL);
        int pad = Ui.dp(context, 6);
        keyRow.setPadding(pad, pad, pad, pad);

        ctrlButton = addKey("Ctrl", KeyEvent.KEYCODE_UNKNOWN);
        ctrlButton.setOnClickListener(v -> toggleCtrl());
        addKey("Esc", KeyEvent.KEYCODE_ESCAPE);
        addKey("Tab", KeyEvent.KEYCODE_TAB);
        addKey("↑", KeyEvent.KEYCODE_DPAD_UP);
        addKey("↓", KeyEvent.KEYCODE_DPAD_DOWN);
        addKey("←", KeyEvent.KEYCODE_DPAD_LEFT);
        addKey("→", KeyEvent.KEYCODE_DPAD_RIGHT);
        addKey("Home", KeyEvent.KEYCODE_MOVE_HOME);
        addKey("End", KeyEvent.KEYCODE_MOVE_END);
        addKey("PgUp", KeyEvent.KEYCODE_PAGE_UP);
        addKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN);

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(keyRow);
        scroll.setVisibility(GONE);
        addView(scroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        keyScroll = scroll;
    }

    private HorizontalScrollView keyScroll;

    private TextView addKey(String label, int keyCode) {
        Context context = getContext();
        TextView key = Ui.medium(context, label, 13.5f, Ui.text(dark));
        key.setGravity(Gravity.CENTER);
        int padX = Ui.dp(context, 13);
        key.setPadding(padX, Ui.dp(context, 11), padX, Ui.dp(context, 11));
        key.setMinWidth(Ui.dp(context, Ui.TOUCH_TARGET_DP));
        key.setMinHeight(Ui.dp(context, Ui.TOUCH_TARGET_DP));
        key.setBackground(Ui.tappable(context,
                Ui.outlined(context, Ui.card(dark), Ui.line(dark), 12), dark));
        key.setClickable(true);
        key.setFocusable(true);
        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            key.setOnClickListener(v -> {
                target.key(keyCode, ctrlLatched ? KeyEvent.META_CTRL_ON : 0);
                if (ctrlLatched) setCtrl(false);
            });
        }
        LayoutParams params = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(context, 6);
        keyRow.addView(key, params);
        return key;
    }

    /**
     * Ctrl latches rather than being held.
     *
     * A finger cannot hold one key and press another, so a chord has to be two taps. It clears
     * itself after the next key, because a latch left on turns every later keystroke into a
     * shortcut -- which is a confusing state to be in with no visible modifier light.
     */
    private void toggleCtrl() { setCtrl(!ctrlLatched); }

    private void setCtrl(boolean on) {
        ctrlLatched = on;
        ctrlButton.setTextColor(on ? Brand.ON_BRAND : Ui.text(dark));
        ctrlButton.setBackground(on
                ? Ui.fill(getContext(), Ui.accent(dark), 12)
                : Ui.tappable(getContext(),
                        Ui.outlined(getContext(), Ui.card(dark), Ui.line(dark), 12), dark));
    }

    void toggleKeys() {
        boolean showing = keyScroll.getVisibility() == VISIBLE;
        keyScroll.setVisibility(showing ? GONE : VISIBLE);
        if (showing) setCtrl(false);
    }

    void toggleTrackpad() {
        trackpad.setVisibility(trackpad.getVisibility() == VISIBLE ? GONE : VISIBLE);
    }

    boolean anythingShowing() {
        return keyScroll.getVisibility() == VISIBLE || trackpad.getVisibility() == VISIBLE;
    }

    void hideAll() {
        keyScroll.setVisibility(GONE);
        trackpad.setVisibility(GONE);
        setCtrl(false);
    }

    /**
     * A pad that turns a finger drag into arrow keys.
     *
     * One arrow per step of movement, so a slow drag moves a character at a time and a fast one
     * covers a line. It is not a pointer -- Monaco would ignore a synthetic pointer anyway --
     * it is a way to press the arrow key many times without hitting a 48 dp target repeatedly.
     */
    private static final class Trackpad extends View {
        private final Target target;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int stepPx;
        private float lastX;
        private float lastY;
        private boolean moved;
        private long downAt;

        Trackpad(Context context, boolean dark, Target target) {
            super(context);
            this.target = target;
            this.stepPx = Ui.dp(context, 14);
            fill.setColor(dark ? Color.rgb(32, 32, 31) : Color.rgb(244, 242, 236));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1, Ui.dp(context, 1)));
            stroke.setColor(Ui.line(dark));
            label.setColor(Ui.muted(dark));
            label.setTextSize(Ui.dp(context, 11));
            label.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = Ui.dp(getContext(), 14);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, fill);
            canvas.drawRoundRect(0.5f, 0.5f, getWidth() - 0.5f, getHeight() - 0.5f,
                    radius, radius, stroke);
            canvas.drawText("Drag to move the cursor · tap to click",
                    getWidth() / 2f, getHeight() / 2f + label.getTextSize() / 3, label);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    moved = false;
                    downAt = SystemClock.uptimeMillis();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    int horizontal = (int) (dx / stepPx);
                    int vertical = (int) (dy / stepPx);
                    if (horizontal != 0) {
                        for (int i = 0; i < Math.min(Math.abs(horizontal), 12); i++) {
                            target.key(horizontal > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT
                                    : KeyEvent.KEYCODE_DPAD_LEFT, 0);
                        }
                        lastX = event.getX();
                        moved = true;
                    }
                    if (vertical != 0) {
                        for (int i = 0; i < Math.min(Math.abs(vertical), 12); i++) {
                            target.key(vertical > 0 ? KeyEvent.KEYCODE_DPAD_DOWN
                                    : KeyEvent.KEYCODE_DPAD_UP, 0);
                        }
                        lastY = event.getY();
                        moved = true;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    // A tap that never moved is a click, which in a text editor means "put the
                    // cursor here" -- and the cursor is already here, so Enter would be wrong.
                    // Nothing is sent; the tap simply ends the drag.
                    if (!moved && SystemClock.uptimeMillis() - downAt < 250) performClick();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
