package com.wp7.navbar;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * WP7 风格导航键图标 —— 纯代码矢量绘制。
 *
 * 直接以 Canvas + Path 绘制，不依赖任何 PNG / XML 资源。
 * 三键：
 *   BACK   : 细线左箭头
 *   HOME   : 经典 Windows 四格视窗 Logo（四个平行四边形，整体居中、右缘微斜）
 *   RECENTS: 圆角方框（当前默认不替换，仅作占位/备用）
 *
 * 兼容性：实现了 ConstantState，使该 Drawable 可被 KeyButtonDrawable /
 * ImageView 等正常持有与重建（避免某些场景 childState 为 null 导致崩溃）。
 */
public class Wp7IconDrawable extends Drawable {

    private final int size;
    private final int color;
    private final String type; // "BACK" / "HOME" / "RECENTS"

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Wp7IconDrawable(int size, int color, String type) {
        this.size = size;
        this.color = color;
        this.type = type;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, size / 10f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setAntiAlias(true);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float s = Math.min(b.width(), b.height());

        switch (type) {
            case "BACK":
                drawBack(canvas, s * 2f / 3f, cx, cy);
                break;
            case "HOME":
                drawHome(canvas, s * 2f / 3f, cx, cy);
                break;
            case "RECENTS":
                drawRecents(canvas, s, cx, cy);
                break;
            default:
                canvas.drawCircle(cx, cy, s / 6f, paint);
                break;
        }
    }

    /** WP7 返回：细线左箭头（stroke） */
    private void drawBack(Canvas canvas, float s, float cx, float cy) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.STROKE);
        float sw = Math.max(2f, s * 0.09f);
        p.setStrokeWidth(sw);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        float r = s * 0.32f;
        Path path = new Path();
        path.moveTo(cx + r, cy);
        path.lineTo(cx - r, cy);
        path.moveTo(cx - r * 0.3f, cy - r * 0.7f);
        path.lineTo(cx - r, cy);
        path.lineTo(cx - r * 0.3f, cy + r * 0.7f);
        canvas.drawPath(path, p);
    }

    /** WP7 主页：经典 Windows 四格视窗 Logo（四个平行四边形，居中） */
    private void drawHome(Canvas canvas, float s, float cx, float cy) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.FILL);
        p.setAntiAlias(true);
        drawWin7Logo(canvas, s, cx, cy, p);
    }

    /**
     * 绘制带飘扬感的 Windows 四格徽标。
     * 四个左倾平行四边形 2×2 排列，中间留隙。
     * 左侧两格上下边缘从中点沿斜边反方向（右上）弯成曲线；
     * 右侧两格上下边缘从中点沿斜边方向（左下）弯成曲线。
     */
    private void drawWin7Logo(Canvas canvas, float s, float cx, float cy, Paint p) {
        float half = s * 0.30f;      // 整体半宽
        float hh = s * 0.28f;        // 整体半高
        float gap = s * 0.09f;       // 格间隙（加大一倍）
        float lean = s * 0.06f;      // 单格倾斜量（上边缘相对下边缘向右偏移）
        float curve = s * 0.07f;     // 曲线拖拽量（调大更弯，调小更直）

        // 单格尺寸
        float cellW = half - gap * 0.5f;
        float cellH = hh - gap * 0.5f;

        // 列边界（以下排/内侧为基准 x）
        float l = cx - half;          // 左列左边缘
        float mid = cx - gap * 0.5f;  // 左列右边缘
        float r = cx + gap * 0.5f;    // 右列左边缘
        float rr = cx + half;         // 右列右边缘

        // 行边界
        float top = cy - hh;          // 上排顶部
        float mt = cy - gap * 0.5f;   // 上排底部
        float mb = cy + gap * 0.5f;   // 下排顶部
        float bot = cy + hh;          // 下排底部

        // 斜边（左边缘）方向单位向量：左下
        float slopeLen = (float) Math.sqrt(lean * lean + cellH * cellH);
        // 左侧偏移：斜边反方向 → 右上
        float leftOffX = lean / slopeLen * curve;
        float leftOffY = -cellH / slopeLen * curve;
        // 右侧偏移：斜边方向 → 左下
        float rightOffX = -lean / slopeLen * curve;
        float rightOffY = cellH / slopeLen * curve;

        // ---------- 左上 ----------
        Path lu = new Path();
        float lu_ax = l + lean, lu_ay = top;      // 左上
        float lu_bx = mid + lean, lu_by = top;    // 右上
        float lu_cx = mid, lu_cy = mt;            // 右下
        float lu_dx = l, lu_dy = mt;              // 左下

        lu.moveTo(lu_ax, lu_ay);
        lu.quadTo((lu_ax + lu_bx) * 0.5f + leftOffX, (lu_ay + lu_by) * 0.5f + leftOffY, lu_bx, lu_by);
        lu.lineTo(lu_cx, lu_cy);
        lu.quadTo((lu_cx + lu_dx) * 0.5f + leftOffX, (lu_cy + lu_dy) * 0.5f + leftOffY, lu_dx, lu_dy);
        lu.close();

        // ---------- 右上 ----------
        Path ru = new Path();
        float ru_ax = r + lean, ru_ay = top;
        float ru_bx = rr + lean, ru_by = top;
        float ru_cx = rr, ru_cy = mt;
        float ru_dx = r, ru_dy = mt;

        ru.moveTo(ru_ax, ru_ay);
        ru.quadTo((ru_ax + ru_bx) * 0.5f + rightOffX, (ru_ay + ru_by) * 0.5f + rightOffY, ru_bx, ru_by);
        ru.lineTo(ru_cx, ru_cy);
        ru.quadTo((ru_cx + ru_dx) * 0.5f + rightOffX, (ru_cy + ru_dy) * 0.5f + rightOffY, ru_dx, ru_dy);
        ru.close();

        // ---------- 左下 ----------
        Path ld = new Path();
        float ld_ax = l, ld_ay = mb;              // 左上
        float ld_bx = mid, ld_by = mb;            // 右上
        float ld_cx = mid - lean, ld_cy = bot;    // 右下
        float ld_dx = l - lean, ld_dy = bot;      // 左下

        ld.moveTo(ld_ax, ld_ay);
        ld.quadTo((ld_ax + ld_bx) * 0.5f + leftOffX, (ld_ay + ld_by) * 0.5f + leftOffY, ld_bx, ld_by);
        ld.lineTo(ld_cx, ld_cy);
        ld.quadTo((ld_cx + ld_dx) * 0.5f + leftOffX, (ld_cy + ld_dy) * 0.5f + leftOffY, ld_dx, ld_dy);
        ld.close();

        // ---------- 右下 ----------
        Path rd = new Path();
        float rd_ax = r, rd_ay = mb;
        float rd_bx = rr, rd_by = mb;
        float rd_cx = rr - lean, rd_cy = bot;
        float rd_dx = r - lean, rd_dy = bot;

        rd.moveTo(rd_ax, rd_ay);
        rd.quadTo((rd_ax + rd_bx) * 0.5f + rightOffX, (rd_ay + rd_by) * 0.5f + rightOffY, rd_bx, rd_by);
        rd.lineTo(rd_cx, rd_cy);
        rd.quadTo((rd_cx + rd_dx) * 0.5f + rightOffX, (rd_cy + rd_dy) * 0.5f + rightOffY, rd_dx, rd_dy);
        rd.close();

        canvas.drawPath(lu, p);
        canvas.drawPath(ru, p);
        canvas.drawPath(ld, p);
        canvas.drawPath(rd, p);
    }

    /** WP7 最近：圆角矩形（备用） */
    private void drawRecents(Canvas canvas, float s, float cx, float cy) {
        Paint p = new Paint(paint);
        p.setStyle(Paint.Style.STROKE);
        float r = s * 0.26f;
        canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, s * 0.08f, s * 0.08f, p);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public ConstantState getConstantState() {
        return new Wp7ConstantState(size, color, type);
    }

    static class Wp7ConstantState extends ConstantState {
        final int size;
        final int color;
        final String type;

        Wp7ConstantState(int size, int color, String type) {
            this.size = size;
            this.color = color;
            this.type = type;
        }

        @Override
        public Drawable newDrawable() {
            return new Wp7IconDrawable(size, color, type);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }
}