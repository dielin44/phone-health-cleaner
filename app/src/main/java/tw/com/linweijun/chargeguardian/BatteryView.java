package tw.com.linweijun.chargeguardian;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Anime-inspired charging reactor with moving rings, particles and energy bands. */
final class BatteryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private int percent;
    private boolean charging;
    private float phase;
    private final ValueAnimator animator;

    BatteryView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(2400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    void update(int p, boolean c) {
        percent = Math.max(0, Math.min(100, p));
        charging = c;
        invalidate();
    }

    int color() {
        return percent < 30 ? Color.rgb(255, 55, 83)
                : percent < 80 ? Color.rgb(31, 226, 145)
                : Color.rgb(48, 157, 255);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h * .49f;
        int energy = color();
        drawTechField(canvas, w, h, cx, cy, energy);
        drawReactorRings(canvas, cx, cy, w, energy);
        drawBatteryCore(canvas, w, h, energy);
        if (charging) drawEnergyParticles(canvas, w, h, energy);
        drawLabels(canvas, w, h, energy);
    }

    private void drawTechField(Canvas canvas, float w, float h, float cx, float cy, int energy) {
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, w * .57f,
                new int[]{withAlpha(energy, 54), Color.rgb(7, 17, 31)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0, 0, w, h, 42, 42, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(withAlpha(energy, 25));
        float grid = Math.max(18f, w / 12f);
        for (float x = grid; x < w; x += grid) canvas.drawLine(x, h * .08f, x, h * .91f, paint);
        for (float y = h * .10f; y < h * .91f; y += grid) canvas.drawLine(w * .06f, y, w * .94f, y, paint);
    }

    private void drawReactorRings(Canvas canvas, float cx, float cy, float w, int energy) {
        float radius = w * .44f;
        ring.set(cx - radius, cy - radius, cx + radius, cy + radius);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        glow.setStrokeWidth(4f);
        glow.setColor(withAlpha(energy, charging ? 210 : 90));
        glow.setShadowLayer(charging ? 15f : 7f, 0, 0, energy);
        float rotation = phase * 360f;
        for (int i = 0; i < 4; i++) canvas.drawArc(ring, rotation + i * 90f, 46f, false, glow);

        float inner = radius - 14f;
        ring.set(cx - inner, cy - inner, cx + inner, cy + inner);
        glow.setStrokeWidth(2f);
        glow.setColor(withAlpha(Color.WHITE, charging ? 150 : 55));
        for (int i = 0; i < 3; i++) canvas.drawArc(ring, -rotation * .65f + i * 120f, 64f, false, glow);

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(energy, 185));
        for (int i = 0; i < 12; i++) {
            double a = Math.toRadians(i * 30f + rotation * .22f);
            float x = cx + (float) Math.cos(a) * (radius + 5f);
            float y = cy + (float) Math.sin(a) * (radius + 5f);
            canvas.save();
            canvas.rotate(i * 30f + rotation * .22f + 90f, x, y);
            canvas.drawRoundRect(x - 1.5f, y - 7f, x + 1.5f, y + 7f, 2f, 2f, paint);
            canvas.restore();
        }
    }

    private void drawBatteryCore(Canvas canvas, float w, float h, int energy) {
        float left = w * .25f, right = w * .75f, top = h * .18f, bottom = h * .79f;
        float inset = 12f;
        glow.setStyle(Paint.Style.FILL);
        glow.setColor(withAlpha(energy, charging ? 42 : 22));
        glow.setShadowLayer(charging ? 24f : 10f, 0, 0, energy);
        canvas.drawRoundRect(left - 5f, top - 5f, right + 5f, bottom + 5f, 28f, 28f, glow);
        glow.clearShadowLayer();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(10, 25, 43));
        canvas.drawRoundRect(left, top, right, bottom, 24f, 24f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setColor(withAlpha(Color.WHITE, 225));
        canvas.drawRoundRect(left, top, right, bottom, 24f, 24f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(Color.WHITE, 225));
        canvas.drawRoundRect(w * .42f, top - h * .045f, w * .58f, top + 3f, 8f, 8f, paint);

        float capacity = (bottom - top - inset * 2f) * percent / 100f;
        float fillTop = bottom - inset - capacity;
        paint.setShader(new LinearGradient(0, bottom, 0, fillTop,
                new int[]{withAlpha(energy, 245), withAlpha(Color.WHITE, 210), withAlpha(energy, 175)},
                null, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(charging ? 14f : 5f, 0, 0, energy);
        canvas.drawRoundRect(left + inset, fillTop, right - inset, bottom - inset, 14f, 14f, paint);
        paint.clearShadowLayer();
        paint.setShader(null);
        if (charging && percent > 3) {
            float bandY = bottom - inset - capacity * ((phase * 1.35f) % 1f);
            paint.setColor(withAlpha(Color.WHITE, 190));
            paint.setShadowLayer(10f, 0, 0, Color.WHITE);
            canvas.drawRoundRect(left + inset + 3f, bandY - 2f, right - inset - 3f, bandY + 2f, 3f, 3f, paint);
            paint.clearShadowLayer();
        }
        drawBolt(canvas, w * .5f, h * .38f, w * .13f, charging ? Color.WHITE : withAlpha(Color.WHITE, 120), energy);
    }

    private void drawEnergyParticles(Canvas canvas, float w, float h, int energy) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 18; i++) {
            float seed = (i * .173f) % 1f;
            float travel = (phase * (1f + (i % 4) * .16f) + seed) % 1f;
            float x = w * (.12f + ((i * 37) % 76) / 100f);
            float y = h * (.88f - travel * .75f);
            float size = 1.8f + (i % 3) * 1.3f;
            int alpha = (int) (220 * Math.sin(Math.PI * travel));
            paint.setColor(withAlpha(i % 4 == 0 ? Color.WHITE : energy, alpha));
            paint.setShadowLayer(8f, 0, 0, energy);
            canvas.drawCircle(x, y, size, paint);
        }
        paint.clearShadowLayer();
    }

    private void drawLabels(Canvas canvas, float w, float h, int energy) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(charging ? 10f : 4f, 0, 0, energy);
        paint.setTextSize(w * .145f);
        canvas.drawText(percent + "%", w / 2f, h * .59f, paint);
        paint.clearShadowLayer();
        paint.setTextSize(w * .047f);
        paint.setLetterSpacing(.18f);
        paint.setColor(charging ? energy : Color.LTGRAY);
        canvas.drawText(charging ? "ENERGY LINK" : "STANDBY", w / 2f, h * .69f, paint);
        paint.setLetterSpacing(0f);
    }

    private void drawBolt(Canvas canvas, float cx, float cy, float size, int fill, int energy) {
        Path bolt = new Path();
        bolt.moveTo(cx + size * .10f, cy - size);
        bolt.lineTo(cx - size * .58f, cy + size * .03f);
        bolt.lineTo(cx - size * .08f, cy + size * .03f);
        bolt.lineTo(cx - size * .30f, cy + size);
        bolt.lineTo(cx + size * .62f, cy - size * .18f);
        bolt.lineTo(cx + size * .10f, cy - size * .18f);
        bolt.close();
        glow.setStyle(Paint.Style.FILL);
        glow.setColor(fill);
        glow.setShadowLayer(charging ? 18f : 5f, 0, 0, energy);
        canvas.drawPath(bolt, glow);
        glow.clearShadowLayer();
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }
}
