package com.pocketdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class VncView extends View implements VncClient.Listener {
    enum PointerMode { TOUCHPAD, DIRECT }
    interface StateListener { void state(String text, boolean connected); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF destination = new RectF();
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
    private String status = "Waiting for Linux desktop…";

    /** 1.0 means "as large as the screen allows"; above that the user has zoomed in. */
    private float zoom = 1f;
    private float panX;
    private float panY;
    /** Fill uses the whole screen and lets the edges be panned to; fit shows everything at once. */
    private boolean fillMode = true;
    private boolean centreOnNextLayout = true;
    private ScaleGestureDetector zoomDetector;
    private ZoomListener zoomListener;

    interface ZoomListener { void zoomChanged(int percent, boolean fill); }

    VncView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Local Linux desktop");
        setBackgroundColor(Color.BLACK);
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

    void zoomBy(float factor) {
        float previous = zoom;
        zoom = clampZoom(zoom * factor);
        float ratio = zoom / previous;
        panX = getWidth() / 2f - (getWidth() / 2f - panX) * ratio;
        panY = getHeight() / 2f - (getHeight() / 2f - panY) * ratio;
        notifyZoom();
        invalidate();
    }

    void setFillMode(boolean fill) {
        fillMode = fill;
        resetView();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        // A rotation changes what "fills the screen" means, so re-centre instead of keeping a
        // pan that was clamped against the old size.
        centreOnNextLayout = true;
    }

    boolean isFillMode() { return fillMode; }

    void resetView() {
        zoom = 1f;
        centreOnNextLayout = true;
        notifyZoom();
        invalidate();
    }

    private void notifyZoom() {
        if (zoomListener != null) zoomListener.zoomChanged(Math.round(zoom * 100), fillMode);
    }

    private float clampZoom(float value) {
        return Math.max(minZoom(), Math.min(value, 6f));
    }

    /** The zoom at which the whole desktop is visible; below that there is only empty space. */
    private float minZoom() {
        Bitmap current = bitmap;
        if (current == null || getWidth() == 0 || getHeight() == 0 || !fillMode) return 1f;
        float fit = Math.min(getWidth() / (float) current.getWidth(),
                getHeight() / (float) current.getHeight());
        float fill = Math.max(getWidth() / (float) current.getWidth(),
                getHeight() / (float) current.getHeight());
        return fill <= 0 ? 1f : Math.max(0.2f, fit / fill);
    }

    /** Recomputes where the framebuffer lands on screen for the current zoom, pan and mode. */
    private void layoutDestination(Bitmap current) {
        float fit = Math.min(getWidth() / (float) current.getWidth(),
                getHeight() / (float) current.getHeight());
        float fill = Math.max(getWidth() / (float) current.getWidth(),
                getHeight() / (float) current.getHeight());
        float scale = (fillMode ? fill : fit) * zoom;
        float shownWidth = current.getWidth() * scale;
        float shownHeight = current.getHeight() * scale;

        if (centreOnNextLayout) {
            // Open on the middle of the desktop rather than its top-left corner.
            panX = (getWidth() - shownWidth) / 2f;
            panY = (getHeight() - shownHeight) / 2f;
            centreOnNextLayout = false;
        }

        // Centre whatever is smaller than the screen; otherwise keep the edges flush with it.
        panX = shownWidth <= getWidth()
                ? (getWidth() - shownWidth) / 2f
                : Math.max(getWidth() - shownWidth, Math.min(panX, 0f));
        panY = shownHeight <= getHeight()
                ? (getHeight() - shownHeight) / 2f
                : Math.max(getHeight() - shownHeight, Math.min(panY, 0f));
        destination.set(panX, panY, panX + shownWidth, panY + shownHeight);
    }

    void setClient(VncClient client) { this.client = client; }
    VncClient getClient() { return client; }
    void setStateListener(StateListener listener) { this.stateListener = listener; }
    void setPointerMode(PointerMode mode) { pointerMode = mode; invalidate(); }
    PointerMode getPointerMode() { return pointerMode; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap current = bitmap;
        if (current == null) {
            overlayPaint.setColor(Color.rgb(186, 195, 224));
            overlayPaint.setTextSize(Ui.dp(getContext(), 16));
            canvas.drawText(status, getWidth() / 2f, getHeight() / 2f, overlayPaint);
            return;
        }
        layoutDestination(current);
        canvas.drawBitmap(current, null, destination, paint);

        if (pointerMode == PointerMode.TOUCHPAD) {
            float scale = destination.width() / current.getWidth();
            float px = destination.left + pointerX * scale;
            float py = destination.top + pointerY * scale;
            overlayPaint.setColor(Color.WHITE);
            overlayPaint.setStyle(Paint.Style.STROKE);
            overlayPaint.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            canvas.drawCircle(px, py, Ui.dp(getContext(), 5), overlayPaint);
            overlayPaint.setColor(Color.BLACK);
            canvas.drawCircle(px, py, Ui.dp(getContext(), 3), overlayPaint);
            overlayPaint.setStyle(Paint.Style.FILL);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (isMouse(event)) return handleMouse(event);
        VncClient active = client;
        if (active == null || bitmap == null) return true;
        zoomDetector.onTouchEvent(event);
        int action = event.getActionMasked();

        if (event.getPointerCount() >= 2) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) {
                twoFingerX = averageX(event);
                twoFingerY = averageY(event);
            } else if (action == MotionEvent.ACTION_MOVE && !zoomDetector.isInProgress()) {
                float x = averageX(event);
                float y = averageY(event);
                if (canPan()) {
                    panX += x - twoFingerX;
                    panY += y - twoFingerY;
                    twoFingerX = x;
                    twoFingerY = y;
                    invalidate();
                } else if (Math.abs(y - twoFingerY) > Ui.dp(getContext(), 18)) {
                    int mask = y - twoFingerY < 0 ? 8 : 16;
                    active.sendPointer(pointerX, pointerY, mask);
                    active.sendPointer(pointerX, pointerY, 0);
                    twoFingerY = y;
                }
            }
            return true;
        }
        if (pointerMode == PointerMode.DIRECT) return directTouch(event, action, active);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                downAt = System.currentTimeMillis();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.abs(event.getX() - downX) + Math.abs(event.getY() - downY) > Ui.dp(getContext(), 8)) moved = true;
                pointerX = clamp(pointerX + Math.round(dx * 1.35f), 0, active.getWidth() - 1);
                pointerY = clamp(pointerY + Math.round(dy * 1.35f), 0, active.getHeight() - 1);
                active.sendPointer(pointerX, pointerY, 0);
                lastX = event.getX();
                lastY = event.getY();
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                long duration = System.currentTimeMillis() - downAt;
                if (!moved) {
                    int button = duration >= 550 ? 4 : 1;
                    active.sendPointer(pointerX, pointerY, button);
                    active.sendPointer(pointerX, pointerY, 0);
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (isMouse(event)) return handleMouse(event);
        return super.onGenericMotionEvent(event);
    }

    private boolean handleMouse(MotionEvent event) {
        VncClient active = client;
        Bitmap current = bitmap;
        if (active == null || current == null) return true;
        requestFocus();

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

    private boolean directTouch(MotionEvent event, int action, VncClient active) {
        int x = mapX(event.getX(), active.getWidth());
        int y = mapY(event.getY(), active.getHeight());
        pointerX = x;
        pointerY = y;
        if (action == MotionEvent.ACTION_DOWN) {
            requestFocus();
            active.sendPointer(x, y, 1);
        } else if (action == MotionEvent.ACTION_MOVE) {
            active.sendPointer(x, y, 1);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            active.sendPointer(x, y, 0);
            performClick();
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public void onConnected(int width, int height, String name) {
        main.post(() -> {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            pointerX = width / 2;
            pointerY = height / 2;
            centreOnNextLayout = true;
            status = "Connected";
            if (stateListener != null) stateListener.state("Connected · " + width + "×" + height, true);
            invalidate();
        });
    }

    @Override public void onResize(int width, int height) {
        main.post(() -> {
            Bitmap old = bitmap;
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (old != null) old.recycle();
            pointerX = Math.min(pointerX, width - 1);
            pointerY = Math.min(pointerY, height - 1);
            if (stateListener != null) stateListener.state("Connected · " + width + "×" + height, true);
            invalidate();
        });
    }

    @Override public void onRectangle(int x, int y, int width, int height, int[] pixels) {
        CountDownLatch applied = new CountDownLatch(1);
        main.post(() -> {
            try {
                Bitmap current = bitmap;
                if (current == null || current.isRecycled()) return;
                int safeWidth = Math.min(width, current.getWidth() - x);
                int safeHeight = Math.min(height, current.getHeight() - y);
                if (x >= 0 && y >= 0 && safeWidth > 0 && safeHeight > 0) {
                    current.setPixels(pixels, 0, width, x, y, safeWidth, safeHeight);
                    invalidate();
                }
            } finally {
                applied.countDown();
            }
        });
        try {
            applied.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void onClipboard(String text) {
        main.post(() -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Linux desktop", text));
        });
    }

    @Override public void onDisconnected(String reason) {
        main.post(() -> {
            status = reason == null ? "Disconnected" : reason;
            if (stateListener != null) stateListener.state(status, false);
            invalidate();
        });
    }

    private int mapX(float viewX, int remoteWidth) {
        if (destination.width() <= 0) return 0;
        return clamp(Math.round((viewX - destination.left) * remoteWidth / destination.width()), 0, remoteWidth - 1);
    }

    private int mapY(float viewY, int remoteHeight) {
        if (destination.height() <= 0) return 0;
        return clamp(Math.round((viewY - destination.top) * remoteHeight / destination.height()), 0, remoteHeight - 1);
    }

    /** True once the picture is larger than the screen, so dragging it has somewhere to go. */
    private boolean canPan() {
        return destination.width() > getWidth() + 1f || destination.height() > getHeight() + 1f;
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
