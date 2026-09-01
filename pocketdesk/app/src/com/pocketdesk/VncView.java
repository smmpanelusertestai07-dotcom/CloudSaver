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
    private String status = "Waiting for Linux desktop…";

    VncView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setContentDescription("Local Linux desktop");
        setBackgroundColor(Color.BLACK);
        overlayPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        overlayPaint.setTextAlign(Paint.Align.CENTER);
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
        float scale = Math.min(getWidth() / (float) current.getWidth(), getHeight() / (float) current.getHeight());
        float shownWidth = current.getWidth() * scale;
        float shownHeight = current.getHeight() * scale;
        float left = (getWidth() - shownWidth) / 2f;
        float top = (getHeight() - shownHeight) / 2f;
        destination.set(left, top, left + shownWidth, top + shownHeight);
        canvas.drawBitmap(current, null, destination, paint);

        if (pointerMode == PointerMode.TOUCHPAD) {
            float px = left + pointerX * scale;
            float py = top + pointerY * scale;
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
        int action = event.getActionMasked();
        if (pointerMode == PointerMode.DIRECT) return directTouch(event, action, active);

        if (event.getPointerCount() >= 2) {
            if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN) {
                twoFingerY = averageY(event);
            } else if (action == MotionEvent.ACTION_MOVE) {
                float y = averageY(event);
                float delta = y - twoFingerY;
                if (Math.abs(delta) > Ui.dp(getContext(), 18)) {
                    int mask = delta < 0 ? 8 : 16;
                    active.sendPointer(pointerX, pointerY, mask);
                    active.sendPointer(pointerX, pointerY, 0);
                    twoFingerY = y;
                }
            }
            return true;
        }

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
