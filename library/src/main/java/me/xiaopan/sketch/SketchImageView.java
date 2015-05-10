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

package me.xiaopan.sketch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.TransitionDrawable;
import android.net.Uri;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.Scroller;

import me.xiaopan.sketch.util.CommentUtils;
import pl.droidsonroids.gif.GifViewSavedState;
import pl.droidsonroids.gif.GifViewUtils;

public class SketchImageView extends ImageView implements SketchImageViewInterface {
    private static final String NAME = "SketchImageView";

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
    private DisplayParams displayParams;
    private View.OnClickListener onClickListener;
    private boolean replacedClickListener;
    private boolean clickDisplayOnPauseDownload;
    private boolean clickRedisplayOnFailed;
    private boolean isSetImage;

    private boolean mFreezesAnimation;

    protected int fromFlagColor = NONE;
    protected Path fromFlagPath;
    protected Paint fromFlagPaint;
    protected boolean showFromFlag;

    protected int progressColor = DEFAULT_PROGRESS_COLOR;
    protected Paint progressPaint;
    protected float progress = NONE;
    protected boolean showDownloadProgress;

    protected int touchX;
    protected int touchY;
    protected int clickRippleColor = DEFAULT_RIPPLE_COLOR;
    protected boolean pressed;
    protected boolean showClickRipple;
    protected Paint clickRipplePaint;
    protected Scroller clickRippleScroller;
    protected Runnable clickRippleRefreshRunnable;

    protected boolean currentIsShowGifFlag;
    protected boolean showGifFlag;
    protected float gifTextLeft = -1;
    protected float gifTextTop = -1;
    protected Drawable gifFlagDrawable;

    protected Path imageShapeClipPath;
    protected float[] roundedRadii;
    protected ImageShape imageShape = ImageShape.RECT;
    protected boolean applyClip = false;

    public SketchImageView(Context context) {
        super(context);
    }

    public SketchImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        postInit(GifViewUtils.initImageView(this, attrs, 0, 0));
    }

    public SketchImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        postInit(GifViewUtils.initImageView(this, attrs, defStyle, 0));
    }

    @Override
    public void setOnClickListener(OnClickListener l) {
        super.setOnClickListener(l);
        this.onClickListener = l;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if(fromFlagPath != null){
            fromFlagPath.reset();
            int x = getWidth()/10;
            int y = getWidth()/10;
            int left2 = getPaddingLeft();
            int top2 = getPaddingTop();
            fromFlagPath.moveTo(left2, top2);
            fromFlagPath.lineTo(left2 + x, top2);
            fromFlagPath.lineTo(left2, top2 + y);
            fromFlagPath.close();
        }

        if(showGifFlag && gifFlagDrawable != null){
            gifTextLeft = getWidth()-getPaddingRight() - gifFlagDrawable.getIntrinsicWidth();
            gifTextTop = getHeight()-getPaddingBottom() - gifFlagDrawable.getIntrinsicHeight();
        }

        imageShapeClipPath = null;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        imageShapeClipPath = null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawPressedStatus(canvas);
        drawDownloadProgress(canvas);
        drawFromFlag(canvas);
        drawGifFlag(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(showClickRipple && event.getAction() == MotionEvent.ACTION_DOWN && !pressed){
            touchX = (int) event.getX();
            touchY = (int) event.getY();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if(!isSetImage && displayParams != null){
            if(Sketch.isDebugMode()){
                Log.w(Sketch.TAG, CommentUtils.concat(NAME, "：", "restore image on attached to window", " - ", displayParams.uri));
            }
            Sketch.with(getContext()).display(displayParams, SketchImageView.this).commit();
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Drawable source = mFreezesAnimation ? getDrawable() : null;
        Drawable background = mFreezesAnimation ? getBackground() : null;
        return new GifViewSavedState(super.onSaveInstanceState(), source, background);
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        GifViewSavedState ss = (GifViewSavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        ss.restoreState(getDrawable(), 0);
        ss.restoreState(getBackground(), 1);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        this.isSetImage = false;
        if(displayRequest != null && !displayRequest.isFinished()){
            displayRequest.cancel();
        }
        final Drawable oldDrawable = getDrawable();
        if(oldDrawable != null){
            super.setImageDrawable(null);
            notifyDrawable("onDetachedFromWindow", oldDrawable, false);
        }
    }

    @Override
    protected void dispatchSetPressed(boolean pressed) {
        if(showClickRipple && isClickable() && this.pressed != pressed){
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

    @Override
    public void setBackgroundResource(int resId) {
        if (!GifViewUtils.setResource(this, false, resId)) {
            super.setBackgroundResource(resId);
        }
    }

    /**
     * Sets the content of this GifImageView to the specified Uri.
     * If uri destination is not a GIF then {@link android.widget.ImageView#setImageURI(android.net.Uri)}
     * is called as fallback.
     * For supported URI schemes see: {@link android.content.ContentResolver#openAssetFileDescriptor(android.net.Uri, String)}.
     *
     * @param uri The Uri of an image
     */
    @Override
    public void setImageURI(Uri uri) {
        if (!GifViewUtils.setGifImageUri(this, uri)) {
            super.setImageURI(uri);
        }
    }

    @Override
    public void setImageResource(int resId) {
        if (!GifViewUtils.setResource(this, true, resId)) {
            super.setImageResource(resId);
        }
    }

    @Override
    public void setImageDrawable(Drawable newDrawable) {
        // refresh gif flag
        if(showGifFlag){
            boolean newDrawableIsGif = isGifImage(newDrawable);
            if(newDrawableIsGif != currentIsShowGifFlag){
                currentIsShowGifFlag = newDrawableIsGif;
                invalidate();
            }
        }

        final Drawable oldDrawable = getDrawable();
        super.setImageDrawable(newDrawable);

        if(newDrawable != null){
            notifyDrawable("setImageDrawable:newDrawable", newDrawable, true);
        }
        if(oldDrawable != null){
            notifyDrawable("setImageDrawable:oldDrawable", oldDrawable, false);
        }
    }

    protected void drawPressedStatus(Canvas canvas){
        if(pressed || (clickRippleScroller != null && clickRippleScroller.computeScrollOffset())){
            Path imageShapeClipPath = getImageShapeClipPath();
            applyClip = imageShapeClipPath != null;
            if(applyClip){
                canvas.save();
                canvas.clipPath(imageShapeClipPath);
            }

            if(clickRipplePaint == null){
                clickRipplePaint = new Paint();
                clickRipplePaint.setColor(clickRippleColor);
            }
            canvas.drawCircle(touchX, touchY, clickRippleScroller.getCurrX(), clickRipplePaint);

            if(applyClip){
                canvas.restore();
            }
        }
    }

    protected void drawDownloadProgress(Canvas canvas){
        if(showDownloadProgress && progress != NONE){
            Path imageShapeClipPath = getImageShapeClipPath();
            applyClip = imageShapeClipPath != null;
            if(applyClip){
                canvas.save();
                canvas.clipPath(imageShapeClipPath);
            }

            if(progressPaint == null){
                progressPaint = new Paint();
                progressPaint.setColor(progressColor);
            }
            canvas.drawRect(getPaddingLeft(), getPaddingTop() + (progress * getHeight()), getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom(), progressPaint);

            if(applyClip){
                canvas.restore();
            }
        }
    }

    protected void drawFromFlag(Canvas canvas){
        if(showFromFlag && fromFlagColor != NONE){
            if(fromFlagPath == null){
                fromFlagPath = new Path();
                int x = getWidth()/10;
                int y = getWidth()/10;
                int left2 = getPaddingLeft();
                int top2 = getPaddingTop();
                fromFlagPath.moveTo(left2, top2);
                fromFlagPath.lineTo(left2 + x, top2);
                fromFlagPath.lineTo(left2, top2 + y);
                fromFlagPath.close();
            }
            if(fromFlagPaint == null){
                fromFlagPaint = new Paint();
            }
            fromFlagPaint.setColor(fromFlagColor);
            canvas.drawPath(fromFlagPath, fromFlagPaint);
        }
    }

    protected void drawGifFlag(Canvas canvas){
        if(showGifFlag && currentIsShowGifFlag && gifFlagDrawable != null){
            if(gifTextLeft == -1){
                gifTextLeft = getWidth()-getPaddingRight() - gifFlagDrawable.getIntrinsicWidth();
                gifTextTop = getHeight()-getPaddingBottom() - gifFlagDrawable.getIntrinsicHeight();
            }
            canvas.save();
            canvas.translate(gifTextLeft, gifTextTop);
            gifFlagDrawable.draw(canvas);
            canvas.restore();
        }
    }

    protected Path getImageShapeClipPath(){
        if(imageShapeClipPath == null){
            if(imageShape == ImageShape.RECT){
                if(getPaddingLeft() != 0 || getPaddingTop() != 0 || getPaddingRight() != 0 || getPaddingBottom() != 0){
                    imageShapeClipPath = new Path();
                    imageShapeClipPath.addRect(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom(), Path.Direction.CW);
                }
            }else if(imageShape == ImageShape.CIRCLE){
                imageShapeClipPath = new Path();
                int xRadius = (getWidth()-getPaddingLeft()-getPaddingRight())/2;
                int yRadius = (getHeight()-getPaddingTop()-getPaddingBottom())/2;
                imageShapeClipPath.addCircle(xRadius, yRadius, xRadius < yRadius ? xRadius : yRadius, Path.Direction.CW);
            }else if(imageShape == ImageShape.ROUNDED_RECT){
                RectF rectF = new RectF(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
                imageShapeClipPath = new Path();
                imageShapeClipPath.addRoundRect(rectF, roundedRadii, Path.Direction.CW);
            }
        }
        return imageShapeClipPath;
    }

    @Override
    public void onDisplay() {

    }

    @Override
    public Request displayImage(String uri){
        return Sketch.with(getContext()).display(uri, this).commit();
    }

    @Override
    public Request displayFileImage(String imageFilePath){
        return Sketch.with(getContext()).display(imageFilePath, this).commit();
    }

    @Override
    public Request displayResourceImage(int drawableResId){
        return Sketch.with(getContext()).display(UriScheme.DRAWABLE.createUri(String.valueOf(drawableResId)), this).commit();
    }

    @Override
    public Request displayAssetImage(String imageFileName){
        return Sketch.with(getContext()).display(UriScheme.ASSET.createUri(imageFileName), this).commit();
    }

    @Override
    public Request displayContentImage(Uri uri){
        return Sketch.with(getContext()).display(uri != null ? UriScheme.ASSET.createUri(uri.toString()):null, this).commit();
    }

    @Override
    public DisplayOptions getDisplayOptions() {
        return displayOptions;
    }

    @Override
    public void setDisplayOptions(DisplayOptions displayOptions) {
        if(displayOptions == null){
            this.displayOptions = null;
        }else if(this.displayOptions == null){
            this.displayOptions = new DisplayOptions(displayOptions);
        }else{
            this.displayOptions.copyOf(displayOptions);
        }
    }

    @Override
    public void setDisplayOptions(Enum<?> optionsName) {
        setDisplayOptions((DisplayOptions) Sketch.getOptions(optionsName));
    }

    @Override
    public DisplayListener getDisplayListener(boolean isPauseDownload){
        if(showFromFlag || showDownloadProgress || (isPauseDownload && clickDisplayOnPauseDownload) || clickRedisplayOnFailed){
            if(myListener == null){
                myListener = new MyListener();
            }
            return myListener;
        }else{
            return displayListener;
        }
    }

    @Override
    public void setDisplayListener(DisplayListener displayListener) {
        this.displayListener = displayListener;
    }

    @Override
    public ProgressListener getProgressListener(){
        if(showDownloadProgress){
            if(myListener == null){
                myListener = new MyListener();
            }
            return myListener;
        }else{
            return progressListener;
        }
    }

    @Override
    public void setProgressListener(ProgressListener progressListener) {
        this.progressListener = progressListener;
    }

    @Override
    public Request getDisplayRequest() {
        return displayRequest;
    }

    @Override
    public void setDisplayRequest(Request displayRequest) {
        this.displayRequest = displayRequest;
    }

    @Override
    public DisplayParams getDisplayParams() {
        return displayParams;
    }

    @Override
    public void setDisplayParams(DisplayParams displayParams) {
        this.displayParams = displayParams;
        this.isSetImage = true;
        if(replacedClickListener){
            setOnClickListener(onClickListener);
            if(onClickListener == null){
                setClickable(false);
            }
            replacedClickListener = false;
        }
    }

    /**
     * Sets whether animation position is saved in {@link #onSaveInstanceState()} and restored
     * in {@link #onRestoreInstanceState(Parcelable)}
     *
     * @param freezesAnimation whether animation position is saved
     */
    public void setFreezesAnimation(boolean freezesAnimation) {
        mFreezesAnimation = freezesAnimation;
    }

    /**
     * 设置当暂停下载的时候点击显示图片
     * @param clickDisplayOnPauseDownload true：是
     */
    public void setClickDisplayOnPauseDownload(boolean clickDisplayOnPauseDownload) {
        this.clickDisplayOnPauseDownload = clickDisplayOnPauseDownload;
    }

    /**
     * 设置当失败的时候点击重新显示图片
     * @param clickRedisplayOnFailed true：是
     */
    public void setClickRedisplayOnFailed(boolean clickRedisplayOnFailed) {
        this.clickRedisplayOnFailed = clickRedisplayOnFailed;
    }

    /**
     * 设置是否显示点击涟漪效果，开启后按下的时候会在ImageView表面显示一个黑色半透明的涟漪效果，此功能需要注册点击事件或设置Clickable为true
     * @param showClickRipple 是否显示点击涟漪效果
     */
    public void setShowClickRipple(boolean showClickRipple) {
        this.showClickRipple = showClickRipple;
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
     * 是否显示GIF标识
     * @return 是否显示GIF标识
     */
    public boolean isShowGifFlag() {
        return showGifFlag;
    }

    /**
     * 设置是否显示GIF标识
     * @param showGifFlag 是否显示GIF标识
     */
    public void setShowGifFlag(boolean showGifFlag) {
        this.showGifFlag = showGifFlag;
    }

    /**
     * 获取GIF图片标识
     * @return GIF图片标识
     */
    public Drawable getGifFlagDrawable() {
        return gifFlagDrawable;
    }

    /**
     * 设置GIF图片标识
     * @param gifFlagDrawable GIF图片标识
     */
    public void setGifFlagDrawable(Drawable gifFlagDrawable) {
        this.gifFlagDrawable = gifFlagDrawable;
        if(this.gifFlagDrawable != null){
            this.gifFlagDrawable.setBounds(0 , 0, this.gifFlagDrawable.getIntrinsicWidth(), this.gifFlagDrawable.getIntrinsicHeight());
        }
    }

    /**
     * 设置GIF图片标识
     * @param gifFlagResId GIF图片标识
     */
    public void setGifFlagDrawable(int gifFlagResId) {
        this.gifFlagDrawable = getResources().getDrawable(gifFlagResId);
        if(this.gifFlagDrawable != null){
            this.gifFlagDrawable.setBounds(0 , 0, this.gifFlagDrawable.getIntrinsicWidth(), this.gifFlagDrawable.getIntrinsicHeight());
        }
    }

    /**
     * 获取图片形状
     * @return 图片形状
     */
    public ImageShape getImageShape() {
        return imageShape;
    }

    /**
     * 设置图片形状
     * @param imageShape 图片形状
     */
    public void setImageShape(ImageShape imageShape) {
        this.imageShape = imageShape;
    }

    /**
     * 设置圆角半径
     * @return 圆角半径
     */
    public float[] getRoundedRadii() {
        return roundedRadii;
    }

    /**
     * 设置圆角半径
     * @param roundedRadii 圆角半径
     */
    public void setRoundedRadii(float[] roundedRadii) {
        this.roundedRadii = roundedRadii;
    }

    private void postInit(GifViewUtils.InitResult result) {
        mFreezesAnimation = result.mFreezesAnimation;
        if (result.mSourceResId > 0) {
            super.setImageResource(result.mSourceResId);
        }
        if (result.mBackgroundResId > 0) {
            super.setBackgroundResource(result.mBackgroundResId);
        }
    }

    /**
     * 计算涟漪的半径
     * @return 涟漪的半径
     */
    private int computeRippleRadius(){
        // 先计算按下点到四边的距离
        int toLeftDistance = touchX - getPaddingLeft();
        int toTopDistance = touchY - getPaddingTop();
        int toRightDistance = Math.abs(getWidth() - getPaddingRight() - touchX);
        int toBottomDistance = Math.abs(getHeight() - getPaddingBottom() - touchY);

        // 当按下位置在第一或第四象限的时候，比较按下位置在左上角到右下角这条线上距离谁最远就以谁为半径，否则在左下角到右上角这条线上比较
        int centerX = getWidth()/2;
        int centerY = getHeight()/2;
        if((touchX < centerX && touchY < centerY) || (touchX > centerX && touchY > centerY)) {
            int toLeftTopDistance = (int) Math.sqrt((toLeftDistance * toLeftDistance) + (toTopDistance * toTopDistance));
            int toRightBottomDistance = (int) Math.sqrt((toRightDistance * toRightDistance) + (toBottomDistance * toBottomDistance));
            return toLeftTopDistance > toRightBottomDistance ? toLeftTopDistance : toRightBottomDistance;
        }else{
            int toLeftBottomDistance = (int) Math.sqrt((toLeftDistance * toLeftDistance) + (toBottomDistance * toBottomDistance));
            int toRightTopDistance = (int) Math.sqrt((toRightDistance * toRightDistance) + (toTopDistance * toTopDistance));
            return toLeftBottomDistance > toRightTopDistance ? toLeftBottomDistance : toRightTopDistance;
        }
    }

    private static boolean isGifImage(Drawable newDrawable){
        if(newDrawable == null){
            return false;
        }

        if(newDrawable instanceof TransitionDrawable){
            TransitionDrawable transitionDrawable = (TransitionDrawable) newDrawable;
            if(transitionDrawable.getNumberOfLayers() >= 2){
                newDrawable = transitionDrawable.getDrawable(1);
            }
        }
        return newDrawable instanceof RecycleDrawableInterface && "image/gif".equals(((RecycleDrawableInterface) newDrawable).getMimeType());
    }

    /**
     * 修改Drawable显示状态
     * @param callingStation 调用位置
     * @param drawable Drawable
     * @param isDisplayed 是否已显示
     */
    private static void notifyDrawable(String callingStation, Drawable drawable, final boolean isDisplayed) {
        if(drawable instanceof BindBitmapDrawable){
            BindBitmapDrawable bindBitmapDrawable = (BindBitmapDrawable) drawable;
            DisplayRequest displayRequest = bindBitmapDrawable.getDisplayRequest();
            if(displayRequest != null && !displayRequest.isFinished()){
                displayRequest.cancel();
            }
        }else if (drawable instanceof RecycleDrawableInterface) {
            ((RecycleDrawableInterface) drawable).setIsDisplayed(callingStation, isDisplayed);
        } else if (drawable instanceof LayerDrawable) {
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
        public void onCompleted(ImageFrom imageFrom, String mimeType) {
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
                displayListener.onCompleted(imageFrom, mimeType);
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
            if(clickRedisplayOnFailed){
                SketchImageView.super.setOnClickListener(this);
                replacedClickListener = true;
            }
            if (displayListener != null){
                displayListener.onFailed(failCause);
            }
        }

        @Override
        public void onCanceled(CancelCause cancelCause) {
            if(cancelCause != null && cancelCause == CancelCause.PAUSE_DOWNLOAD && clickDisplayOnPauseDownload){
                SketchImageView.super.setOnClickListener(this);
                replacedClickListener = true;
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
            if(displayParams != null){
                Sketch.with(getContext()).display(displayParams, SketchImageView.this).requestLevel(RequestLevel.NET).commit();
            }
        }
    }

    /**
     * 图片形状
     */
    public enum ImageShape{
        RECT,
        CIRCLE,
        ROUNDED_RECT,
    }
}