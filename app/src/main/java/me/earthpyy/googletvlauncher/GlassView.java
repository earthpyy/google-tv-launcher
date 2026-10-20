package me.earthpyy.googletvlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** The single translucent focus highlight that springs from tile to tile. */
public final class GlassView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix shaderMatrix = new Matrix();
    private final LinearGradient sheen;
    private final RectF rect = new RectF();
    private final float radius;

    private float centerX;
    private float centerY;
    private float boxWidth;
    private float boxHeight;

    private final SpringAnimation springX = spring(new Prop("x") {
        @Override public float getValue(GlassView v) { return v.centerX; }
        @Override public void setValue(GlassView v, float value) { v.centerX = value; v.invalidate(); }
    });
    private final SpringAnimation springY = spring(new Prop("y") {
        @Override public float getValue(GlassView v) { return v.centerY; }
        @Override public void setValue(GlassView v, float value) { v.centerY = value; v.invalidate(); }
    });
    private final SpringAnimation springW = spring(new Prop("w") {
        @Override public float getValue(GlassView v) { return v.boxWidth; }
        @Override public void setValue(GlassView v, float value) { v.boxWidth = value; v.invalidate(); }
    });
    private final SpringAnimation springH = spring(new Prop("h") {
        @Override public float getValue(GlassView v) { return v.boxHeight; }
        @Override public void setValue(GlassView v, float value) { v.boxHeight = value; v.invalidate(); }
    });

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        radius = 13 * density;
        // Unit-height gradient, stretched onto the box in onDraw
        sheen = new LinearGradient(0, 0, 0, 1, 0x38FFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP);
        fill.setShader(sheen);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * density);
        stroke.setColor(0x66FFFFFF);
    }

    /** Move to a box centred on (x, y); jump instead of springing when {@code immediate}. */
    void moveTo(float x, float y, float width, float height, boolean immediate) {
        if (immediate || boxWidth == 0) {
            springX.cancel();
            springY.cancel();
            springW.cancel();
            springH.cancel();
            centerX = x;
            centerY = y;
            boxWidth = width;
            boxHeight = height;
            invalidate();
            return;
        }
        springX.animateToFinalPosition(x);
        springY.animateToFinalPosition(y);
        springW.animateToFinalPosition(width);
        springH.animateToFinalPosition(height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (boxWidth <= 0) {
            return;
        }
        rect.set(centerX - boxWidth / 2, centerY - boxHeight / 2, centerX + boxWidth / 2, centerY + boxHeight / 2);
        shaderMatrix.setScale(1, boxHeight);
        shaderMatrix.postTranslate(0, rect.top);
        sheen.setLocalMatrix(shaderMatrix);
        canvas.drawRoundRect(rect, radius, radius, fill);
        canvas.drawRoundRect(rect, radius, radius, stroke);
    }

    private SpringAnimation spring(FloatPropertyCompat<GlassView> property) {
        SpringAnimation animation = new SpringAnimation(this, property);
        animation.setSpring(new SpringForce()
                .setStiffness(700f)
                .setDampingRatio(0.72f));
        return animation;
    }

    private abstract static class Prop extends FloatPropertyCompat<GlassView> {
        Prop(String name) {
            super(name);
        }
    }
}
