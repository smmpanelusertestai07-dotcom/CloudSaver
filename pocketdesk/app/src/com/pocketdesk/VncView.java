package com.pocketdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;

import java.util.ArrayList;
import java.util.List;


final class VncView extends View implements VncClient.Listener {
    enum PointerMode { TOUCHPAD, DIRECT }
    interface StateListener { void state(String text, boolean connected); }

    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * When the desktop was last touched or typed on. Smart auto-stop reads it: a session being
     * worked in should never be closed by a clock, and one nobody is using should not run on.
     */
    static volatile long lastInteractionAt;

    /** Guards the front bitmap between the blit at the end of an update and the drawing pass. */
    private final Object pixelLock = new Object();
    /** Guards the back bitmap, which only the network thread writes, against a resize. */
    private final Object backLock = new Object();
    /**
     * Two copies of the desktop. The network thread writes each strip of an update into the
     * back one; when the whole update has arrived it is copied, in one go, to the front one
     * that onDraw paints. Painting used to happen while strips were still landing, so a frame
     * on screen was half old and half new -- the tearing seen whenever something scrolled.
     */
    private Bitmap back;
    private Canvas frontCanvas;
    private final android.graphics.Rect dirty = new android.graphics.Rect();
    private boolean anyDirty;
    /** The pointer's own shape, as the desktop reports it; null until it sends one. */
    private Bitmap cursorBitmap;
    private int cursorHotX;
    private int cursorHotY;
    private android.graphics.drawable.Drawable handGlyph;
    private final android.graphics.Rect cursorSource = new android.graphics.Rect();
    private final RectF cursorTarget = new RectF();
    // Finger mode: a fast swipe keeps scrolling after the finger lifts, as every phone page
    // does, by sending wheel notches while a decaying velocity runs down.
    private VelocityTracker velocity;
    private float flingVelocity;
    private float flingTravel;
    private final RectF spinnerBounds = new RectF();
    private float ringX, ringY;
    private long ringAt = -10_000L;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
    private final android.graphics.Path pointerPath = new android.graphics.Path();
    private Bitmap bitmap;
    private VncClient client;
    private StateListener stateListener;
    private PointerMode pointerMode = PointerMode.TOUCHPAD;
    private int pointerX = 640;
    private int pointerY = 360;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private long downAt;
    private boolean moved;
    private float twoFingerY;
    private float twoFingerX;
    private String status = "Waiting for the Linux computer…";

    /** 1.0 means "the whole desktop fits the screen"; above that the user has zoomed in. */
    private float zoom = 1f;
    private float panX;
    private float panY;
    private boolean centreOnNextLayout = true;
    private ScaleGestureDetector zoomDetector;
    private ZoomListener zoomListener;

    // Mouse mode dragging: a tap followed at once by a press-and-move holds the left button,
    // the way every laptop touchpad does it. Windows can be moved and text selected on purpose.
    private long lastTapAt = -10_000L;
    private boolean dragArmed;
    private boolean dragging;

    interface ZoomListener { void zoomChanged(int percent); }

    VncView(Context context) {
        super(context);
        // Not focusable by touch: the phone keyboard types into a hidden field, and a view that
        // took focus on every tap restarted the keyboard against a bare fallback connection --
        // letters were dropped and the keyboard went full-screen in landscape.
        setFocusable(false);
        setFocusableInTouchMode(false);
        setContentDescription("Linux computer");
        // A deep, calm backdrop, so at 100 % the framed desktop sits on colour, not black.
        setBackgroundColor(Color.rgb(9, 14, 26));
        overlayPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        overlayPaint.setTextAlign(Paint.Align.CENTER);
        zoomDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float previous = zoom;
                zoom = clampZoom(zoom * detector.getScaleFactor());
                // Keep the point under the fingers still while the picture grows around it.
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float ratio = zoom / previous;
                panX = focusX - (focusX - panX) * ratio;
                panY = focusY - (focusY - panY) * ratio;
                notifyZoom();
                invalidate();
                return true;
            }
        });
    }

    void setZoomListener(ZoomListener listener) {
        zoomListener = listener;
        notifyZoom();
    }

    /** Zooms around the middle of the screen. Returns false when already at the limit. */
    boolean zoomBy(float factor) {
        float previous = zoom;
        zoom = clampZoom(zoom * factor);
        if (zoom == previous) return false;
        float ratio = zoom / previous;
        panX = getWidth() / 2f - (getWidth() / 2f - panX) * ratio;
        panY = getHeight() / 2f - (getHeight() / 2f - panY) * ratio;
        notifyZoom();
        invalidate();
        return true;
    }

    int zoomPercent() { return Math.round(zoom * 100); }

    /** The view size the Linux desktop was last matched to, so a bar is not a new screen. */
    private int matchedWidth;
    private int matchedHeight;

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // Showing the key row or hiding the bars changes this view's height by 56 dp, and that
        // used to resize the whole Linux desktop and throw away the owner's zoom mid-task. Only
        // a genuine change of screen shape does that; a bar is just less of the same screen.
        boolean firstLayout = matchedWidth == 0 || matchedHeight == 0;
        // A rotation changes the WIDTH (and swaps which side is longer). A bar or the key row
        // only ever changes the height, by 56 dp each. Comparing the longer and shorter sides
        // was blind to a rotation, because 720x1600 and 1600x720 have the same pair.
        int barsHeight = Ui.dp(getContext(), 56 + 56 + 8);
        boolean shapeChanged = firstLayout
                || width != matchedWidth
                || Math.abs(height - matchedHeight) > barsHeight;
        if (!shapeChanged) {
            centreOnNextLayout = false;
            invalidate();               // same desktop, less room: just redraw where it fits
            return;
        }
        matchedWidth = width;
        matchedHeight = height;
        zoom = 1f;
        centreOnNextLayout = true;
        notifyZoom();
        matchDesktopToScreen();
    }

    /** Height of the on-screen keyboard covering the bottom of this view, 0 when hidden. */
    private int keyboardInset;

    /**
     * The keyboard must never resize the Linux desktop. When it opened, the window shrank, the
     * desktop was resized to the sliver above it, every app relaid out, and a tap on a text
     * field landed somewhere else -- then it all happened again in reverse when it closed. The
     * window keeps its size now, and the view slides up just enough to keep the pointer above
     * the keys, exactly as a phone screen scrolls to a text field.
     */
    void setKeyboardInset(int pixels) {
        if (pixels == keyboardInset) return;
        keyboardInset = Math.max(0, pixels);
        Bitmap current = bitmap;
        if (current == null) { invalidate(); return; }
        if (keyboardInset > 0) {
            float scale = destination.width() / current.getWidth();
            float pointerScreenY = destination.top + pointerY * scale;
            float visibleBottom = getHeight() - keyboardInset - Ui.dp(getContext(), 72);
            if (pointerScreenY > visibleBottom) panY -= (pointerScreenY - visibleBottom);
        } else {
            centreOnNextLayout = true;
        }
        invalidate();
    }

    boolean isKeyboardShowing() { return keyboardInset > 0; }

    /**
     * Asks the Linux desktop to become exactly the size of this view.
     *
     * With the two matched there is nothing to letterbox or crop: portrait gives a portrait
     * desktop and landscape a landscape one, both filling the screen pixel for pixel. Debounced,
     * because a rotation delivers several size changes in a row.
     */
    private void matchDesktopToScreen() {
        main.removeCallbacks(desktopResize);
        main.postDelayed(desktopResize, 450L);
    }

    private final Runnable desktopResize = new Runnable() {
        @Override public void run() {
            VncClient active = client;
            if (active == null || !active.isResizable()) return;
            int viewWidth = getWidth();
            int viewHeight = getHeight();
            if (viewWidth < 320 || viewHeight < 320) return;
            long pixels = (long) viewWidth * viewHeight;
            if (pixels > MAX_DESKTOP_PIXELS) {
                double shrink = Math.sqrt(MAX_DESKTOP_PIXELS / (double) pixels);
                viewWidth = (int) Math.round(viewWidth * shrink);
                viewHeight = (int) Math.round(viewHeight * shrink);
            }
            viewWidth -= viewWidth % 2;
            viewHeight -= viewHeight % 2;
            if (viewWidth == active.getWidth() && viewHeight == active.getHeight()) return;
            active.requestDesktopSize(viewWidth, viewHeight);
        }
    };

    private static final long MAX_DESKTOP_PIXELS = 2_300_000L;

    /** Back to 100 %: the whole desktop on screen, centred. */
    void resetView() {
        zoom = 1f;
        centreOnNextLayout = true;
        notifyZoom();
        invalidate();
    }

    private void notifyZoom() {
        if (zoomListener != null) zoomListener.zoomChanged(Math.round(zoom * 100));
    }

    /**
     * Never below 1: at 100 % the whole desktop is already on screen (the desktop is kept the
     * size of the screen, and when it cannot be, it is letterboxed rather than cropped), so
     * there is nothing smaller to show. Fill-and-crop was removed: it hid the right-hand edge
     * of every window, close button included, in portrait.
     */
    private float clampZoom(float value) {
        return Math.max(1f, Math.min(value, 6f));
    }

    /** The gap kept around the desktop at 100 %, so it reads as a screen on a desk, not a crop. */
    private int frame() { return Ui.dp(getContext(), 7); }

    /** Recomputes where the framebuffer lands on screen for the current zoom and pan. */
    private void layoutDestination(Bitmap current) {
        int m = frame();
        float availW = Math.max(1f, getWidth() - 2f * m);
        float availH = Math.max(1f, getHeight() - 2f * m);
        float fit = Math.min(availW / current.getWidth(), availH / current.getHeight());
        float scale = fit * zoom;
        float shownWidth = current.getWidth() * scale;
        float shownHeight = current.getHeight() * scale;

        if (centreOnNextLayout) {
            // Open on the middle of the desktop rather than its top-left corner.
            panX = (getWidth() - shownWidth) / 2f;
            panY = (getHeight() - shownHeight) / 2f;
            centreOnNextLayout = false;
        }

        // Centre whatever is smaller than the screen; otherwise keep the edges flush with it.
        // While the keyboard is up the view may also sit higher by up to the keyboard's height,
        // so the field being typed into can be brought out from under it.
        panX = shownWidth <= getWidth()
                ? (getWidth() - shownWidth) / 2f
                : Math.max(getWidth() - shownWidth, Math.min(panX, 0f));
        if (shownHeight <= getHeight()) {
            float centred = (getHeight() - shownHeight) / 2f;
            panY = keyboardInset > 0 ? Math.max(centred - keyboardInset, Math.min(panY, centred)) : centred;
        } else {
            panY = Math.max(getHeight() - shownHeight - keyboardInset, Math.min(panY, 0f));
        }
        destination.set(panX, panY, panX + shownWidth, panY + shownHeight);
    }

    void setClient(VncClient client) { this.client = client; }
    VncClient getClient() { return client; }
    void setStateListener(StateListener listener) { this.stateListener = listener; }
    void setPointerMode(PointerMode mode) { pointerMode = mode; stopFling(); invalidate(); }
    PointerMode getPointerMode() { return pointerMode; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap current = bitmap;
        if (current == null) {
            drawWaiting(canvas);
            return;
        }
        layoutDestination(current);
        synchronized (pixelLock) {
            if (current.isRecycled()) return;
            canvas.drawBitmap(current, null, destination, paint);
        }
        // A session that has ended keeps its last frame on screen, which looked exactly like a
        // working desktop that had stopped answering. Dim it and say so.
        if (!live && everConnected) {
            overlayPaint.setStyle(Paint.Style.FILL);
            overlayPaint.setColor(Color.argb(175, 6, 9, 20));
            canvas.drawRect(destination, overlayPaint);
            overlayPaint.setColor(Color.rgb(226, 232, 248));
            overlayPaint.setTextSize(Ui.dp(getContext(), 15));
            overlayPaint.setTextAlign(Paint.Align.CENTER);
            float centreX = destination.centerX();
            float centreY = destination.centerY();
            canvas.drawText("The computer stopped", centreX, centreY - Ui.dp(getContext(), 6), overlayPaint);
            overlayPaint.setTextSize(Ui.dp(getContext(), 12.5f));
            overlayPaint.setColor(Color.rgb(150, 166, 205));
            canvas.drawText("Tap Back, then Open desktop to start it again",
                    centreX, centreY + Ui.dp(getContext(), 16), overlayPaint);
            overlayPaint.setTextAlign(Paint.Align.LEFT);
        }
        // A thin rounded border around the desktop, in both orientations, so the framed edge
        // is deliberate rather than a picture that ran off the screen.
        float r = Ui.dp(getContext(), 6);
        float bw = Ui.dp(getContext(), 1.5f);
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(bw);
        overlayPaint.setColor(Color.argb(150, 122, 155, 255));
        canvas.drawRoundRect(destination.left - bw, destination.top - bw,
                destination.right + bw, destination.bottom + bw, r, r, overlayPaint);
        overlayPaint.setStyle(Paint.Style.FILL);

        float scale = destination.width() / current.getWidth();
        float px = destination.left + pointerX * scale;
        float py = destination.top + pointerY * scale;
        if (pointerMode == PointerMode.TOUCHPAD) {
            // Mouse mode shows the pointer the desktop itself is showing -- an I-beam over text,
            // a hand over a link, a resize arrow at an edge -- and an arrow until it says.
            Bitmap shape = cursorBitmap;
            if (shape != null && !shape.isRecycled()) {
                float grow = Math.max(scale, 1f);
                cursorSource.set(0, 0, shape.getWidth(), shape.getHeight());
                cursorTarget.set(px - cursorHotX * grow, py - cursorHotY * grow,
                        px + (shape.getWidth() - cursorHotX) * grow, py + (shape.getHeight() - cursorHotY) * grow);
                canvas.drawBitmap(shape, cursorSource, cursorTarget, paint);
            } else {
                drawPointer(canvas, px, py);
            }
        } else {
            // Finger mode: a hand where the pointer is, so what the desktop thinks is "under
            // the pointer" is visible; and a ring where the tap landed, fading over a third
            // of a second, so a tap on a small control visibly went where it was meant to.
            drawHand(canvas, px, py);
            long age = SystemClock.elapsedRealtime() - ringAt;
            if (age < 320) {
                float t = age / 320f;
                overlayPaint.setStyle(Paint.Style.STROKE);
                overlayPaint.setStrokeWidth(Ui.dp(getContext(), 2));
                overlayPaint.setColor(Color.argb((int) (200 * (1 - t)), 122, 155, 255));
                canvas.drawCircle(ringX, ringY, Ui.dp(getContext(), 14 + 22 * t), overlayPaint);
                overlayPaint.setStyle(Paint.Style.FILL);
                postInvalidateOnAnimation();
            }
        }
    }

    /** The hand from the toolbar's Finger button, its fingertip on the pointer, dark-edged. */
    private void drawHand(Canvas canvas, float x, float y) {
        if (handGlyph == null) {
            android.graphics.drawable.Drawable glyph = getContext().getDrawable(R.drawable.ic_touch);
            if (glyph == null) return;
            handGlyph = glyph.mutate();
        }
        int size = Ui.dp(getContext(), 28);
        int edge = Math.max(1, Ui.dp(getContext(), 1.5f));
        int left = Math.round(x - size * 0.48f);
        int top = Math.round(y - size * 0.1f);
        handGlyph.setTint(Color.argb(170, 0, 0, 0));
        handGlyph.setBounds(left - edge, top - edge, left + size + edge, top + size + edge);
        handGlyph.draw(canvas);
        handGlyph.setTint(Color.WHITE);
        handGlyph.setBounds(left, top, left + size, top + size);
        handGlyph.draw(canvas);
    }

    /** A real arrow, outlined in dark so it stays visible against any wallpaper. */
    private void drawPointer(Canvas canvas, float x, float y) {
        float unit = Ui.dp(getContext(), 1);
        pointerPath.reset();
        pointerPath.moveTo(x, y);
        pointerPath.lineTo(x, y + 17 * unit);
        pointerPath.lineTo(x + 4.4f * unit, y + 13.2f * unit);
        pointerPath.lineTo(x + 7.2f * unit, y + 19.4f * unit);
        pointerPath.lineTo(x + 10.2f * unit, y + 18f * unit);
        pointerPath.lineTo(x + 7.4f * unit, y + 12f * unit);
        pointerPath.lineTo(x + 12.6f * unit, y + 11.8f * unit);
        pointerPath.close();
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(2.4f * unit);
        overlayPaint.setColor(Color.argb(220, 0, 0, 0));
        canvas.drawPath(pointerPath, overlayPaint);
        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(dragging ? Color.rgb(160, 190, 255) : Color.WHITE);
        canvas.drawPath(pointerPath, overlayPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        lastInteractionAt = System.currentTimeMillis();
        if (isMouse(event)) return handleMouse(event);
        VncClient active = client;
        if (active == null || bitmap == null) return true;
        zoomDetector.onTouchEvent(event);
        int action = event.getActionMasked();

        if (event.getPointerCount() >= 2) {
            // A second finger ends any one-finger gesture: nothing is held or dragged.
            if (dragging) {
                dragging = false;
                active.sendPointer(pointerX, pointerY, 0);
            }
            dragArmed = false;
            moved = true;
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) {
                twoFingerX = averageX(event);
                twoFingerY = averageY(event);
                // Scroll the window under the fingers, as a phone does: X sends the wheel to
                // whatever is under the pointer, so the pointer goes there first.
                pointerX = mapX(twoFingerX, active.getWidth());
                pointerY = mapY(twoFingerY, active.getHeight());
                active.sendPointer(pointerX, pointerY, 0);
            } else if (action == MotionEvent.ACTION_MOVE && !zoomDetector.isInProgress()) {
                float x = averageX(event);
                float y = averageY(event);
                // Two fingers always scroll in Mouse mode -- the arrow is what moves the view
                // when zoomed in. In Finger mode one finger already scrolls, so two fingers
                // pan a zoomed-in picture instead.
                if (pointerMode == PointerMode.DIRECT && zoomedIn()) {
                    panX += x - twoFingerX;
                    panY += y - twoFingerY;
                    twoFingerX = x;
                    twoFingerY = y;
                    invalidate();
                } else {
                    // Fingers up, content up: wheel down. Several notches for a fast swipe.
                    int notch = Ui.dp(getContext(), 16);
                    float dy = y - twoFingerY;
                    float dx = x - twoFingerX;
                    if (Math.abs(dy) >= Math.abs(dx)) {
                        while (dy <= -notch) { wheel(active, 16); twoFingerY -= notch; dy += notch; }
                        while (dy >= notch) { wheel(active, 8); twoFingerY += notch; dy -= notch; }
                        if (Math.abs(dy) < notch) twoFingerX = x;
                    } else {
                        while (dx <= -notch) { wheel(active, 64); twoFingerX -= notch; dx += notch; }
                        while (dx >= notch) { wheel(active, 32); twoFingerX += notch; dx -= notch; }
                        twoFingerY = y;
                    }
                }
            }
            return true;
        }
        if (pointerMode == PointerMode.DIRECT) return directTouch(event, action, active);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                downAt = System.currentTimeMillis();
                moved = false;
                dragging = false;
                dragArmed = downAt - lastTapAt < 300L;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) > Ui.dp(getContext(), 8)) moved = true;
                if (moved && dragArmed && !dragging) {
                    dragging = true;
                    active.sendPointer(pointerX, pointerY, 1);
                }
                pointerX = clamp(pointerX + Math.round(dx * 1.35f), 0, active.getWidth() - 1);
                pointerY = clamp(pointerY + Math.round(dy * 1.35f), 0, active.getHeight() - 1);
                active.sendPointer(pointerX, pointerY, dragging ? 1 : 0);
                lastX = event.getX();
                lastY = event.getY();
                followPointer();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                long duration = System.currentTimeMillis() - downAt;
                if (dragging) {
                    dragging = false;
                    active.sendPointer(pointerX, pointerY, 0);
                    invalidate();
                } else if (!moved && action == MotionEvent.ACTION_UP) {
                    int button = duration >= 550 ? 4 : 1;
                    active.sendPointer(pointerX, pointerY, button);
                    active.sendPointer(pointerX, pointerY, 0);
                    lastTapAt = button == 1 ? System.currentTimeMillis() : -10_000L;
                    performClick();
                }
                dragArmed = false;
                return true;
            }
            default:
                return true;
        }
    }

    /** One wheel click, in the direction the mask names (8 up, 16 down, 32 left, 64 right). */
    private void wheel(VncClient active, int mask) {
        active.sendPointer(pointerX, pointerY, mask);
        active.sendPointer(pointerX, pointerY, 0);
    }

    /**
     * Mouse mode, zoomed in: the picture slides so the arrow never leaves the screen. Without
     * this the arrow ran off the visible part and there was no way to scroll after it.
     */
    private void followPointer() {
        Bitmap current = bitmap;
        if (current == null || !zoomedIn()) return;
        float scale = destination.width() / current.getWidth();
        float x = destination.left + pointerX * scale;
        float y = destination.top + pointerY * scale;
        float margin = Ui.dp(getContext(), 40);
        if (x < margin) panX += margin - x;
        else if (x > getWidth() - margin) panX -= x - (getWidth() - margin);
        float bottom = getHeight() - keyboardInset;
        if (y < margin) panY += margin - y;
        else if (y > bottom - margin) panY -= y - (bottom - margin);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouse(event)) return handleMouse(event);
        return super.onGenericMotionEvent(event);
    }

    private boolean handleMouse(MotionEvent event) {
        // A paired mouse is the owner too: moving, clicking and scrolling all arrive here.
        lastInteractionAt = System.currentTimeMillis();
        VncClient active = client;
        Bitmap current = bitmap;
        if (active == null || current == null) return true;

        float relativeX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float relativeY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
                && (relativeX != 0 || relativeY != 0)) {
            pointerX = clamp(pointerX + Math.round(relativeX), 0, active.getWidth() - 1);
            pointerY = clamp(pointerY + Math.round(relativeY), 0, active.getHeight() - 1);
        } else {
            pointerX = mapX(event.getX(), active.getWidth());
            pointerY = mapY(event.getY(), active.getHeight());
        }

        int buttons = mouseButtons(event.getButtonState());
        int changedButton = mouseButtons(event.getActionButton());
        if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_PRESS) buttons |= changedButton;
        else if (event.getActionMasked() == MotionEvent.ACTION_BUTTON_RELEASE) buttons &= ~changedButton;
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            sendWheel(active, buttons, event.getAxisValue(MotionEvent.AXIS_VSCROLL), 8, 16);
            sendWheel(active, buttons, event.getAxisValue(MotionEvent.AXIS_HSCROLL), 64, 32);
        } else {
            active.sendPointer(pointerX, pointerY, buttons);
        }
        followPointer();
        invalidate();
        return true;
    }

    private void sendWheel(VncClient active, int buttons, float amount, int positiveMask, int negativeMask) {
        if (amount == 0) return;
        int steps = Math.max(1, Math.min(6, Math.round(Math.abs(amount))));
        int wheel = amount > 0 ? positiveMask : negativeMask;
        for (int i = 0; i < steps; i++) {
            active.sendPointer(pointerX, pointerY, buttons | wheel);
            active.sendPointer(pointerX, pointerY, buttons);
        }
    }

    private static int mouseButtons(int state) {
        int result = 0;
        if ((state & MotionEvent.BUTTON_PRIMARY) != 0) result |= 1;
        if ((state & MotionEvent.BUTTON_TERTIARY) != 0) result |= 2;
        if ((state & MotionEvent.BUTTON_SECONDARY) != 0) result |= 4;
        return result;
    }

    private static boolean isMouse(MotionEvent event) {
        return event.isFromSource(InputDevice.SOURCE_MOUSE)
                || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
    }

    /**
     * Finger mode, behaving the way a phone behaves.
     *
     * The old version pressed the left button the instant a finger landed, so the slightest
     * wobble became a drag -- icons moved, text selected, windows tore around -- and scrolling
     * did not exist. Now a tap is a click where the finger first landed, holding still for half
     * a second is a right-click, and a swipe turns the scroll wheel like every phone screen.
     * Precise dragging is what Mouse mode is for.
     */
    private boolean directTouch(MotionEvent event, int action, VncClient active) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                stopFling();
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(event);
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                downAt = System.currentTimeMillis();
                moved = false;
                pointerX = mapX(downX, active.getWidth());
                pointerY = mapY(downY, active.getHeight());
                active.sendPointer(pointerX, pointerY, 0);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(event);
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY)
                        > Ui.dp(getContext(), 10)) {
                    moved = true;
                }
                if (!moved) return true;
                // A swipe is a scroll, in the direction the content moves on any phone screen.
                float travelled = event.getY() - lastY;
                int notch = Ui.dp(getContext(), 16);
                while (travelled <= -notch) {
                    wheel(active, 16);
                    lastY -= notch;
                    travelled += notch;
                }
                while (travelled >= notch) {
                    wheel(active, 8);
                    lastY += notch;
                    travelled -= notch;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!moved && action == MotionEvent.ACTION_UP) {
                    // Click where the finger first landed: the touch point, not the lift wobble.
                    int button = System.currentTimeMillis() - downAt >= 500 ? 4 : 1;
                    active.sendPointer(pointerX, pointerY, button);
                    active.sendPointer(pointerX, pointerY, 0);
                    ringX = downX; ringY = downY; ringAt = SystemClock.elapsedRealtime();
                    postInvalidateOnAnimation();
                    performClick();
                } else if (moved && action == MotionEvent.ACTION_UP && velocity != null) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000);
                    float speed = velocity.getYVelocity();
                    if (Math.abs(speed) > Ui.dp(getContext(), 600)) startFling(speed);
                }
                return true;
            default:
                return true;
        }
    }

    /** Keeps a fast swipe scrolling after the finger lifts, slowing down as a phone page does. */
    private void startFling(float pixelsPerSecond) {
        flingVelocity = pixelsPerSecond;
        flingTravel = 0f;
        main.removeCallbacks(flingStep);
        main.postDelayed(flingStep, 16L);
    }

    private void stopFling() {
        flingVelocity = 0f;
        flingTravel = 0f;
        main.removeCallbacks(flingStep);
    }

    private final Runnable flingStep = new Runnable() {
        @Override public void run() {
            VncClient active = client;
            if (active == null || pointerMode != PointerMode.DIRECT) { stopFling(); return; }
            flingTravel += flingVelocity * 0.016f;
            flingVelocity *= 0.94f;
            int notch = Ui.dp(getContext(), 16);
            // Finger moving down carried the content down: wheel up. And the reverse.
            while (flingTravel >= notch) { wheel(active, 8); flingTravel -= notch; }
            while (flingTravel <= -notch) { wheel(active, 16); flingTravel += notch; }
            if (Math.abs(flingVelocity) < Ui.dp(getContext(), 40)) { stopFling(); return; }
            main.postDelayed(this, 16L);
        }
    };

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public void onConnected(int width, int height, String name) {
        main.post(() -> {
            live = true;
            everConnected = true;
            replaceBitmap(width, height);
            pointerX = width / 2;
            pointerY = height / 2;
            // Tell Linux where the pointer is, or its own cursor stays wherever the last session
            // (or the last screen size) left it -- which is the second arrow at the screen edge.
            VncClient active = client;
            if (active != null) active.sendPointer(pointerX, pointerY, 0);
            centreOnNextLayout = true;
            status = "Connected";
            matchDesktopToScreen();
            if (stateListener != null) stateListener.state("Linux computer", true);
            invalidate();
        });
    }

    @Override public void onResize(int width, int height) {
        main.post(() -> {
            live = true;
            replaceBitmap(width, height);
            centreOnNextLayout = true;
            pointerX = Math.min(pointerX, width - 1);
            pointerY = Math.min(pointerY, height - 1);
            VncClient active = client;
            if (active != null) active.sendPointer(pointerX, pointerY, 0);
            if (stateListener != null) stateListener.state("Linux computer", true);
            invalidate();
        });
    }

    /** The desktop's current size in pixels, for the details the status label opens. */
    String desktopSize() {
        Bitmap current = bitmap;
        return current == null ? "not connected yet" : current.getWidth() + " × " + current.getHeight();
    }

    @Override public void onRectangle(int x, int y, int width, int height, int[] pixels) {
        // Written straight from the network thread into the back copy; nothing is shown until
        // onUpdateComplete says the whole update has landed.
        synchronized (backLock) {
            Bitmap target = back;
            if (target == null || target.isRecycled()) return;
            int safeWidth = Math.min(width, target.getWidth() - x);
            int safeHeight = Math.min(height, target.getHeight() - y);
            if (x < 0 || y < 0 || safeWidth <= 0 || safeHeight <= 0) return;
            long needed = (long) (safeHeight - 1) * width + safeWidth;
            if (needed > pixels.length) return;
            target.setPixels(pixels, 0, width, x, y, safeWidth, safeHeight);
            if (anyDirty) dirty.union(x, y, x + safeWidth, y + safeHeight);
            else { dirty.set(x, y, x + safeWidth, y + safeHeight); anyDirty = true; }
        }
    }

    @Override public void onUpdateComplete() {
        synchronized (backLock) {
            if (!anyDirty) return;
            anyDirty = false;
            Bitmap source = back;
            synchronized (pixelLock) {
                Bitmap front = bitmap;
                if (front == null || source == null || front.isRecycled() || source.isRecycled()) return;
                if (frontCanvas == null) frontCanvas = new Canvas(front);
                // One blit of the changed area: the front copy is never half an update.
                frontCanvas.drawBitmap(source, dirty, dirty, null);
            }
        }
        postInvalidate();
    }

    @Override public void onCursor(int hotX, int hotY, int width, int height, int[] argb) {
        final Bitmap shape = width > 0 && height > 0 && argb != null
                ? Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888) : null;
        main.post(() -> {
            Bitmap old = cursorBitmap;
            cursorBitmap = shape;
            cursorHotX = hotX;
            cursorHotY = hotY;
            if (old != null) old.recycle();
            invalidate();
        });
    }

    /**
     * Text the Linux side copied. Only a real copy arrives here now (the display server no
     * longer forwards every highlighted word), so the phone's "Copied" bubble appears when
     * something was actually copied and not whenever text was selected.
     */
    private String lastClip;

    @Override public void onClipboard(String text) {
        if (text == null || text.isEmpty() || text.equals(lastClip)) return;
        lastClip = text;
        main.post(() -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Linux computer", text));
        });
    }

    @Override public void onDisconnected(String reason) {
        main.post(() -> {
            status = reason == null ? "Disconnected" : reason;
            live = false;
            if (stateListener != null) stateListener.state(status, false);
            invalidate();
        });
    }

    /** True while frames are still arriving. A dead session must not look like a live one. */
    private boolean live;
    /** Set once a session has really connected: before that, "stopped" would be wrong. */
    private boolean everConnected;

    /**
     * The screen shown while the desktop is still coming up.
     *
     * A single centred line of text ran off both edges of a phone screen, so a sentence that
     * said how long the wait had been read as a fragment. This wraps, and turns while it waits,
     * so the wait looks like a wait rather than a hang.
     */
    private void drawWaiting(Canvas canvas) {
        Context context = getContext();
        canvas.drawColor(Color.rgb(9, 13, 26));

        float cardWidth = Math.min(getWidth() - Ui.dp(context, 48), Ui.dp(context, 340));
        float centreX = getWidth() / 2f;
        float centreY = getHeight() / 2f;

        overlayPaint.setTextSize(Ui.dp(context, 15));
        overlayPaint.setTextAlign(Paint.Align.CENTER);
        List<String> lines = wrap(status, cardWidth - Ui.dp(context, 32));
        float lineHeight = overlayPaint.getFontSpacing();
        float spinner = Ui.dp(context, 18);
        float cardHeight = spinner * 2 + Ui.dp(context, 34) + lines.size() * lineHeight
                + Ui.dp(context, 32);

        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(Color.rgb(17, 24, 44));
        canvas.drawRoundRect(centreX - cardWidth / 2f, centreY - cardHeight / 2f,
                centreX + cardWidth / 2f, centreY + cardHeight / 2f,
                Ui.dp(context, 18), Ui.dp(context, 18), overlayPaint);

        float spinnerY = centreY - cardHeight / 2f + Ui.dp(context, 26) + spinner;
        spinnerBounds.set(centreX - spinner, spinnerY - spinner, centreX + spinner, spinnerY + spinner);
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(Ui.dp(context, 3));
        overlayPaint.setStrokeCap(Paint.Cap.ROUND);
        overlayPaint.setColor(Color.rgb(31, 43, 78));
        canvas.drawArc(spinnerBounds, 0, 360, false, overlayPaint);
        overlayPaint.setColor(Color.rgb(122, 155, 255));
        float sweepStart = (SystemClock.elapsedRealtime() / 3L) % 360L;
        canvas.drawArc(spinnerBounds, sweepStart, 90, false, overlayPaint);

        overlayPaint.setStyle(Paint.Style.FILL);
        overlayPaint.setColor(Color.rgb(214, 222, 245));
        float textY = spinnerY + spinner + Ui.dp(context, 26);
        for (String line : lines) {
            canvas.drawText(line, centreX, textY, overlayPaint);
            textY += lineHeight;
        }
        postInvalidateOnAnimation();
    }

    /** Greedy word wrap, so a long sentence stays inside the card instead of past the screen. */
    private List<String> wrap(String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (overlayPaint.measureText(candidate) <= maxWidth || line.length() == 0) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private void replaceBitmap(int width, int height) {
        // A pointer parked at a stale coordinate lands in a corner on a phone-shaped desktop.
        // The middle is where a mouse pointer belongs when a screen first appears.
        pointerX = width / 2;
        pointerY = height / 2;
        synchronized (backLock) {
            synchronized (pixelLock) {
                Bitmap oldFront = bitmap;
                Bitmap oldBack = back;
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                back = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                frontCanvas = null;
                anyDirty = false;
                if (oldFront != null) oldFront.recycle();
                if (oldBack != null) oldBack.recycle();
            }
        }
    }

    private int mapX(float viewX, int remoteWidth) {
        if (destination.width() <= 0) return 0;
        return clamp(Math.round((viewX - destination.left) * remoteWidth / destination.width()), 0, remoteWidth - 1);
    }

    private int mapY(float viewY, int remoteHeight) {
        if (destination.height() <= 0) return 0;
        return clamp(Math.round((viewY - destination.top) * remoteHeight / destination.height()), 0, remoteHeight - 1);
    }

    /** Clearly zoomed in, not merely a few pixels over: only then is there anywhere to pan. */
    private boolean zoomedIn() {
        return destination.width() > getWidth() * 1.15f || destination.height() > getHeight() * 1.15f;
    }

    private static float averageX(MotionEvent event) {
        float total = 0;
        for (int i = 0; i < event.getPointerCount(); i++) total += event.getX(i);
        return total / event.getPointerCount();
    }

    private static float averageY(MotionEvent event) {
        float total = 0;
        for (int i = 0; i < event.getPointerCount(); i++) total += event.getY(i);
        return total / event.getPointerCount();
    }

    private static int clamp(int value, int minimum, int maximum) {
        if (maximum < minimum) return minimum;
        return Math.max(minimum, Math.min(value, maximum));
    }
}
