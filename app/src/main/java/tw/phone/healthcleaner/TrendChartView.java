package tw.phone.healthcleaner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class TrendChartView extends View {
    static final int TEMPERATURE = 1;
    static final int BATTERY = 2;
    private final int mode;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<HistoryPoint> points = new ArrayList<>();

    TrendChartView(Context context, int mode) {
        super(context);
        this.mode = mode;
        setMinimumHeight(dp(230));
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    void setPoints(List<HistoryPoint> value) {
        points = new ArrayList<>(value);
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(dp(230), heightMeasureSpec));
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(Color.WHITE);
        float left = dp(43), right = getWidth() - dp(12), top = dp(28), bottom = getHeight() - dp(34);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(13)); paint.setColor(Color.rgb(30, 41, 59));
        c.drawText(mode == TEMPERATURE ? "溫度變化（°C）" : "電量變化（%）", left, dp(18), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);

        if (points.size() < 2) {
            paint.setTextSize(dp(14)); paint.setColor(Color.rgb(100, 116, 139));
            c.drawText("即時監測滿 10 秒後開始產生曲線", left, (top + bottom) / 2, paint);
            return;
        }

        float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
        for (HistoryPoint p : points) {
            if (mode == BATTERY && p.batteryLevel >= 0) { min = Math.min(min, p.batteryLevel); max = Math.max(max, p.batteryLevel); }
            if (mode == TEMPERATURE) {
                if (!Float.isNaN(p.batteryTemp)) { min = Math.min(min, p.batteryTemp); max = Math.max(max, p.batteryTemp); }
                if (!Float.isNaN(p.cpuTemp)) { min = Math.min(min, p.cpuTemp); max = Math.max(max, p.cpuTemp); }
                if (!Float.isNaN(p.gpuTemp)) { min = Math.min(min, p.gpuTemp); max = Math.max(max, p.gpuTemp); }
            }
        }
        if (!Float.isFinite(min) || !Float.isFinite(max)) return;
        if (mode == BATTERY) { min = Math.max(0, min - 1); max = Math.min(100, max + 1); }
        else { min = (float)Math.floor(min - 1); max = (float)Math.ceil(max + 1); }
        if (max - min < 2) { min -= 1; max += 1; }

        paint.setStrokeWidth(dp(1)); paint.setTextSize(dp(10));
        for (int i = 0; i <= 4; i++) {
            float y = top + (bottom - top) * i / 4f;
            paint.setColor(Color.rgb(226, 232, 240)); c.drawLine(left, y, right, y, paint);
            float label = max - (max - min) * i / 4f;
            paint.setColor(Color.rgb(100, 116, 139));
            c.drawText(String.format(Locale.TAIWAN, mode == BATTERY ? "%.0f" : "%.1f", label), dp(4), y + dp(4), paint);
        }

        long start = points.get(0).timestamp;
        long end = points.get(points.size() - 1).timestamp;
        paint.setColor(Color.rgb(100, 116, 139));
        c.drawText("0:00", left, bottom + dp(18), paint);
        String endLabel = duration(end - start);
        c.drawText(endLabel, right - paint.measureText(endLabel), bottom + dp(18), paint);

        if (mode == BATTERY) drawLine(c, left, right, top, bottom, min, max, 0, Color.rgb(37, 99, 235));
        else {
            drawLine(c, left, right, top, bottom, min, max, 1, Color.rgb(234, 88, 12));
            drawLine(c, left, right, top, bottom, min, max, 2, Color.rgb(220, 38, 38));
            drawLine(c, left, right, top, bottom, min, max, 3, Color.rgb(147, 51, 234));
            drawLegend(c, right);
        }
    }

    private void drawLine(Canvas c, float left, float right, float top, float bottom, float min, float max, int series, int color) {
        Path path = new Path(); boolean started = false;
        long first = points.get(0).timestamp;
        long span = Math.max(1, points.get(points.size() - 1).timestamp - first);
        for (HistoryPoint p : points) {
            float value;
            if (series == 0) value = p.batteryLevel;
            else if (series == 1) value = p.batteryTemp;
            else if (series == 2) value = p.cpuTemp;
            else value = p.gpuTemp;
            if (value < 0 || Float.isNaN(value)) { started = false; continue; }
            float x = left + (right - left) * (p.timestamp - first) / span;
            float y = bottom - (bottom - top) * (value - min) / (max - min);
            if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f)); paint.setColor(color);
        c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
    }

    private void drawLegend(Canvas c, float right) {
        String[] names = {"電池", "CPU", "GPU"};
        int[] colors = {Color.rgb(234,88,12), Color.rgb(220,38,38), Color.rgb(147,51,234)};
        paint.setTextSize(dp(10)); float x = right;
        for (int i = names.length - 1; i >= 0; i--) {
            float width = paint.measureText(names[i]) + dp(17); x -= width;
            paint.setColor(colors[i]); c.drawCircle(x + dp(4), dp(14), dp(3), paint);
            paint.setColor(Color.rgb(71,85,105)); c.drawText(names[i], x + dp(10), dp(17), paint);
        }
    }

    private static String duration(long ms) {
        long sec = Math.max(0, ms / 1000);
        return String.format(Locale.TAIWAN, "%d:%02d", sec / 60, sec % 60);
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
