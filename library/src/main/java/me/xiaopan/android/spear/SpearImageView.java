/*
 * Copyright (C) 2013 Peng fei Pan <sky@xiaopan.me>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.xiaopan.android.spear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.Scroller;

import me.xiaopan.android.spear.util.RecyclingBitmapDrawable;

/**
 * SpearImageView
 */
public class SpearImageView extends ImageView{
    private static final int NONE = -1;
    private static final int FROM_FLAG_COLOR_MEMORY = 0x8800FF00;
    private static final int FROM_FLAG_COLOR_LOCAL = 0x880000FF;
    private static final int FROM_FLAG_COLOR_DISK_CACHE = 0x88FFFF00;
    private static final int FROM_FLAG_COLOR_NETWORK = 0x88FF0000;
    private static final int DEFAULT_PROGRESS_COLOR = 0x22000000;
    private static final int DEFAULT_RIPPLE_COLOR = 0x33000000;
    private static final int RIPPLE_ANIMATION_DURATION_SHORT = 100;
    private static final int RIPPLE_ANIMATION_DURATION_LENGTH = 500;

    private Request displayRequest;
    private MyListener myListener;
    private DisplayOptions displayOptions;
    private DisplayListener displayListener;
    private ProgressListener progressListener;

    private int fromFlagColor = NONE;
    private Path fromFlagPath;
    private Paint fromFlagPaint;
    private boolean showFromFlag;

    private int progressColor = DEFAULT_PROGRESS_COLOR;
    private Paint progressPaint;
    private float progress = NONE;
    private boolean showDownloadProgress;

    private View.OnClickListener onClickListener;
    private String imageUri;

    private int touchX;
    private int touchY;
    private int clickRippleColor = DEFAULT_RIPPLE_COLOR;
    private boolean pressed;
    private boolean enableClickRipple;
    private Paint clickRipplePaint;
    private Scroller clickRippleScroller;
    private Runnable clickRippleRefreshRunnable;

    public SpearImageView(Context context) {
        super(context);
    }

    public SpearImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setOnClickListener(OnClickListener l) {
        super.setOnClickListener(l);
        this.onClickListener = l;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // 重新计算三角形的位置
        if(fromFlagPath != null){
            fromFlagPath.reset();
            int x = getWidth()/10;
            int y = getWidth()/10;
            fromFlagPath.moveTo(getPaddingLeft(), getPaddingTop());
            fromFlagPath.lineTo(getPaddingLeft() + x, getPaddingTop());
            fromFlagPath.lineTo(getPaddingLeft(), getPaddingTop() + y);
            fromFlagPath.close();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 绘制按下状态
        if(pressed || (clickRippleScroller != null && clickRippleScroller.computeScrollOffset())){
            if(clickRipplePaint == null){
                clickRipplePaint = new Paint();
                clickRipplePaint.setColor(clickRippleColor);
            }
            canvas.drawCircle(touchX, touchY, clickRippleScroller.getCurrX(), clickRipplePaint);
        }

        // 绘制进度
        if(showDownloadProgress && progress != NONE){
            if(progressPaint == null){
                progressPaint = new Paint();
                progressPaint.setColor(progressColor);
            }
            canvas.drawRect(getPaddingLeft(), getPaddingTop() + (progress * getHeight()), getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom(), progressPaint);
        }

        // 绘制三角形
        if(showFromFlag && fromFlagColor != NONE){
            if(fromFlagPath == null){
                fromFlagPath = new Path();
                int x = getWidth()/10;
                int y = getWidth()/10;
                fromFlagPath.moveTo(getPaddingLeft(), getPaddingTop());
                fromFlagPath.lineTo(getPaddingLeft()+x, getPaddingTop());
                fromFlagPath.lineTo(getPaddingLeft(), getPaddingTop()+y);
                fromFlagPath.close();
            }
            if(fromFlagPaint == null){
                fromFlagPaint = new Paint();
            }
            fromFlagPaint.setColor(fromFlagColor);
            canvas.drawPath(fromFlagPath, fromFlagPaint);
        }
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        if(enableClickRipple && this.pressed != pressed){
            this.pressed = pressed;
            if(pressed){
                if(clickRippleScroller == null){
                    clickRippleScroller = new Scroller(getContext(), new DecelerateInterpolator());
                }
                clickRippleScroller.startScroll(0, 0, computeRippleRadius(), 0, RIPPLE_ANIMATION_DURATION_LENGTH);
                if(clickRippleRefreshRunnable == null){
                    clickRippleRefreshRunnable = new Runnable() {
                        @Override
                        public void run() {
                            invalidate();
                            if(clickRippleScroller.computeScrollOffset()){
                                post(this);
                            }
                        }
                    };
                }
                post(clickRippleRefreshRunnable);
            }

            invalidate();
        }
    }

    /**
     * 计算涟漪的半径
     * @return 涟漪的半径
     */
    private int computeRippleRadius(){
        int centerX = getWidth()/2;
        int centerY = getHeight()/2;
        // 当按下位置在第一或第四象限的时候，比较按下位置在左上角到右下角这条线上距离谁最远就以谁为半径，否则在左下角到右上角这条线上比较
        if((touchX < centerX && touchY < centerY) || (touchX > centerX && touchY > centerY)) {
            int toLeftTopXDistance = touchX;
            int toLeftTopYDistance = touchY;
            int toLeftTopDistance = (int) Math.sqrt((toLeftTopXDistance * toLeftTopXDistance) + (toLeftTopYDistance * toLeftTopYDistance));
            int toRightBottomXDistance = Math.abs(touchX - getWidth());
            int toRightBottomYDistance = Math.abs(touchY - getHeight());
            int toRightBottomDistance = (int) Math.sqrt((toRightBottomXDistance * toRightBottomXDistance) + (toRightBottomYDistance * toRightBottomYDistance));
            return toLeftTopDistance > toRightBottomDistance ? toLeftTopDistance : toRightBottomDistance;
        }else{
           int toLeftBottomXDistance = touchX;
           int toLeftBottomYDistance = Math.abs(touchY - getHeight());
           int toLeftBottomDistance = (int) Math.sqrt((toLeftBottomXDistance * toLeftBottomXDistance) + (toLeftBottomYDistance * toLeftBottomYDistance));
           int toRightTopXDistance = Math.abs(touchX - getWidth());
           int toRightTopYDistance = touchY;
           int toRightTopDistance = (int) Math.sqrt((toRightTopXDistance * toRightTopXDistance) + (toRightTopYDistance * toRightTopYDistance));
           return toLeftBottomDistance > toRightTopDistance ? toLeftBottomDistance : toRightTopDistance;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(enableClickRipple && event.getAction() == MotionEvent.ACTION_DOWN && !pressed){
            touchX = (int) event.getX();
            touchY = (int) event.getY();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.GINGERBREAD_MR1){
            final Drawable previousDrawable = getDrawable();
            if(previousDrawable != null){
                notifyDrawable("onDetachedFromWindow", previousDrawable, false);
            }
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        // Keep hold of previous Drawable
        final Drawable previousDrawable = getDrawable();

        // Call super to set new Drawable
        super.setImageDrawable(drawable);

        // Notify new Drawable that it is being displayed
        if(drawable != null){
            notifyDrawable("setImageDrawable", drawable, true);
        }

        // Notify old Drawable so it is no longer being displayed
        if(previousDrawable != null){
            notifyDrawable("setImageDrawable", previousDrawable, false);
        }
    }

    /**
     * 显示图片
     * @param uri 图片Uri，支持以下几种
     * <blockquote>"http://site.com/image.png"; // from Web
     * <br>"https://site.com/image.png"; // from Web
     * <br>"/mnt/sdcard/image.png"; // from SD card
     * <br>"/mnt/sdcard/app.apk"; // from SD card apk file
     * <br>"content://media/external/audio/albumart/13"; // from content provider
     * <br>"asset://image.png"; // from assets
     * <br>"drawable://" + R.drawable.image; // from drawables (only images, non-9patch)
     * </blockquote>
     * @return Request 你可以通过Request查看请求是否完成或主动取消请求
     */
    public Request displayImage(String uri){
        return Spear.with(getContext()).display(uri, this).fire();
    }

    /**
     * 显示本地图片
     * @param imageFilePath SD卡上的图片文件
     * @return Request 你可以通过Request查看请求是否完成或主动取消请求
     */
    public Request displayFilImagee(String imageFilePath){
        return Spear.with(getContext()).display(imageFilePath, this).fire();
    }

    /**
     * 显示Drawable资源里的图片
     * @param drawableResId Drawable ID
     * @return Request 你可以通过Request查看请求是否完成或主动取消请求
     */
    public Request displayResourceImage(int drawableResId){
        return Spear.with(getContext()).display(UriScheme.DRAWABLE.createUri(String.valueOf(drawableResId)), this).fire();
    }

    /**
     * 显示asset里的图片
     * @param imageFileName ASSETS文件加下的图片文件的名称
     * @return Request 你可以通过Request查看请求是否完成或主动取消请求
     */
    public Request displayAssetImage(String imageFileName){
        return Spear.with(getContext()).display(UriScheme.ASSET.createUri(imageFileName), this).fire();
    }

    /**
     * 根据Content Uri显示图片
     * @param uri Content Uri 这个URI是其它Content Provider返回的
     * @return Request 你可以通过Request查看请求是否完成或主动取消请求
     */
    public Request displayContentImage(Uri uri){
        return Spear.with(getContext()).display(uri != null ? UriScheme.ASSET.createUri(uri.toString()):null, this).fire();
    }

    /**
     * 设置是否开启点击涟漪效果，开启后按下的时候会在ImageView表面显示一个黑色半透明的涟漪效果，此功能需要你点注册点击事件或设置Clickable
     * @param enableClickRipple 是否开启点击涟漪效果
     */
    public void setEnableClickRipple(boolean enableClickRipple) {
        this.enableClickRipple = enableClickRipple;
    }

    /**
     * 设置是否显示下载进度
     * @param showDownloadProgress 是否显示进度
     */
    public void setShowDownloadProgress(boolean showDownloadProgress) {
        this.showDownloadProgress = showDownloadProgress;
    }

    /**
     * 设置点击涟漪效果的颜色
     * @param clickRippleColor 点击涟漪效果的颜色
     */
    public void setClickRippleColor(int clickRippleColor) {
        this.clickRippleColor = clickRippleColor;
        if(clickRipplePaint != null){
            clickRipplePaint.setColor(clickRippleColor);
        }
    }

    /**
     * 设置进度的颜色
     * @param progressColor 进度的颜色
     */
    public void setProgressColor(int progressColor) {
        this.progressColor = progressColor;
        if(progressPaint != null){
            progressPaint.setColor(progressColor);
        }
    }

    /**
     * 获取显示参数
     * @return 显示参数
     */
    DisplayOptions getDisplayOptions() {
        return displayOptions;
    }

    /**
     * 设置显示参数
     * @param displayOptions 显示参数
     */
    public void setDisplayOptions(DisplayOptions displayOptions) {
        this.displayOptions = displayOptions;
    }

    /**
     * 设置显示参数的名称
     * @param optionsName 显示参数的名称
     */
    public void setDisplayOptions(Enum<?> optionsName) {
        this.displayOptions = (DisplayOptions) Spear.getOptions(optionsName);
    }

    /**
     * 获取显示监听器
     * @return 显示监听器
     */
    DisplayListener getDisplayListener(boolean isPauseDownload){
        if(showFromFlag || showDownloadProgress || isPauseDownload){
            if(myListener == null){
                myListener = new MyListener();
            }
            return myListener;
        }else{
            return displayListener;
        }
    }

    /**
     * 设置显示监听器
     * @param displayListener 显示监听器
     */
    public void setDisplayListener(DisplayListener displayListener) {
        this.displayListener = displayListener;
    }

    /**
     * 获取进度监听器
     * @return 进度监听器
     */
    ProgressListener getProgressListener(){
        if(showDownloadProgress){
            if(myListener == null){
                myListener = new MyListener();
            }
            return myListener;
        }else{
            return progressListener;
        }
    }

    /**
     * 设置显示进度监听器
     * @param progressListener 进度监听器
     */
    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    /**
     * 获取显示请求，你可通过这个对象来查看状态或主动取消请求
     * @return 显示请求
     */
    public Request getDisplayRequest() {
        return displayRequest;
    }

    /**
     * 设置显示请求，此方法由Spear调用，你无需理会即可
     * @param displayRequest 显示请求
     */
    void setDisplayRequest(Request displayRequest) {
        this.displayRequest = displayRequest;
    }

    /**
     * 设置图片uri
     * @param imageUri 图片uri
     */
    void setImageUri(String imageUri){
        this.imageUri = imageUri;
        setOnClickListener(onClickListener);
    }

    /**
     * 设置是否开启调试模式，开启后会在View的左上角显示一个纯色三角形，红色代表本次是从网络加载的，黄色代表本次是从本地加载的，绿色代表本次是从内存加载的
     * @param showFromFlag 是否开启调试模式
     */
    public void setShowFromFlag(boolean showFromFlag) {
        boolean oldDebugMode = this.showFromFlag;
        this.showFromFlag = showFromFlag;
        if(oldDebugMode){
            fromFlagColor = NONE;
            invalidate();
        }
    }

    /**
     * Notifies the drawable that it's displayed state has changed.
     * @param callingStation 调用位置
     * @param drawable Drawable
     * @param isDisplayed 是否已显示
     */
    private static void notifyDrawable(String callingStation, Drawable drawable, final boolean isDisplayed) {
        if (drawable instanceof RecyclingBitmapDrawable) {
            // The drawable is a CountingBitmapDrawable, so notify it
            ((RecyclingBitmapDrawable) drawable).setIsDisplayed(callingStation, isDisplayed);
        } else if (drawable instanceof LayerDrawable) {
            // The drawable is a LayerDrawable, so recurse on each layer
            LayerDrawable layerDrawable = (LayerDrawable) drawable;
            for (int i = 0, z = layerDrawable.getNumberOfLayers(); i < z; i++) {
                notifyDrawable(callingStation, layerDrawable.getDrawable(i), isDisplayed);
            }
        }
    }

    private class MyListener implements DisplayListener, ProgressListener, View.OnClickListener{
        @Override
        public void onStarted() {
            if(showFromFlag){
                fromFlagColor = NONE;
            }
            if(showDownloadProgress){
                progress = 0;
            }
            if(showFromFlag || showDownloadProgress){
                invalidate();
            }
            if(displayListener != null){
                displayListener.onStarted();
            }
        }

        @Override
        public void onCompleted(ImageFrom imageFrom) {
            if(showFromFlag){
                if(imageFrom != null){
                    switch (imageFrom){
                        case MEMORY_CACHE: fromFlagColor = FROM_FLAG_COLOR_MEMORY; break;
                        case DISK_CACHE: fromFlagColor = FROM_FLAG_COLOR_DISK_CACHE; break;
                        case NETWORK: fromFlagColor = FROM_FLAG_COLOR_NETWORK; break;
                        case LOCAL: fromFlagColor = FROM_FLAG_COLOR_LOCAL; break;
                    }
                }else{
                    fromFlagColor = NONE;
                }
            }
            if(showDownloadProgress){
                progress = NONE;
            }
            if(showFromFlag || showDownloadProgress){
                invalidate();
            }
            if(displayListener != null){
                displayListener.onCompleted(imageFrom);
            }
        }

        @Override
        public void onFailed(FailCause failCause) {
            if(showFromFlag){
                fromFlagColor = NONE;
            }
            if(showDownloadProgress){
                progress = NONE;
            }
            if(showDownloadProgress || showFromFlag){
                invalidate();
            }
            if (displayListener != null){
                displayListener.onFailed(failCause);
            }
        }

        @Override
        public void onCanceled(CancelCause cancelCause) {
            if(cancelCause != null && cancelCause == CancelCause.PAUSE_DOWNLOAD_NEW_IMAGE){
                SpearImageView.super.setOnClickListener(this);
            }
            if(displayListener != null){
                displayListener.onCanceled(cancelCause);
            }
        }

        @Override
        public void onUpdateProgress(int totalLength, int completedLength) {
            if(showDownloadProgress){
                progress = (float) completedLength/totalLength;
                invalidate();
            }
            if(progressListener != null){
                progressListener.onUpdateProgress(totalLength, completedLength);
            }
        }

        @Override
        public void onClick(View v) {
            Spear.with(getContext()).display(imageUri, SpearImageView.this).level(RequestHandleLevel.NET).fire();
        }
    }
}