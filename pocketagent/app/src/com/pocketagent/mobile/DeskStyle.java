package com.pocketagent.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.Window;

/** Neutral native surfaces, Android typography and a persisted device-local appearance. */
final class DeskStyle {
    static int BG = Ui.DARK_BG, SURFACE = Ui.DARK_CARD, FIELD = Ui.DARK_FIELD, LINE = Ui.DARK_LINE;
    static int TEXT = Ui.DARK_TEXT, MUTED = Ui.DARK_MUTED, ACCENT = TEXT;
    static int PRIMARY = Ui.DARK_TEXT, PRIMARY_TEXT = Ui.DARK_BG;
    static int ERROR = 0xffffa4a4, WARNING = 0xffe7c17d;
    private static final String PREFS = "pocketagent_appearance", KEY_THEME = "theme";

    private DeskStyle() { }

    /** Call before constructing activity views; recreate them when themeSignature changes. */
    static void apply(Context context) {
        boolean dark = isDark(context);
        BG = dark ? Ui.DARK_BG : Ui.LIGHT_BG;
        SURFACE = Ui.surface(dark);
        FIELD = Ui.field(dark);
        LINE = Ui.line(dark);
        TEXT = Ui.text(dark);
        MUTED = Ui.muted(dark);
        ACCENT = TEXT;
        PRIMARY = TEXT;
        PRIMARY_TEXT = dark ? Ui.DARK_BG : Ui.LIGHT_BG;
        ERROR = dark ? 0xffffa4a4 : 0xffa92727;
        WARNING = dark ? 0xffe7c17d : 0xff7c4e00;
        context.setTheme(dark ? R.style.Theme_PocketAgent_Dark : R.style.Theme_PocketAgent_Light);
        if (context instanceof Activity) applySystemBars((Activity) context);
    }

    static String themeChoice(Context context) {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THEME, "system");
        return "dark".equals(value) || "light".equals(value) ? value : "system";
    }

    static void setThemeChoice(Context context, String choice) {
        if (!"system".equals(choice) && !"light".equals(choice) && !"dark".equals(choice))
            throw new IllegalArgumentException("Choose System, Light or Dark appearance.");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, choice).apply();
    }

    static boolean isDark(Context context) {
        String choice = themeChoice(context);
        return "dark".equals(choice) || ("system".equals(choice)
                && (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
    }

    static String themeSignature(Context context) {
        return themeChoice(context) + ':' + isDark(context) + ':' + context.getResources().getConfiguration().fontScale;
    }

    static int dialogTheme(Context context) {
        return isDark(context) ? R.style.Theme_PocketAgent_Dialog : R.style.Theme_PocketAgent_Dialog_Light;
    }

    /** May be called again after an activity configures edge-to-edge layout flags. */
    static void applySystemBars(Activity activity) {
        Window window = activity.getWindow(); if (window == null) return;
        boolean dark = isDark(activity);
        int background = dark ? Ui.DARK_BG : Ui.LIGHT_BG;
        window.setStatusBarColor(background); window.setNavigationBarColor(background);
        int flags = window.getDecorView().getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(dark ? flags & ~light : flags | light);
    }

    static GradientDrawable background(Context context) {
        return flat(context, BG, 0, 0);
    }

    static GradientDrawable card(Context context) {
        return flat(context, SURFACE, 12, 0);
    }

    static GradientDrawable field(Context context) {
        return flat(context, FIELD, 12, 0);
    }

    /** One containing surface for a chat draft; utility controls use the tighter field radius. */
    static GradientDrawable composer(Context context) {
        return flat(context, FIELD, 16, LINE);
    }

    static Drawable secondary(Context context) {
        return Ui.tappable(context, flat(context, FIELD, 12, 0), isDark(context));
    }

    static Drawable plain(Context context) {
        return Ui.tappable(context, flat(context, Color.TRANSPARENT, 12, 0), isDark(context));
    }

    static Drawable primary(Context context) {
        return new RippleDrawable(ColorStateList.valueOf(isDark(context) ? 0x22000000 : 0x33ffffff),
                flat(context, PRIMARY, 12, 0), null);
    }

    static GradientDrawable nav(Context context) {
        return flat(context, BG, 0, 0);
    }

    static GradientDrawable indicator(Context context) {
        return flat(context, FIELD, 12, 0);
    }

    static Drawable icon(Context context, String name, int color) {
        return new LineIcon(name, color, Ui.dp(context, 24));
    }

    private static GradientDrawable flat(Context context, int fill, int radius, int border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill);
        drawable.setCornerRadius(Ui.dp(context, radius));
        if (border != 0) drawable.setStroke(Math.max(1, Ui.dp(context, 1)), border);
        return drawable;
    }

    /** Drawn directly at the requested size; no font glyphs, icon fonts or runtime resources. */
    private static final class LineIcon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final String name;
        private final int intrinsic;

        LineIcon(String name, int color, int intrinsic) {
            this.name = name;
            this.intrinsic = intrinsic;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.8f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int save = canvas.save();
            float size = Math.min(bounds.width(), bounds.height());
            canvas.translate(bounds.left + (bounds.width() - size) / 2f,
                    bounds.top + (bounds.height() - size) / 2f);
            canvas.scale(size / 24f, size / 24f);
            path.reset();
            switch (name) {
                case "chat":
                    path.moveTo(6, 4); path.lineTo(18, 4); path.quadTo(21, 4, 21, 7);
                    path.lineTo(21, 15); path.quadTo(21, 18, 18, 18); path.lineTo(10, 18);
                    path.lineTo(4, 21); path.lineTo(4, 17); path.quadTo(3, 16, 3, 14);
                    path.lineTo(3, 7); path.quadTo(3, 4, 6, 4); path.close();
                    canvas.drawPath(path, paint); line(canvas, 7, 9, 17, 9); line(canvas, 7, 13, 14, 13);
                    break;
                case "files": case "folder":
                    path.moveTo(3, 8); path.lineTo(3, 6); path.quadTo(3, 4, 5, 4);
                    path.lineTo(9, 4); path.lineTo(12, 7); path.lineTo(19, 7);
                    path.quadTo(21, 7, 21, 9); path.lineTo(21, 18);
                    path.quadTo(21, 20, 19, 20); path.lineTo(5, 20);
                    path.quadTo(3, 20, 3, 18); path.lineTo(3, 8); path.close();
                    canvas.drawPath(path, paint); line(canvas, 3, 10, 21, 10);
                    break;
                case "file":
                    path.moveTo(6, 3); path.lineTo(14, 3); path.lineTo(20, 9);
                    path.lineTo(20, 19); path.quadTo(20, 21, 18, 21); path.lineTo(6, 21);
                    path.quadTo(4, 21, 4, 19); path.lineTo(4, 5); path.quadTo(4, 3, 6, 3);
                    path.close(); canvas.drawPath(path, paint);
                    path.reset(); path.moveTo(14, 3); path.lineTo(14, 9); path.lineTo(20, 9);
                    canvas.drawPath(path, paint); line(canvas, 8, 13, 16, 13); line(canvas, 8, 17, 14, 17);
                    break;
                case "new": case "plus":
                    line(canvas, 12, 4, 12, 20); line(canvas, 4, 12, 20, 12);
                    break;
                case "menu":
                    canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                    line(canvas, 9, 4, 9, 20);
                    break;
                case "more":
                    Paint.Style dotsStyle = paint.getStyle(); paint.setStyle(Paint.Style.FILL);
                    for (int y = 5; y <= 19; y += 7) canvas.drawCircle(12, y, 1.5f, paint);
                    paint.setStyle(dotsStyle);
                    break;
                case "trash":
                    line(canvas, 4, 6, 20, 6); line(canvas, 9, 3, 15, 3);
                    path.moveTo(6, 6); path.lineTo(7, 21); path.lineTo(17, 21); path.lineTo(18, 6);
                    canvas.drawPath(path, paint); line(canvas, 10, 10, 10, 17); line(canvas, 14, 10, 14, 17);
                    break;
                case "archive":
                    canvas.drawRoundRect(3, 3, 21, 8, 1.2f, 1.2f, paint);
                    path.moveTo(5, 8); path.lineTo(5, 19); path.quadTo(5, 21, 7, 21);
                    path.lineTo(17, 21); path.quadTo(19, 21, 19, 19); path.lineTo(19, 8);
                    canvas.drawPath(path, paint); line(canvas, 9, 12, 15, 12);
                    break;
                case "restore":
                    path.moveTo(4, 11); path.lineTo(4, 19); path.quadTo(4, 21, 6, 21);
                    path.lineTo(18, 21); path.quadTo(20, 21, 20, 19); path.lineTo(20, 11);
                    canvas.drawPath(path, paint); line(canvas, 4, 14, 9, 14); line(canvas, 15, 14, 20, 14);
                    path.reset(); path.moveTo(8, 7); path.lineTo(12, 3); path.lineTo(16, 7);
                    canvas.drawPath(path, paint); line(canvas, 12, 3, 12, 15);
                    break;
                case "close":
                    line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18);
                    break;
                case "search":
                    canvas.drawCircle(10.5f, 10.5f, 6.5f, paint);
                    line(canvas, 15.2f, 15.2f, 21, 21);
                    break;
                case "apps":
                    for (int x = 4; x <= 14; x += 10)
                        for (int y = 4; y <= 14; y += 10)
                            canvas.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, paint);
                    break;
                case "terminal":
                    canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                    path.moveTo(7, 9); path.lineTo(10, 12); path.lineTo(7, 15);
                    canvas.drawPath(path, paint); line(canvas, 13, 15, 17, 15);
                    break;
                case "tasks":
                    path.moveTo(5, 4); path.lineTo(5, 17); path.quadTo(5, 20, 8, 20);
                    path.lineTo(20, 20); canvas.drawPath(path, paint);
                    canvas.drawRoundRect(9, 4, 20, 14, 2, 2, paint);
                    line(canvas, 12, 8, 17, 8); line(canvas, 12, 11, 16, 11);
                    break;
                case "edit":
                    path.moveTo(4, 16); path.lineTo(16, 4); path.quadTo(18, 2, 20, 4);
                    path.quadTo(22, 6, 20, 8); path.lineTo(8, 20); path.lineTo(3, 21);
                    path.lineTo(4, 16); path.close(); canvas.drawPath(path, paint);
                    line(canvas, 14, 6, 18, 10);
                    break;
                case "share":
                    path.moveTo(8, 6); path.lineTo(12, 2); path.lineTo(16, 6);
                    canvas.drawPath(path, paint); line(canvas, 12, 2, 12, 14);
                    path.reset(); path.moveTo(7, 9); path.lineTo(4, 9); path.lineTo(4, 21);
                    path.lineTo(20, 21); path.lineTo(20, 9); path.lineTo(17, 9);
                    canvas.drawPath(path, paint);
                    break;
                case "copy":
                    canvas.drawRoundRect(8, 8, 21, 21, 3, 3, paint);
                    path.moveTo(16, 8); path.lineTo(16, 6); path.quadTo(16, 3, 13, 3);
                    path.lineTo(6, 3); path.quadTo(3, 3, 3, 6); path.lineTo(3, 13);
                    path.quadTo(3, 16, 6, 16); path.lineTo(8, 16);
                    canvas.drawPath(path, paint);
                    break;
                case "speaker":
                    path.moveTo(4, 9); path.lineTo(8, 9); path.lineTo(13, 5);
                    path.lineTo(13, 19); path.lineTo(8, 15); path.lineTo(4, 15);
                    path.close(); canvas.drawPath(path, paint);
                    canvas.drawArc(13, 8, 19, 16, -65, 130, false, paint);
                    canvas.drawArc(11, 4, 23, 20, -55, 110, false, paint);
                    break;
                case "language":
                    canvas.drawCircle(12, 12, 9, paint);
                    canvas.drawOval(8, 3, 16, 21, paint);
                    line(canvas, 3, 12, 21, 12);
                    canvas.drawArc(4, 3, 20, 11, 15, 150, false, paint);
                    canvas.drawArc(4, 13, 20, 21, 195, 150, false, paint);
                    break;
                case "export": case "download":
                    line(canvas, 12, 3, 12, 15);
                    path.moveTo(7, 10); path.lineTo(12, 15); path.lineTo(17, 10);
                    canvas.drawPath(path, paint);
                    path.reset(); path.moveTo(4, 16); path.lineTo(4, 19);
                    path.quadTo(4, 21, 6, 21); path.lineTo(18, 21);
                    path.quadTo(20, 21, 20, 19); path.lineTo(20, 16);
                    canvas.drawPath(path, paint);
                    break;
                case "refresh":
                    canvas.drawArc(4, 4, 20, 20, -145, 285, false, paint);
                    path.moveTo(4, 3); path.lineTo(4, 8); path.lineTo(9, 8);
                    canvas.drawPath(path, paint);
                    break;
                case "history":
                    canvas.drawArc(4, 4, 20, 20, -145, 285, false, paint);
                    path.moveTo(3, 3); path.lineTo(3, 8); path.lineTo(8, 8);
                    canvas.drawPath(path, paint);
                    line(canvas, 12, 7, 12, 12); line(canvas, 12, 12, 15, 14);
                    break;
                case "back":
                    path.moveTo(10, 5); path.lineTo(3, 12); path.lineTo(10, 19);
                    canvas.drawPath(path, paint); line(canvas, 3, 12, 21, 12);
                    break;
                case "changes":
                    canvas.drawCircle(7, 5, 2.5f, paint); canvas.drawCircle(7, 19, 2.5f, paint);
                    canvas.drawCircle(18, 5, 2.5f, paint); line(canvas, 7, 7.5f, 7, 16.5f);
                    path.moveTo(18, 7.5f); path.lineTo(18, 9); path.quadTo(18, 13, 13, 13);
                    path.lineTo(7, 13); canvas.drawPath(path, paint);
                    break;
                case "preview":
                    canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint);
                    line(canvas, 9, 21, 15, 21); line(canvas, 12, 17, 12, 21);
                    path.moveTo(10, 8); path.lineTo(15, 10.5f); path.lineTo(10, 13); path.close();
                    canvas.drawPath(path, paint);
                    break;
                case "account":
                    canvas.drawCircle(12, 8, 3.5f, paint);
                    path.moveTo(4, 21); path.lineTo(4, 19); path.cubicTo(4, 13, 20, 13, 20, 19);
                    path.lineTo(20, 21); canvas.drawPath(path, paint);
                    break;
                case "attach":
                    path.moveTo(9, 15); path.lineTo(16, 8); path.cubicTo(19, 5, 22, 9, 19, 12);
                    path.lineTo(10, 21); path.cubicTo(6, 25, 0, 19, 4, 15); path.lineTo(15, 4);
                    path.cubicTo(20, -1, 27, 6, 22, 11);
                    // Keep the paperclip comfortably inside the same 24 dp visual box.
                    canvas.translate(1.1f, 1.2f); canvas.scale(0.85f, 0.85f); canvas.drawPath(path, paint);
                    break;
                case "send":
                    path.moveTo(5, 11); path.lineTo(12, 4); path.lineTo(19, 11);
                    canvas.drawPath(path, paint); line(canvas, 12, 4, 12, 21);
                    break;
                case "stop":
                    canvas.drawRoundRect(6, 6, 18, 18, 2.2f, 2.2f, paint);
                    break;
                case "effort": case "settings":
                    line(canvas, 4, 7, 9, 7); line(canvas, 15, 7, 20, 7);
                    canvas.drawCircle(12, 7, 3, paint);
                    line(canvas, 4, 17, 5, 17); line(canvas, 11, 17, 20, 17);
                    canvas.drawCircle(8, 17, 3, paint);
                    break;
                case "chevron":
                    path.moveTo(8, 10); path.lineTo(12, 14); path.lineTo(16, 10);
                    canvas.drawPath(path, paint); break;
                case "chevron_left":
                    path.moveTo(14, 6); path.lineTo(8, 12); path.lineTo(14, 18);
                    canvas.drawPath(path, paint); break;
                case "chevron_right":
                    path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18);
                    canvas.drawPath(path, paint); break;
                case "usage":
                    canvas.drawArc(3.5f, 3.5f, 20.5f, 20.5f, 135, 270, false, paint);
                    line(canvas, 12, 12, 17, 7); canvas.drawCircle(12, 12, 1.5f, paint);
                    break;
                case "check":
                    path.moveTo(5, 12); path.lineTo(10, 17); path.lineTo(20, 6);
                    canvas.drawPath(path, paint); break;
                case "model":
                    canvas.drawRoundRect(6, 6, 18, 18, 3, 3, paint);
                    for (int p = 9; p <= 15; p += 6) {
                        line(canvas, p, 3, p, 6); line(canvas, p, 18, p, 21);
                        line(canvas, 3, p, 6, p); line(canvas, 18, p, 21, p);
                    }
                    canvas.drawRoundRect(9, 9, 15, 15, 1, 1, paint); break;
                case "slash":
                    line(canvas, 16, 3, 8, 21); break;
                case "dollar":
                    path.moveTo(18, 6); path.cubicTo(14, 2, 6, 4, 6, 8);
                    path.cubicTo(6, 13, 18, 10, 18, 16); path.cubicTo(18, 21, 9, 22, 5, 18);
                    canvas.drawPath(path, paint); line(canvas, 12, 2, 12, 22);
                    break;
                case "at":
                    canvas.drawCircle(12, 12, 3.5f, paint);
                    canvas.drawArc(3, 3, 21, 21, 45, 310, false, paint);
                    path.moveTo(15.5f, 8.5f); path.lineTo(15.5f, 14);
                    path.cubicTo(15.5f, 18, 21, 17, 21, 12); canvas.drawPath(path, paint);
                    break;
                case "voice":
                    float voiceWidth = paint.getStrokeWidth(); paint.setStrokeWidth(2.7f);
                    line(canvas, 4, 9, 4, 15); line(canvas, 8, 5, 8, 19);
                    line(canvas, 12, 7, 12, 17); line(canvas, 16, 3, 16, 21);
                    line(canvas, 20, 9, 20, 15); paint.setStrokeWidth(voiceWidth);
                    break;
                case "mic":
                    canvas.drawRoundRect(9, 3, 15, 15, 3, 3, paint);
                    path.moveTo(6, 10); path.lineTo(6, 12); path.cubicTo(6, 20, 18, 20, 18, 12);
                    path.lineTo(18, 10); canvas.drawPath(path, paint);
                    line(canvas, 12, 18, 12, 22); line(canvas, 9, 22, 15, 22);
                    break;
                case "theme":
                    canvas.drawCircle(12, 12, 8.5f, paint);
                    path.moveTo(12, 3.5f); path.arcTo(3.5f, 3.5f, 20.5f, 20.5f, -90, 180, false);
                    path.close(); Paint.Style old = paint.getStyle(); paint.setStyle(Paint.Style.FILL);
                    canvas.drawPath(path, paint); paint.setStyle(old); break;
                case "image":
                    canvas.drawRoundRect(3, 3, 21, 21, 3, 3, paint); canvas.drawCircle(8, 8, 1.5f, paint);
                    path.moveTo(4, 18); path.lineTo(9, 13); path.lineTo(12, 16); path.lineTo(17, 10); path.lineTo(21, 15);
                    canvas.drawPath(path, paint); break;
                case "video":
                    canvas.drawRoundRect(3, 5, 16, 19, 2.5f, 2.5f, paint);
                    path.moveTo(16, 10); path.lineTo(21, 7); path.lineTo(21, 17); path.lineTo(16, 14);
                    canvas.drawPath(path, paint); break;
                case "logout":
                    path.moveTo(10, 4); path.lineTo(5, 4); path.quadTo(3, 4, 3, 6); path.lineTo(3, 18);
                    path.quadTo(3, 20, 5, 20); path.lineTo(10, 20); canvas.drawPath(path, paint);
                    line(canvas, 10, 12, 21, 12); path.reset(); path.moveTo(17, 8); path.lineTo(21, 12); path.lineTo(17, 16);
                    canvas.drawPath(path, paint); break;
                case "plan":
                    for (int y = 5; y <= 19; y += 7) {
                        canvas.drawRoundRect(3, y - 1, 6, y + 2, .5f, .5f, paint); line(canvas, 10, y + .5f, 21, y + .5f);
                    }
                    break;
                default:
                    canvas.drawCircle(12, 12, 8, paint); line(canvas, 12, 8, 12, 16);
                    line(canvas, 8, 12, 16, 12); break;
            }
            canvas.restoreToCount(save);
        }

        private void line(Canvas canvas, float x1, float y1, float x2, float y2) {
            canvas.drawLine(x1, y1, x2, y2, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return intrinsic; }
        @Override public int getIntrinsicHeight() { return intrinsic; }
    }
}
