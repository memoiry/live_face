/*
 * Copyright 2016-present Tzutalin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tzutalin.dlibtest;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import com.tzutalin.dlib.Constants;
import com.tzutalin.dlib.FaceDet;
import com.tzutalin.dlib.VisionDetRet;

import junit.framework.Assert;

import java.io.File;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.lang.System.*;
import java.lang.Math;

/**
 * Class that takes in preview frames and converts the image to Bitmaps to process with dlib lib.
 */
public class OnGetImageListener implements OnImageAvailableListener {
    private static final boolean SAVE_PREVIEW_BITMAP = false;

    //324, 648, 972, 1296, 224, 448, 672, 976, 1344
    private static final int INPUT_SIZE = 976;
    private static final String TAG = "OnGetImageListener";

    private int mScreenRotation = 90;

    private List<VisionDetRet> results;
    private Rect boudingBox;
    private int mPreviewWdith = 0;
    private int mPreviewHeight = 0;
    private byte[][] mYUVBytes;
    private int[] mRGBBytes = null;
    private Bitmap mRGBframeBitmap = null;
    private Bitmap mCroppedBitmap = null;
    private Bitmap mResizedBitmap = null;
    private Bitmap mInversedBipmap = null;

    private boolean mIsComputing = false;
    private Handler mInferenceHandler;

    private Context mContext;
    private FaceDet mFaceDet;
    private TrasparentTitleView mTransparentTitleView;
    private FloatingCameraWindow mWindow;
    private Paint mFaceLandmardkPaint;
    private Paint mFaceDectPaint;
    private Boolean headRotate;

    private int mframeNum = 0;
    private int mframe = 0;
    private int mStartNumLeft;
    private int mStartNumRight;
    private int mStartNumLeftTemp;
    private int mStartNumRightTemp;
    private String headInfo;

    public void initialize(
            final Context context,
            final AssetManager assetManager,
            final TrasparentTitleView scoreView,
            final Handler handler) {
        this.mContext = context;
        this.mTransparentTitleView = scoreView;
        this.mInferenceHandler = handler;
        mFaceDet = new FaceDet(Constants.getFaceShapeModelPath());
        mWindow = new FloatingCameraWindow(mContext);

        mFaceLandmardkPaint = new Paint();
        mFaceLandmardkPaint.setColor(Color.GREEN);
        mFaceLandmardkPaint.setStrokeWidth(2);
        mFaceLandmardkPaint.setStyle(Paint.Style.STROKE);

        mFaceDectPaint = new Paint();
        mFaceDectPaint.setColor(Color.GREEN);
        mFaceDectPaint.setStrokeWidth(2);
        mFaceDectPaint.setStyle(Paint.Style.STROKE);
    }

    public void deInitialize() {
        synchronized (OnGetImageListener.this) {
            if (mFaceDet != null) {
                mFaceDet.release();
            }

            if (mWindow != null) {
                mWindow.release();
            }
        }
    }

    private static double getDistance(Point p1, Point p2){
        return Math.sqrt((p2.x-p1.x)*(p2.x-p1.x)+(p2.y-p1.y)*(p2.y-p1.y));
    }

    private static double ear(Point p1, Point p2, Point p3, Point p4, Point p5, Point p6){
        return (getDistance(p2, p6) + getDistance(p3, p5))/(2 * getDistance(p1, p4));
    }

    private void drawResizedBitmap(final Bitmap src, final Bitmap dst) {

        Display getOrient = ((WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        Point point = new Point();
        getOrient.getSize(point);
        int screen_width = point.x;
        int screen_height = point.y;
        Log.d(TAG, String.format("screen size (%d,%d)", screen_width, screen_height));
        if (screen_width < screen_height) {
            orientation = Configuration.ORIENTATION_PORTRAIT;
            mScreenRotation = -90;
        } else {
            orientation = Configuration.ORIENTATION_LANDSCAPE;
            mScreenRotation = 0;
        }

        Assert.assertEquals(dst.getWidth(), dst.getHeight());
        final float minDim = Math.min(src.getWidth(), src.getHeight());

        final Matrix matrix = new Matrix();

        // We only want the center square out of the original rectangle.
        final float translateX = -Math.max(0, (src.getWidth() - minDim) / 2);
        final float translateY = -Math.max(0, (src.getHeight() - minDim) / 2);
        matrix.preTranslate(translateX, translateY);

        final float scaleFactor = dst.getHeight() / minDim;
        matrix.postScale(scaleFactor, scaleFactor);

        // Rotate around the center if necessary.
        if (mScreenRotation != 0) {
            matrix.postTranslate(-dst.getWidth() / 2.0f, -dst.getHeight() / 2.0f);
            matrix.postRotate(mScreenRotation);
            matrix.postTranslate(dst.getWidth() / 2.0f, dst.getHeight() / 2.0f);
        }

        final Canvas canvas = new Canvas(dst);
        canvas.drawBitmap(src, matrix, null);
    }

    public Bitmap imageSideInversion(Bitmap src){
        Matrix sideInversion = new Matrix();
        sideInversion.setScale(-1, 1);
        Bitmap inversedImage = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), sideInversion, false);
        return inversedImage;
    }

    @Override
    public void onImageAvailable(final ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            // No mutex needed as this method is not reentrant.
            if (mIsComputing) {
                image.close();
                return;
            }
            mIsComputing = true;

            Trace.beginSection("imageAvailable");

            final Plane[] planes = image.getPlanes();

            // Initialize the storage bitmaps once when the resolution is known.
            if (mPreviewWdith != image.getWidth() || mPreviewHeight != image.getHeight()) {
                mPreviewWdith = image.getWidth();
                mPreviewHeight = image.getHeight();

                //Log.d(TAG, String.format("Initializing at size %dx%d", mPreviewWdith, mPreviewHeight));
                mRGBBytes = new int[mPreviewWdith * mPreviewHeight];
                mRGBframeBitmap = Bitmap.createBitmap(mPreviewWdith, mPreviewHeight, Config.ARGB_8888);
                mCroppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Config.ARGB_8888);

                mYUVBytes = new byte[planes.length][];
                for (int i = 0; i < planes.length; ++i) {
                    mYUVBytes[i] = new byte[planes[i].getBuffer().capacity()];
                }
            }

            for (int i = 0; i < planes.length; ++i) {
                planes[i].getBuffer().get(mYUVBytes[i]);
            }

            final int yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            ImageUtils.convertYUV420ToARGB8888(
                    mYUVBytes[0],
                    mYUVBytes[1],
                    mYUVBytes[2],
                    mRGBBytes,
                    mPreviewWdith,
                    mPreviewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    false);

            image.close();
        } catch (final Exception e) {
            if (image != null) {
                image.close();
            }
            //Log.e(TAG, "Exception!", e);
            Trace.endSection();
            return;
        }

        mRGBframeBitmap.setPixels(mRGBBytes, 0, mPreviewWdith, 0, 0, mPreviewWdith, mPreviewHeight);
        drawResizedBitmap(mRGBframeBitmap, mCroppedBitmap);

        mInversedBipmap = imageSideInversion(mCroppedBitmap);
        mResizedBitmap = Bitmap.createScaledBitmap(mInversedBipmap, (int)(INPUT_SIZE/4.5), (int)(INPUT_SIZE/4.5), true);

        mInferenceHandler.post(
                new Runnable() {
                    @Override
                    public void run() {

                        if (!new File(Constants.getFaceShapeModelPath()).exists()) {
                            mTransparentTitleView.setText("Copying landmark model to " + Constants.getFaceShapeModelPath());
                            FileUtils.copyFileFromRawToOthers(mContext, R.raw.shape_predictor_68_face_landmarks, Constants.getFaceShapeModelPath());
                        }

                        //if(mframeNum % 3 == 0){
                            long startTime = System.currentTimeMillis();
                            synchronized (OnGetImageListener.this) {
                                results = mFaceDet.detect(mResizedBitmap);
                            }
			                long endTime = System.currentTimeMillis();
                        //}

                        // Draw on bitmap
                        if (results.size() != 0) {
                            mframe = mframe + 1;
                            for (final VisionDetRet ret : results) {
                                float resizeRatio = 4.5f;
                                Canvas canvas = new Canvas(mInversedBipmap);

                                // Draw landmark
                                ArrayList<Point> landmarks = ret.getFaceLandmarks();
                                //for (Point point : landmarks) {
                                //    int pointX = (int) (point.x * resizeRatio);
                                //    int pointY = (int) (point.y * resizeRatio);
                                //    canvas.drawCircle(pointX, pointY, 4, mFaceLandmardkPaint);
                                //}
                                // Face detection result

                                int left = (int) (ret.getLeft() * resizeRatio);
                                int top = (int) (ret.getTop() * resizeRatio);
                                int right = (int) (ret.getRight() * resizeRatio);
                                int bottom = (int) (ret.getBottom() * resizeRatio);
                                Rect boudingBox = new Rect(left, top, right, bottom);

                                // Face landmanr result

                                Point right_pupil = new Point();
                                Point left_pupil = new Point();
                                int temp_x = 0;
                                int temp_y = 0;
                                for (int i = 36; i < 42; i++){
                                    temp_x = temp_x + (int) (landmarks.get(i).x * resizeRatio);
                                    temp_y = temp_y + (int) (landmarks.get(i).y * resizeRatio);
                                }
                                right_pupil.x = temp_x / 6;
                                right_pupil.y = temp_y / 6;

                                temp_x = 0;
                                temp_y = 0;


                                for (int i = 42; i < 48; i++){
                                    temp_x = temp_x + (int) (landmarks.get(i).x * resizeRatio) ;
                                    temp_y = temp_y + (int) (landmarks.get(i).y * resizeRatio);
                                }
                                left_pupil.x = temp_x / 6;
                                left_pupil.y = temp_y / 6;

                                landmarks.get(30).x = (int) (landmarks.get(30).x * resizeRatio);
                                landmarks.get(48).x = (int) (landmarks.get(48).x * resizeRatio);
                                landmarks.get(54).x = (int) (landmarks.get(54).x * resizeRatio);


                                landmarks.get(30).y = (int) (landmarks.get(30).y * resizeRatio);
                                landmarks.get(48).y = (int) (landmarks.get(48).y * resizeRatio);
                                landmarks.get(54).y = (int) (landmarks.get(54).y * resizeRatio);


                                ArrayList<Point> alignLandmark = new ArrayList<Point>();
                                alignLandmark.add(left_pupil);
                                alignLandmark.add(right_pupil);
                                alignLandmark.add(landmarks.get(30));
                                alignLandmark.add(landmarks.get(48));
                                alignLandmark.add(landmarks.get(54));

                                for (Point point : alignLandmark) {
                                    int pointX = point.x;
                                    int pointY = point.y;
                                    canvas.drawCircle(pointX, pointY, 4, mFaceLandmardkPaint);
                                }


                                canvas.drawRect(left, top, right, bottom, mFaceDectPaint);


                                // get 5 landmarks
                                for (int i = 0; i < 5; i ++){
                                    alignLandmark.get(i).x = alignLandmark.get(i).x - left;
                                    alignLandmark.get(i).y = alignLandmark.get(i).y - top;
                                }
                                // Eye detection
                                //double testss = getDistance(landmarks.get(36), landmarks.get(37));
                                double rightEAR = ear(landmarks.get(36),
                                        landmarks.get(37),landmarks.get(38),
                                        landmarks.get(39),landmarks.get(40),
                                        landmarks.get(41))* resizeRatio;
                                double leftEAR = ear(landmarks.get(42),
                                        landmarks.get(43),landmarks.get(44),
                                        landmarks.get(45),landmarks.get(46),
                                        landmarks.get(47))* resizeRatio;
                                double earAve = (rightEAR + leftEAR)/2;

                                mStartNumRightTemp = landmarks.get(30).x - right_pupil.x;
                                mStartNumLeftTemp = left_pupil.x - landmarks.get(30).x;
                                if (Math.abs(mStartNumRightTemp - mStartNumLeftTemp) < 10){
                                    mStartNumRight = mStartNumRightTemp;
                                    mStartNumLeft = mStartNumLeftTemp;
                                }
                                double RDetect = (left_pupil.x - landmarks.get(30).x)/(float) (mStartNumLeft);
                                double LDetect = (landmarks.get(30).x - right_pupil.x)/(float) (mStartNumRight);
                                if (RDetect < 0.5 || LDetect < 0.5){
                                    headRotate = true;
                                    headInfo =  String.format("Rotating");
                                }
                                else {
                                    headRotate = false;
                                    headInfo =  String.format(" ");
                                }


                                mTransparentTitleView.setText("T: " + String.valueOf((endTime - startTime) / 1000f) +
                                        " EAR: " + String.format("%.2f",earAve) + headInfo);
                                //mTransparentTitleView.setText("Time cost: " + String.valueOf((endTime - startTime) / 1000f) + " sec");

                            }
                        }

                        mframeNum++;
                        mWindow.setRGBBitmap(mInversedBipmap);
                        mIsComputing = false;
                    }

                });

        Trace.endSection();
    }
}
