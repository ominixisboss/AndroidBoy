package com.ominixisboss.androidboy;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Line icons for the game screen's toolbars and menu, drawn in code on a 24×24 grid (the app
 * uses no icon fonts or libraries). Strokes are round-capped, like common icon sets.
 */
final class Icons {
    static final int BACK = 0;
    static final int MENU = 1;
    static final int TROPHY = 2;
    static final int LINK = 3;
    static final int CAMERA = 4;
    static final int LOCK = 5;
    static final int UNLOCK = 6;
    static final int PAUSE = 7;
    static final int PLAY = 8;
    static final int REWIND = 9;
    static final int SOUND = 10;
    static final int MUTE = 11;
    static final int FULLSCREEN = 12;
    static final int CLOSE = 13;
    static final int CHEVRON = 14;
    static final int SAVE = 15;
    static final int LOAD = 16;
    static final int CHEAT = 17;
    static final int SEARCH = 18;
    static final int GAMEPAD = 19;
    static final int TOUCH = 20;
    static final int TURBO = 21;
    static final int SKIN = 22;
    static final int SETTINGS = 23;
    static final int RESET = 24;
    static final int PRINTER = 25;
    static final int EXIT = 26;
    static final int FAST = 27;
    static final int SLOW = 28;
    static final int SWAP = 29;
    static final int UNPLUG = 30;
    static final int PLUS = 31;
    static final int MORE = 32;
    static final int STAR = 33;
    static final int SORT = 34;
    static final int COUNT = 35;

    private Icons() {}

    /** A drawable for icon {@code type} in {@code color}, drawn at whatever size it's given. */
    static Drawable drawable(int type, int color) {
        return new IconDrawable(type, color);
    }

    private static final class IconDrawable extends Drawable {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        IconDrawable(int type, int color) {
            this.type = type;
            paint.setColor(color);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            canvas.save();
            canvas.translate(b.centerX() - size / 2, b.centerY() - size / 2);
            canvas.scale(size / 24f, size / 24f);
            Icons.draw(canvas, type, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return -1;
        }

        @Override
        public int getIntrinsicHeight() {
            return -1;
        }
    }

    /** Draws icon {@code type} on a 24×24 canvas. */
    static void draw(Canvas canvas, int type, Paint paint) {
        Path p = new Path();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        switch (type) {
            case BACK:
                p.moveTo(19, 12); p.lineTo(5, 12);
                p.moveTo(11, 6); p.lineTo(5, 12); p.lineTo(11, 18);
                break;
            case MENU:
                p.moveTo(4, 7); p.lineTo(20, 7);
                p.moveTo(4, 12); p.lineTo(20, 12);
                p.moveTo(4, 17); p.lineTo(20, 17);
                break;
            case TROPHY:
                p.moveTo(7, 4); p.lineTo(17, 4); p.lineTo(17, 9);
                p.cubicTo(17, 12.5f, 14.8f, 14.5f, 12, 14.5f);
                p.cubicTo(9.2f, 14.5f, 7, 12.5f, 7, 9); p.close();
                p.moveTo(7, 6); p.lineTo(4, 6); p.cubicTo(4, 9, 5.5f, 10.5f, 7.4f, 10.8f);
                p.moveTo(17, 6); p.lineTo(20, 6); p.cubicTo(20, 9, 18.5f, 10.5f, 16.6f, 10.8f);
                p.moveTo(12, 14.5f); p.lineTo(12, 18);
                p.moveTo(8, 20); p.lineTo(16, 20);
                p.moveTo(9, 18); p.lineTo(15, 18);
                break;
            case LINK:
                // Two people.
                p.addCircle(9, 8, 3, Path.Direction.CW);
                p.moveTo(3.5f, 19); p.cubicTo(3.5f, 15, 6, 13.5f, 9, 13.5f); p.cubicTo(12, 13.5f, 14.5f, 15, 14.5f, 19);
                p.addCircle(16.5f, 8.5f, 2.5f, Path.Direction.CW);
                p.moveTo(16, 13.6f); p.cubicTo(18.8f, 13.6f, 20.5f, 15.2f, 20.5f, 18.5f);
                break;
            case CAMERA:
                p.addRoundRect(new RectF(3, 7, 21, 19), 2.5f, 2.5f, Path.Direction.CW);
                p.moveTo(8, 7); p.lineTo(9.5f, 4.5f); p.lineTo(14.5f, 4.5f); p.lineTo(16, 7);
                p.addCircle(12, 13, 3.3f, Path.Direction.CW);
                break;
            case LOCK:
            case UNLOCK:
                p.addRoundRect(new RectF(5, 11, 19, 20.5f), 2, 2, Path.Direction.CW);
                p.moveTo(8, 11); p.lineTo(8, 8);
                p.cubicTo(8, 5.6f, 9.8f, 4, 12, 4);
                p.cubicTo(14.2f, 4, 16, 5.6f, 16, 8);
                if (type == LOCK) p.lineTo(16, 11);
                p.moveTo(12, 15); p.lineTo(12, 16.5f);
                break;
            case PAUSE:
                paint.setStyle(Paint.Style.FILL);
                p.addRoundRect(new RectF(6, 5, 10, 19), 1, 1, Path.Direction.CW);
                p.addRoundRect(new RectF(14, 5, 18, 19), 1, 1, Path.Direction.CW);
                break;
            case PLAY:
                paint.setStyle(Paint.Style.FILL);
                p.moveTo(7, 5); p.lineTo(19, 12); p.lineTo(7, 19); p.close();
                break;
            case REWIND:
                paint.setStyle(Paint.Style.FILL);
                p.moveTo(12, 6); p.lineTo(3, 12); p.lineTo(12, 18); p.close();
                p.moveTo(21, 6); p.lineTo(12, 12); p.lineTo(21, 18); p.close();
                break;
            case FAST:
                paint.setStyle(Paint.Style.FILL);
                p.moveTo(3, 6); p.lineTo(12, 12); p.lineTo(3, 18); p.close();
                p.moveTo(12, 6); p.lineTo(21, 12); p.lineTo(12, 18); p.close();
                break;
            case SLOW:
                // A snail's shell and body: slow motion.
                p.addCircle(11, 12, 5, Path.Direction.CW);
                p.moveTo(11, 12); p.cubicTo(11, 10.5f, 13, 10.5f, 13, 12.5f); p.cubicTo(13, 15, 9, 15, 9, 12);
                p.moveTo(3, 18); p.lineTo(20, 18); p.cubicTo(21.5f, 18, 21.5f, 15, 20, 14);
                break;
            case SOUND:
            case MUTE:
                paint.setStyle(Paint.Style.FILL);
                Path speaker = new Path();
                speaker.moveTo(3, 9); speaker.lineTo(7, 9); speaker.lineTo(12, 5); speaker.lineTo(12, 19);
                speaker.lineTo(7, 15); speaker.lineTo(3, 15); speaker.close();
                canvas.drawPath(speaker, paint);
                paint.setStyle(Paint.Style.STROKE);
                if (type == SOUND) {
                    p.moveTo(15.5f, 9); p.cubicTo(17, 10.5f, 17, 13.5f, 15.5f, 15);
                    p.moveTo(18, 6.5f); p.cubicTo(21, 9.5f, 21, 14.5f, 18, 17.5f);
                } else {
                    p.moveTo(16, 9); p.lineTo(21, 15);
                    p.moveTo(21, 9); p.lineTo(16, 15);
                }
                break;
            case FULLSCREEN:
                p.moveTo(4, 9); p.lineTo(4, 4); p.lineTo(9, 4);
                p.moveTo(15, 4); p.lineTo(20, 4); p.lineTo(20, 9);
                p.moveTo(20, 15); p.lineTo(20, 20); p.lineTo(15, 20);
                p.moveTo(9, 20); p.lineTo(4, 20); p.lineTo(4, 15);
                break;
            case CLOSE:
                p.moveTo(6, 6); p.lineTo(18, 18);
                p.moveTo(18, 6); p.lineTo(6, 18);
                break;
            case CHEVRON:
                p.moveTo(9, 5); p.lineTo(16, 12); p.lineTo(9, 19);
                break;
            case SAVE:
                p.moveTo(5, 4); p.lineTo(16, 4); p.lineTo(20, 8); p.lineTo(20, 20); p.lineTo(4, 20); p.lineTo(4, 4);
                p.addCircle(12, 14, 2.5f, Path.Direction.CW);
                p.moveTo(8, 4); p.lineTo(8, 8); p.lineTo(14, 8); p.lineTo(14, 4);
                break;
            case LOAD:
                p.moveTo(4, 7); p.lineTo(4, 19); p.lineTo(20, 19); p.lineTo(20, 9); p.lineTo(12, 9);
                p.lineTo(10, 6); p.lineTo(5, 6);
                p.moveTo(12, 17); p.lineTo(12, 11.5f);
                p.moveTo(9.5f, 14); p.lineTo(12, 11.5f); p.lineTo(14.5f, 14);
                break;
            case CHEAT: {
                // "01 / 10", as binary digits.
                paint.setStrokeWidth(1.8f);
                p.addRoundRect(new RectF(4, 3.5f, 8, 10.5f), 2, 2, Path.Direction.CW);
                p.moveTo(12, 5); p.lineTo(14, 3.5f); p.lineTo(14, 10.5f);
                p.moveTo(6, 15); p.lineTo(8, 13.5f); p.lineTo(8, 20.5f);
                p.addRoundRect(new RectF(12, 13.5f, 16, 20.5f), 2, 2, Path.Direction.CW);
                p.moveTo(18, 3.5f); p.lineTo(20, 3.5f);
                break;
            }
            case SEARCH:
                p.addCircle(10.5f, 10.5f, 6, Path.Direction.CW);
                p.moveTo(15, 15); p.lineTo(20, 20);
                break;
            case GAMEPAD:
                p.addRoundRect(new RectF(2.5f, 7, 21.5f, 17.5f), 5, 5, Path.Direction.CW);
                p.moveTo(7.5f, 10); p.lineTo(7.5f, 14.5f);
                p.moveTo(5.25f, 12.25f); p.lineTo(9.75f, 12.25f);
                p.addCircle(15.5f, 11, 0.9f, Path.Direction.CW);
                p.addCircle(18, 13.5f, 0.9f, Path.Direction.CW);
                break;
            case TOUCH:
                p.moveTo(9, 13); p.lineTo(9, 5.5f); p.cubicTo(9, 4, 11.5f, 4, 11.5f, 5.5f); p.lineTo(11.5f, 11);
                p.moveTo(11.5f, 10); p.cubicTo(11.5f, 8.5f, 14, 8.5f, 14, 10); p.lineTo(14, 11.5f);
                p.moveTo(14, 11); p.cubicTo(14, 9.5f, 16.5f, 9.5f, 16.5f, 11); p.lineTo(16.5f, 12);
                p.moveTo(16.5f, 12); p.cubicTo(16.5f, 10.5f, 19, 10.5f, 19, 12); p.lineTo(19, 15.5f);
                p.cubicTo(19, 19, 17, 21, 14, 21); p.lineTo(12.5f, 21); p.cubicTo(10.5f, 21, 9.5f, 20, 8.5f, 18.5f);
                p.lineTo(5.8f, 14.5f); p.cubicTo(5, 13.2f, 6.8f, 12, 7.8f, 13); p.lineTo(9, 14.3f);
                break;
            case TURBO:
                paint.setStyle(Paint.Style.FILL);
                p.moveTo(13.5f, 2.5f); p.lineTo(5, 13.5f); p.lineTo(11, 13.5f); p.lineTo(10, 21.5f);
                p.lineTo(19, 10); p.lineTo(13, 10); p.close();
                break;
            case SKIN:
                // A handheld.
                p.addRoundRect(new RectF(6, 2.5f, 18, 21.5f), 2, 2, Path.Direction.CW);
                p.addRect(new RectF(8.5f, 5, 15.5f, 10.5f), Path.Direction.CW);
                p.moveTo(9, 15.5f); p.lineTo(11, 15.5f);
                p.moveTo(10, 14.5f); p.lineTo(10, 16.5f);
                p.addCircle(14.5f, 15, 0.8f, Path.Direction.CW);
                break;
            case SETTINGS: {
                p.addCircle(12, 12, 3, Path.Direction.CW);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 2 * i / 8;
                    float cos = (float) Math.cos(angle);
                    float sin = (float) Math.sin(angle);
                    p.moveTo(12 + cos * 6.2f, 12 + sin * 6.2f);
                    p.lineTo(12 + cos * 8.8f, 12 + sin * 8.8f);
                }
                p.addCircle(12, 12, 6.2f, Path.Direction.CW);
                break;
            }
            case RESET:
                p.addArc(new RectF(4.5f, 4.5f, 19.5f, 19.5f), -60, 300);
                p.moveTo(14, 3); p.lineTo(16, 5.8f); p.lineTo(13, 7.5f);
                break;
            case PRINTER:
                p.moveTo(7, 9); p.lineTo(7, 3.5f); p.lineTo(17, 3.5f); p.lineTo(17, 9);
                p.addRoundRect(new RectF(3, 9, 21, 17), 2, 2, Path.Direction.CW);
                p.addRect(new RectF(7, 14, 17, 20.5f), Path.Direction.CW);
                break;
            case EXIT:
                p.moveTo(10, 4); p.lineTo(5, 4); p.lineTo(5, 20); p.lineTo(10, 20);
                p.moveTo(10, 12); p.lineTo(20, 12);
                p.moveTo(16, 8); p.lineTo(20, 12); p.lineTo(16, 16);
                break;
            case SWAP:
                p.moveTo(4, 8); p.lineTo(19, 8);
                p.moveTo(15, 4); p.lineTo(19, 8); p.lineTo(15, 12);
                p.moveTo(20, 16); p.lineTo(5, 16);
                p.moveTo(9, 12); p.lineTo(5, 16); p.lineTo(9, 20);
                break;
            case UNPLUG:
                // A plug with a gap in its cable.
                p.addRoundRect(new RectF(7, 7, 17, 14), 2, 2, Path.Direction.CW);
                p.moveTo(9.5f, 7); p.lineTo(9.5f, 3.5f);
                p.moveTo(14.5f, 7); p.lineTo(14.5f, 3.5f);
                p.moveTo(12, 14); p.lineTo(12, 16.5f);
                p.moveTo(12, 19); p.lineTo(12, 21);
                break;
            case PLUS:
                p.moveTo(12, 5); p.lineTo(12, 19);
                p.moveTo(5, 12); p.lineTo(19, 12);
                break;
            case MORE:
                // Three dots, stacked.
                paint.setStyle(Paint.Style.FILL);
                p.addCircle(12, 5.5f, 1.6f, Path.Direction.CW);
                p.addCircle(12, 12, 1.6f, Path.Direction.CW);
                p.addCircle(12, 18.5f, 1.6f, Path.Direction.CW);
                break;
            case STAR:
                for (int i = 0; i < 10; i++) {
                    double angle = Math.PI / 5 * i - Math.PI / 2;
                    float radius = i % 2 == 0 ? 9 : 3.8f;
                    float x = 12 + (float) Math.cos(angle) * radius;
                    float y = 12.6f + (float) Math.sin(angle) * radius;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                p.close();
                break;
            case SORT:
                // Lines getting shorter, and an arrow.
                p.moveTo(4, 7); p.lineTo(14, 7);
                p.moveTo(4, 12); p.lineTo(11, 12);
                p.moveTo(4, 17); p.lineTo(8, 17);
                p.moveTo(18, 5); p.lineTo(18, 19);
                p.moveTo(15.5f, 16.5f); p.lineTo(18, 19); p.lineTo(20.5f, 16.5f);
                break;
            default:
                break;
        }
        canvas.drawPath(p, paint);
    }
}
