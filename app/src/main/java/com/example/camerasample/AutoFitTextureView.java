package com.example.camerasample;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {

    private int _ratioWidth = 0;
    private int _ratioHeight = 0;

    public AutoFitTextureView(Context context) {
        super(context);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }

        _ratioWidth = width;
        _ratioHeight = height;

        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (_ratioWidth  == 0 || _ratioHeight == 0) {
            setMeasuredDimension(width, height);
        }
        else {
            if (width < height * _ratioWidth / _ratioHeight) {
                setMeasuredDimension(width, width * _ratioHeight / _ratioWidth);
            }
            else {
                setMeasuredDimension(height * _ratioWidth / _ratioHeight, height);
            }
        }
    }

}
