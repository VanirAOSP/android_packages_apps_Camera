/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Message;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.camera.R;

import java.util.ArrayList;
import java.util.List;

public class PieRenderer extends OverlayRenderer
        implements FocusIndicator {

    private static final String TAG = "CAM Pie";

    // Sometimes continuous autofocus starts and stops several times quickly.
    // These states are used to make sure the animation is run for at least some
    // time.
    
    private int mState;
    private ScaleAnimation mAnimation = new ScaleAnimation();
    private static final int STATE_IDLE = 0;
    private static final int STATE_FOCUSING = 1;
    private static final int STATE_FINISHING = 2;
    private static final int STATE_PIE = 3;

    private Runnable mDisappear = new Disappear();
    private Animation.AnimationListener mEndAction = new EndAction();
    private static final int SCALING_UP_TIME = 1000;
    private static final int SCALING_DOWN_TIME = 200;
    private static final int DISAPPEAR_TIMEOUT = 200;
    private static final int DIAL_HORIZONTAL = 157;

    private static final long PIE_OPEN_DELAY = 200;

    private static final int MSG_OPEN = 2;
    private static final int MSG_CLOSE = 3;
    private static final int MSG_SUBMENU = 4;
    private static final float PIE_SWEEP = (float)(Math.PI * 2 / 3);
    // geometry
    private Point mCenter;
    private int mRadius;
    private int mRadiusInc;
    private int mSlop;

    // the detection if touch is inside a slice is offset
    // inbounds by this amount to allow the selection to show before the
    // finger covers it
    private int mTouchOffset;

    private List<PieItem> mItems;

    private PieItem mOpenItem;

    private Paint mSelectedPaint;
    private Paint mSubPaint;

    // touch handling
    private PieItem mCurrentItem;

    private Paint mFocusPaint;
    private int mSuccessColor;
    private int mFailColor;

    private int mCircleSize;
    private int mFocusX;
    private int mFocusY;
    private int mCenterX;
    private int mCenterY;

    private int mDialAngle;
    private RectF mCircle;
    private RectF mDial;
    private Point mPoint1;
    private Point mPoint2;
    private int mStartAnimationAngle;
    private boolean mFocused;
    private int mInnerOffset;
    private int mOuterStroke;
    private int mInnerStroke;
    private boolean mFocusFromTap;
    private boolean mTapMode;
    private boolean mBlockFocus;

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case MSG_OPEN:
                if (mListener != null) {
                    mListener.onPieOpened(mCenter.x, mCenter.y);
                }
                break;
            case MSG_CLOSE:
                if (mListener != null) {
                    mListener.onPieClosed();
                }
                break;
            }
        }
    };

    private PieListener mListener;

    static public interface PieListener {
        public void onPieOpened(int centerX, int centerY);
        public void onPieClosed();
    }

    public void setPieListener(PieListener pl) {
        mListener = pl;
    }

    public PieRenderer(Context context) {
        init(context);
    }

    private void init(Context ctx) {
        setVisible(false);
        mItems = new ArrayList<PieItem>();
        Resources res = ctx.getResources();
        mRadius = (int) res.getDimensionPixelSize(R.dimen.pie_radius_start);
        mCircleSize = mRadius - res.getDimensionPixelSize(R.dimen.focus_radius_offset);
        mRadiusInc =  (int) res.getDimensionPixelSize(R.dimen.pie_radius_increment);
        mTouchOffset = (int) res.getDimensionPixelSize(R.dimen.pie_touch_offset);
        mCenter = new Point(0,0);
        mSelectedPaint = new Paint();
        mSelectedPaint.setColor(Color.argb(255, 51, 181, 229)); //res.getColor(R.color.qc_selected));
        mSelectedPaint.setAntiAlias(true);
        mSubPaint = new Paint();
        mSubPaint.setAntiAlias(true);
        mSubPaint.setColor(Color.argb(200, 250, 230, 128)); //res.getColor(R.color.qc_sub));
        mFocusPaint = new Paint();
        mFocusPaint.setAntiAlias(true);
        mFocusPaint.setColor(Color.WHITE);
        mFocusPaint.setStyle(Paint.Style.STROKE);
        mSuccessColor = Color.GREEN;
        mFailColor = Color.RED;
        mCircle = new RectF();
        mDial = new RectF();
        mPoint1 = new Point();
        mPoint2 = new Point();
        mInnerOffset = res.getDimensionPixelSize(R.dimen.focus_inner_offset);
        mOuterStroke = res.getDimensionPixelSize(R.dimen.focus_outer_stroke);
        mInnerStroke = res.getDimensionPixelSize(R.dimen.focus_inner_stroke);
        mState = STATE_IDLE;
        mBlockFocus = false;
    }

    public boolean showsItems() {
        return mTapMode;
    }

    public void addItem(PieItem item) {
        // add the item to the pie itself
        mItems.add(item);
    }

    public void removeItem(PieItem item) {
        mItems.remove(item);
    }

    public void clearItems() {
        mItems.clear();
    }

    public void showInCenter() {
        if (isVisible()) {
            mTapMode = false;
            show(false);
        } else {
            setCenter(mCenterX, mCenterY);
            mTapMode = true;
            show(true);
        }
    }

    /**
     * guaranteed has center set
     * @param show
     */
    private void show(boolean show) {
        if (show) {
            mState = STATE_PIE;
            // ensure clean state
            mCurrentItem = null;
            mOpenItem = null;
            for (PieItem item : mItems) {
                item.setSelected(false);
            }
            layoutPie();
        } else {
            mState = STATE_IDLE;
        }
        setVisible(show);
        mHandler.sendEmptyMessage(show ? MSG_OPEN : MSG_CLOSE);
    }

    private void fadeIn() {
        mFadeIn = new LinearAnimation(0, 1);
        mFadeIn.setDuration(PIE_FADE_IN_DURATION);
        mFadeIn.setAnimationListener(new AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                mFadeIn = null;
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        mFadeIn.startNow();
        mOverlay.startAnimation(mFadeIn);
    }

    public void setCenter(int x, int y) {
        mCenter.x = x;
        mCenter.y = y;
        // when using the pie menu, align the focus ring
        alignFocus(x, y);
    }

    private void setupPie(int x, int y) {
        // when using the focus ring, align pie items
        mCenter.x = x;
        mCenter.y = y;
        mCurrentItem = null;
        mOpenItem = null;
        for (PieItem item : mItems) {
            item.setSelected(false);
        }
        layoutPie();
    }

    private void layoutPie() {
        int rgap = 2;
        int inner = mRadius + rgap;
        int outer = mRadius + mRadiusInc - rgap;
        int gap = 1;
        layoutItems(mItems, (float) (Math.PI / 2), inner, outer, gap);
    }

    private void layoutItems(List<PieItem> items, float centerAngle, int inner,
            int outer, int gap) {
        float emptyangle = PIE_SWEEP / 16;
        float sweep = (float) (PIE_SWEEP - 2 * emptyangle) / items.size();
        float angle = centerAngle - PIE_SWEEP / 2 + emptyangle + sweep / 2;
        // check if we have custom geometry
        // first item we find triggers custom sweep for all
        // this allows us to re-use the path
        for (PieItem item : items) {
            if (item.getCenter() >= 0) {
                sweep = item.getSweep();
                break;
            }
        }
        Path path = makeSlice(getDegrees(0) - gap, getDegrees(sweep) + gap,
                outer, inner, mCenter);
        for (PieItem item : items) {
            // shared between items
            item.setPath(path);
            if (item.getCenter() >= 0) {
                angle = item.getCenter();
            }
            int w = item.getIntrinsicWidth();
            int h = item.getIntrinsicHeight();
            // move views to outer border
            int r = inner + (outer - inner) * 2 / 3;
            int x = (int) (r * Math.cos(angle));
            int y = mCenter.y - (int) (r * Math.sin(angle)) - h / 2;
            x = mCenter.x + x - w / 2;
            item.setBounds(x, y, x + w, y + h);
            float itemstart = angle - sweep / 2;
            item.setGeometry(itemstart, sweep, inner, outer);
            if (item.hasItems()) {
                layoutItems(item.getItems(), angle, inner,
                        outer + mRadiusInc / 2, gap);
            }
            angle += sweep;
        }
    }

    private Path makeSlice(float start, float end, int outer, int inner, Point center) {
        RectF bb =
                new RectF(center.x - outer, center.y - outer, center.x + outer,
                        center.y + outer);
        RectF bbi =
                new RectF(center.x - inner, center.y - inner, center.x + inner,
                        center.y + inner);
        Path path = new Path();
        path.arcTo(bb, start, end - start, true);
        path.arcTo(bbi, end, start - end);
        path.close();
        return path;
    }

    /**
     * converts a
     * @param angle from 0..PI to Android degrees (clockwise starting at 3 o'clock)
     * @return skia angle
     */
    private float getDegrees(double angle) {
        return (float) (360 - 180 * angle / Math.PI);
    }

    private void startFadeOut() {
        if (ApiHelper.HAS_VIEW_PROPERTY_ANIMATOR) {
            mOverlay.animate().alpha(0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    deselect();
                    show(false);
                    mOverlay.setAlpha(1);
                    super.onAnimationEnd(animation);
                }
            }).setDuration(PIE_SELECT_FADE_DURATION);
        } else {
            deselect();
            show(false);
        }
    }

    @Override
    public void onDraw(Canvas canvas) {
        drawFocus(canvas);
        if (mState == STATE_FINISHING) return;
        if (mOpenItem == null) {
            // draw base menu
            for (PieItem item : mItems) {
                drawItem(canvas, item, alpha);
            }
        }
        if (mOpenItem != null) {
            for (PieItem inner : mOpenItem.getItems()) {
                drawItem(canvas, inner, (mXFade != null) ? (1 - 0.5f * alpha) : 1);
            }
        }
        canvas.restoreToCount(state);
    }

    private void drawItem(Canvas canvas, PieItem item) {
        if (item.getView() != null) {
            if (mState == STATE_PIE) {
                if (item.getPath() != null) {
                    Paint p = item.isSelected() ? mSelectedPaint : mNormalPaint;
                    int state = canvas.save();
                    float r = getDegrees(item.getStartAngle());
                    canvas.rotate(r, mCenter.x, mCenter.y);
                    canvas.drawPath(item.getPath(), p);
                    canvas.restoreToCount(state);
                    // draw the item view
                    View view = item.getView();
                    state = canvas.save();
                    canvas.translate(view.getX(), view.getY());
                    view.draw(canvas);
                    canvas.restoreToCount(state);
                }
                
                // draw the item view
                state = canvas.save();
                if (mFadeIn != null) {
                    float sf = 0.9f + alpha * 0.1f;
                    canvas.scale(sf, sf, mCenter.x, mCenter.y);
                }
                item.setAlpha(alpha);
                item.draw(canvas);
                canvas.restoreToCount(state);
            }
        }
    }
    // touch handling for pie

    @Override
    public boolean onTouchEvent(MotionEvent evt) {
        float x = evt.getX();
        float y = evt.getY();
        int action = evt.getActionMasked();
        PointF polar = getPolar(x, y, !(mTapMode));
        if (MotionEvent.ACTION_DOWN == action) {
            mDown.x = (int) evt.getX();
            mDown.y = (int) evt.getY();
            mOpening = false;
            if (mTapMode) {
                PieItem item = findItem(polar);
                if ((item != null) && (mCurrentItem != item)) {
                    mState = STATE_PIE;
                    onEnter(item);
                }
            } else {
                setCenter((int) x, (int) y);
                show(true);
            }
            return true;
        } else if (MotionEvent.ACTION_UP == action) {
            if (isVisible()) {
                PieItem item = mCurrentItem;
                if (mTapMode) {
                    item = findItem(polar);
                    if (item != null && mOpening) {
                        mOpening = false;
                        return true;
                    }
                }
                if (item == null) {
                    mTapMode = false;
                    show(false);
                } else if (!mOpening
                        && !item.hasItems()) {
                    item.performClick();
                    startFadeOut();
                    mTapMode = false;

                } else {
                    if (!item.hasItems()) {
                        show(false);
                        mTapMode = false;
                        mState = STATE_IDLE;
                        item.getView().performClick();
                        item.setSelected(false);
                    }
                }
                return true;
            } else if (isVisible()) {
                PieItem item = mCurrentItem;
                deselect();
                show(false);
                if ((item != null) && (item.getView() != null)) {
                    item.getView().performClick();
                }
                return true;
            }
        } else if (MotionEvent.ACTION_CANCEL == action) {
            if (isVisible() || mTapMode) {
                show(false);
            }
            deselect();
            return false;
        } else if (MotionEvent.ACTION_MOVE == action) {
            if (polar.y < mRadius) {
                if (mOpenItem != null) {
                    mOpenItem = null;
                } else {
                    deselect();
                }
                return false;
            }
            PieItem item = findItem(polar);
            if ((item != null) && (mCurrentItem != item)) {
                onEnter(item);
            }
        }
        return false;
    }

    private boolean hasMoved(MotionEvent e) {
        return mTouchSlopSquared < (e.getX() - mDown.x) * (e.getX() - mDown.x)
                + (e.getY() - mDown.y) * (e.getY() - mDown.y);
    }

    /**
     * enter a slice for a view
     * updates model only
     * @param item
     */
    private void onEnter(PieItem item) {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (item != null && item.isEnabled()) {
            item.setSelected(true);
            mCurrentItem = item;
            if ((mCurrentItem != mOpenItem) && mCurrentItem.hasItems()) {
                openCurrentItem();
            }
        } else {
            mCurrentItem = null;
        }
    }

    private void deselect() {
        if (mCurrentItem != null) {
            mCurrentItem.setSelected(false);
        }
        if (mOpenItem != null) {
            mOpenItem = null;
        }
        mCurrentItem = null;
    }

    private void openCurrentItem() {
        if ((mCurrentItem != null) && mCurrentItem.hasItems()) {
            mCurrentItem.setSelected(false);
            mOpenItem = mCurrentItem;
            mOpening = true;
            mXFade = new LinearAnimation(1, 0);
            mXFade.setDuration(PIE_XFADE_DURATION);
            mXFade.setAnimationListener(new AnimationListener() {
                @Override
                public void onAnimationStart(Animation animation) {
                }

                @Override
                public void onAnimationEnd(Animation animation) {
                    mXFade = null;
                }

                @Override
                public void onAnimationRepeat(Animation animation) {
                }
            });
            mXFade.startNow();
            mOverlay.startAnimation(mXFade);
        }
    }

    private PointF getPolar(float x, float y, boolean useOffset) {
        PointF res = new PointF();
        // get angle and radius from x/y
        res.x = (float) Math.PI / 2;
        x = x - mCenter.x;
        y = mCenter.y - y;
        res.y = (float) Math.sqrt(x * x + y * y);
        if (x != 0) {
            res.x = (float) Math.atan2(y,  x);
            if (res.x < 0) {
                res.x = (float) (2 * Math.PI + res.x);
            }
        }
        res.y = res.y + (useOffset ? mTouchOffset : 0);
        return res;
    }

    /**
     * @param polar x: angle, y: dist
     * @return the item at angle/dist or null
     */
    private PieItem findItem(PointF polar) {
        // find the matching item:
        List<PieItem> items = (mOpenItem != null) ? mOpenItem.getItems() : mItems;
        for (PieItem item : items) {
            if (inside(polar, item)) {
                return item;
            }
        }
        return null;
    }

    private boolean inside(PointF polar, PieItem item) {
        return (item.getInnerRadius() < polar.y)
                && (item.getStartAngle() < polar.x)
                && (item.getStartAngle() + item.getSweep() > polar.x)
                && (!mTapMode || (item.getOuterRadius() > polar.y));
    }

    @Override
    public boolean handlesTouch() {
        return true;
    }

    // focus specific code

    public void setBlockFocus(boolean blocked) {
        mBlockFocus = blocked;
        if (blocked) {
            clear();
        }
    }

    public void setFocus(int x, int y, boolean startImmediately) {
        mFocusFromTap = true;
        mTapMode = true;
        switch(mOverlay.getOrientation()) {
        case 0:
            mFocusX = x;
            mFocusY = y;
            break;
        case 180:
            mFocusX = getWidth() - x;
            mFocusY = getHeight() - y;
            break;
        case 90:
            mFocusX = getWidth() - y;
            mFocusY = x;
            break;
        case 270:
            mFocusX = y ;
            mFocusY = getHeight() - x;
            break;
        }
        setCircle(mFocusX, mFocusY);
        setupPie(mFocusX, mFocusY);
    }

    public void alignFocus(int x, int y) {
        mOverlay.removeCallbacks(mDisappear);
        mAnimation.cancel();
        mAnimation.reset();
        mFocusX = x;
        mFocusY = y;
        mDialAngle = DIAL_HORIZONTAL;
        setCircle(x, y);
        mFocused = false;
    }

    public int getSize() {
        return 2 * mCircleSize;
    }

    private int getRandomAngle() {
        return (int)(90 * Math.random());
    }

    private int getRandomRange() {
        return (int)(120 * Math.random());
    }

    @Override
    public void layout(int l, int t, int r, int b) {
        super.layout(l, t, r, b);
        mCircleSize = Math.min(200, Math.min(getWidth(), getHeight()) / 5);
        mCenterX = (r - l) / 2;
        mCenterY = (b - t) / 2;
        mFocusX = mCenterX;
        mFocusY = mCenterY;
        setCircle(mFocusX, mFocusY);
        if (isVisible() && mState == STATE_PIE) {
            setCenter(mCenterX, mCenterY);
            layoutPie();
        }
    }

    private void setCircle(int cx, int cy) {
        mCircle.set(cx - mCircleSize, cy - mCircleSize,
                cx + mCircleSize, cy + mCircleSize);
        mDial.set(cx - mCircleSize + mInnerOffset, cy - mCircleSize + mInnerOffset,
                cx + mCircleSize - mInnerOffset, cy + mCircleSize - mInnerOffset);
    }

    public void drawFocus(Canvas canvas) {
        if (mBlockFocus) return;
        mFocusPaint.setStrokeWidth(mOuterStroke);
        canvas.drawCircle((float) mFocusX, (float) mFocusY, (float) mCircleSize, mFocusPaint);
        Paint inner = (mFocused ? mSuccessPaint : mFocusPaint);
        inner.setStrokeWidth(mInnerStroke);
        canvas.drawArc(mDial, mDialAngle, 45, false, inner);
        canvas.drawArc(mDial, mDialAngle + 180, 45, false, inner);
        drawLine(canvas, mDialAngle, inner);
        drawLine(canvas, mDialAngle + 45, inner);
        drawLine(canvas, mDialAngle + 180, inner);
        drawLine(canvas, mDialAngle + 225, inner);
    }

    private void drawLine(Canvas canvas, int angle, Paint p) {
        convertCart(angle, mCircleSize - mInnerOffset, mPoint1);
        convertCart(angle, mCircleSize - mInnerOffset + mInnerOffset / 3, mPoint2);
        canvas.drawLine(mPoint1.x + mFocusX, mPoint1.y + mFocusY,
                mPoint2.x + mFocusX, mPoint2.y + mFocusY, p);
    }

    private static void convertCart(int angle, int radius, Point out) {
        double a = 2 * Math.PI * (angle % 360) / 360;
        out.x = (int) (radius * Math.cos(a) + 0.5);
        out.y = (int) (radius * Math.sin(a) + 0.5);
    }

    @Override
    public void showStart() {
        if (mState == STATE_IDLE) {
            int angle = getRandomAngle();
            int range = getRandomRange();
            startAnimation(R.drawable.ic_focus_focusing, SCALING_UP_TIME,
                    false, angle, angle + range);
            mState = STATE_FOCUSING;
            mStartAnimationAngle = angle;
        }
    }

    @Override
    public void showSuccess(boolean timeout) {
        if (mState == STATE_FOCUSING) {
            startAnimation(R.drawable.ic_focus_focused, SCALING_DOWN_TIME,
                    timeout, mStartAnimationAngle);
            mState = STATE_FINISHING;
            mFocused = true;
        }
    }

    @Override
    public void showFail(boolean timeout) {
        if (mState == STATE_FOCUSING) {
            startAnimation(R.drawable.ic_focus_failed, SCALING_DOWN_TIME,
                    timeout, mStartAnimationAngle);
            mState = STATE_FINISHING;
            mFocused = false;
        }
    }

    @Override
    public void clear() {
        mAnimation.cancel();
        mFocused = false;
        mOverlay.removeCallbacks(mDisappear);
        mDisappear.run();
    }

    private void startAnimation(int resid, long duration, boolean timeout,
            float toScale) {
        startAnimation(resid, duration, timeout, mDialAngle,
                toScale);
    }

    private void startAnimation(int resid, long duration, boolean timeout,
            float fromScale, float toScale) {
        setVisible(true);
        mAnimation.cancel();
        mAnimation.reset();
        mAnimation.setDuration(duration);
        mAnimation.setScale(fromScale, toScale);
        mAnimation.setAnimationListener(timeout ? mEndAction : null);
        mOverlay.startAnimation(mAnimation);
        update();
    }

    private class EndAction implements Animation.AnimationListener {
        @Override
        public void onAnimationEnd(Animation animation) {
            // Keep the focus indicator for some time.
            mOverlay.postDelayed(mDisappear, DISAPPEAR_TIMEOUT);
        }

        @Override
        public void onAnimationRepeat(Animation animation) {
        }

        @Override
        public void onAnimationStart(Animation animation) {
        }
    }

    private class Disappear implements Runnable {
        @Override
        public void run() {
            setVisible(false);
            mFocusX = mCenterX;
            mFocusY = mCenterY;
            mState = STATE_IDLE;
            setCircle(mFocusX, mFocusY);
            setupPie(mFocusX, mFocusY);
            mFocused = false;
        }
    }

    private class ScaleAnimation extends Animation {
        private float mFrom = 1f;
        private float mTo = 1f;

        public ScaleAnimation() {
            setFillAfter(true);
        }

        public void setScale(float from, float to) {
            mFrom = from;
            mTo = to;
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            mDialAngle = (int)(mFrom + (mTo - mFrom) * interpolatedTime);
        }
    }

}
