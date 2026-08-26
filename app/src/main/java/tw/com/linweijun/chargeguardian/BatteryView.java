package tw.com.linweijun.chargeguardian;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;

final class BatteryView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int percent;
    private boolean charging;
    private float pulse;
    private final ValueAnimator animator;

    BatteryView(Context context) {
        super(context);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1200); animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
        animator.start();
    }

    void update(int p, boolean c) { percent = p; charging = c; invalidate(); }

    int color() { return percent < 30 ? Color.rgb(255,65,80) : percent < 80 ? Color.rgb(29,200,120) : Color.rgb(36,145,255); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float left = w*.18f, right = w*.82f, top = h*.08f, bottom = h*.92f;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(10); paint.setColor(Color.argb(210,255,255,255));
        canvas.drawRoundRect(left, top, right, bottom, 28, 28, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(w*.39f, h*.015f, w*.61f, h*.09f, 10, 10, paint);
        float inset = 17;
        float fillTop = bottom-inset-(bottom-top-2*inset)*Math.max(0, Math.min(100, percent))/100f;
        int alpha = charging ? 175 + (int)(80*pulse) : 235;
        paint.setColor((color() & 0x00FFFFFF) | (alpha << 24));
        canvas.drawRoundRect(left+inset, fillTop, right-inset, bottom-inset, 17, 17, paint);
        paint.setTextAlign(Paint.Align.CENTER); paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(w*.19f); paint.setColor(Color.WHITE);
        canvas.drawText(percent + "%", w/2, h*.56f, paint);
        paint.setTextSize(w*.065f);
        canvas.drawText(charging ? "充電中" : "未充電", w/2, h*.68f, paint);
        if (charging) {
            Path bolt = new Path();
            bolt.moveTo(w*.53f,h*.20f); bolt.lineTo(w*.41f,h*.38f); bolt.lineTo(w*.50f,h*.38f);
            bolt.lineTo(w*.45f,h*.49f); bolt.lineTo(w*.61f,h*.31f); bolt.lineTo(w*.51f,h*.31f); bolt.close();
            paint.setColor(Color.WHITE); canvas.drawPath(bolt,paint);
        }
    }
}
