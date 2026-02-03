package com.lvrenyang.dsview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.FontMetrics;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class ProgressButton extends View {
    private FontMetrics fm;
    private int progress = 0;
    private int textColor = Color.WHITE;
    private Paint paint = new Paint();
    RectF oval = new RectF();
    private float textSize = 10;
    private int foreground;
    private int backgroundColor;
    private String text;
    private int max = 100;
    private int corner = 5;// 圆角的弧度

    public ProgressButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public ProgressButton(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        this.backgroundColor = Color.parseColor("#C6C6C6");
        this.foreground = Color.rgb(20,131,214);
        this.textColor = Color.WHITE;
        this.max = 100;
        this.progress = 0;
        this.textSize = 20;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStrokeWidth(5);

        /**
         * 绘制背景
         */
        oval.set(0, 0, getWidth(), getHeight());

        paint.setColor(this.backgroundColor);
        canvas.drawRoundRect(oval, corner, corner, paint);

        /***
         * 绘制进度值
         */
        
        paint.setColor(foreground);
        if (progress <= corner) {
        	oval.set(0, corner - progress, getWidth() * this.progress / this.max, getHeight()
                    - corner + progress);
            canvas.drawRoundRect(oval, progress,progress, paint);
        } else {
        	oval.set(0, 0, getWidth() * this.progress / this.max, getHeight());
            canvas.drawRoundRect(oval, corner, corner, paint);
        }

        /***
         * 绘制文本
         */
        if ("".equals(text) || text == null) {
            return;
        }
        paint.setTextSize(this.textSize);
        fm = paint.getFontMetrics();
        paint.setColor(this.textColor);

        float textCenterVerticalBaselineY = getHeight() / 2 - fm.descent + (fm.descent - fm.ascent) / 2;
        canvas.drawText(this.text, (getMeasuredWidth() - paint.measureText(this.text)) / 2, textCenterVerticalBaselineY,
                paint);

        
    }

    /**
     * 设置最大值
     * 
     * @param max
     */
    public void setMax(int max) {
        this.max = max;
    }

    /**
     * 设置文本提示信息
     * 
     * @param text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * 设置进度条的颜色值
     * 
     * @param color
     */
    public void setForeground(int color) {
        this.foreground = color;
    }

    /**
     * 设置进度条的背景色
     */
    public void setBackgroundColor(int color) {
        this.backgroundColor = color;
    }

    /***
     * 设置文本的大小
     */
    public void setTextSize(int size) {
        this.textSize = size;
    }

    /**
     * 设置文本的颜色值
     * 
     * @param color
     */
    public void setTextColor(int color) {
        this.textColor = color;
    }

    /**
     * 设置进度值
     * 
     * @param progress
     */
    public void setProgress(int progress) {
        if(progress>max){
            return;
        }
        this.progress=progress;
        //设置进度之后，要求UI强制进行重绘
        postInvalidate();
    }
    
    public int getMax(){
        return max;
    }
    
    public int getProgress(){
        return progress;
    }

}