/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.example.android.wearable.watchface.watchface;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationHelperActivity;
import android.support.wearable.complications.rendering.ComplicationDrawable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextPaint;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceHolder;

import com.example.android.wearable.watchface.R;
import com.example.android.wearable.watchface.config.AnalogComplicationConfigRecyclerViewAdapter;
import com.example.android.wearable.watchface.util.GlobalUtils;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/** Demonstrates two simple complications in a watch face. */
public class AnalogComplicationWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "AnalogWatchFace";

    // Unique IDs for each complication. The settings activity that supports allowing users
    // to select their complication data provider requires numbers to be >= 0.
    private static final int BACKGROUND_COMPLICATION_ID = 0;

    private static final int LEFT_COMPLICATION_ID = 100;
    private static final int RIGHT_COMPLICATION_ID = 101;

    // Background, Left and right complication IDs as array for Complication API.
    private static final int[] COMPLICATION_IDS = {
        BACKGROUND_COMPLICATION_ID, LEFT_COMPLICATION_ID, RIGHT_COMPLICATION_ID
    };

    // Left and right dial supported types.
    private static final int[][] COMPLICATION_SUPPORTED_TYPES = {
        {ComplicationData.TYPE_LARGE_IMAGE},
        {
            ComplicationData.TYPE_RANGED_VALUE,
            ComplicationData.TYPE_ICON,
            ComplicationData.TYPE_SHORT_TEXT,
            ComplicationData.TYPE_SMALL_IMAGE
        },
        {
            ComplicationData.TYPE_RANGED_VALUE,
            ComplicationData.TYPE_ICON,
            ComplicationData.TYPE_SHORT_TEXT,
            ComplicationData.TYPE_SMALL_IMAGE
        }
    };

    // Used by {@link AnalogComplicationConfigRecyclerViewAdapter} to check if complication location
    // is supported in settings config activity.
    public static int getComplicationId(
            AnalogComplicationConfigRecyclerViewAdapter.ComplicationLocation complicationLocation) {
        // Add any other supported locations here.
        switch (complicationLocation) {
            case BACKGROUND:
                return BACKGROUND_COMPLICATION_ID;
            case LEFT:
                return LEFT_COMPLICATION_ID;
            case RIGHT:
                return RIGHT_COMPLICATION_ID;
            default:
                return -1;
        }
    }

    // Used by {@link AnalogComplicationConfigRecyclerViewAdapter} to retrieve all complication ids.
    public static int[] getComplicationIds() {
        return COMPLICATION_IDS;
    }

    // Used by {@link AnalogComplicationConfigRecyclerViewAdapter} to see which complication types
    // are supported in the settings config activity.
    public static int[] getSupportedComplicationTypes(
            AnalogComplicationConfigRecyclerViewAdapter.ComplicationLocation complicationLocation) {
        // Add any other supported locations here.
        switch (complicationLocation) {
            case BACKGROUND:
                return COMPLICATION_SUPPORTED_TYPES[0];
            case LEFT:
                return COMPLICATION_SUPPORTED_TYPES[1];
            case RIGHT:
                return COMPLICATION_SUPPORTED_TYPES[2];
            default:
                return new int[] {};
        }
    }

    /*
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        private static final int MSG_UPDATE_TIME = 0;

        private static final float HOUR_STROKE_WIDTH = 10f;
        private static final float MINUTE_STROKE_WIDTH = 3f;
        private static final float SECOND_TICK_STROKE_WIDTH = 4f;

        private static final float CENTER_GAP_AND_CIRCLE_RADIUS = 10f;

        private static final int SHADOW_RADIUS = 6;

        private Calendar mCalendar;
        private boolean mRegisteredTimeZoneReceiver = false;
        private boolean mMuteMode;

        private float mCenterX;
        private float mCenterY;

        private float mSecondHandLength;
        private float mMinuteHandLength;
        private float mHourHandLength;

        Bitmap mBackgroundBitmap;
        Bitmap mForegroundBitmap;
        Bitmap mHourBitmap;
        Bitmap mMinuteBitmap;
        Bitmap mSecondBitmap;

        Bitmap mBackgroundScaledBitmap;
        Bitmap mForegroundScaledBitmap;
        Bitmap mHourScaledBitmap;
        Bitmap mMinuteScaledBitmap;
        Bitmap mSecondScaledBitmap;

        // Colors for all hands (hour, minute, seconds, ticks) based on photo loaded.
        private int mWatchHandAndComplicationsColor;
        private int mWatchHandHighlightColor;
        private int mWatchHandShadowColor;

        private int mBackgroundColor;

        private Paint mPaint;
        private TextPaint mTextPaint;

        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mSecondAndHighlightPaint;
        private Paint mTickAndCirclePaint;

        private Paint mBackgroundPaint;

        /* Maps active complication ids to the data for that complication. Note: Data will only be
         * present if the user has chosen a provider via the settings activity for the watch face.
         */
        private SparseArray<ComplicationData> mActiveComplicationDataSparseArray;

        /* Maps complication ids to corresponding ComplicationDrawable that renders the
         * the complication data on the watch face.
         */
        private SparseArray<ComplicationDrawable> mComplicationDrawableSparseArray;

        private boolean mAmbient;
        private boolean mLowBitAmbient;
        private boolean mBurnInProtection;

        // Used to pull user's preferences for background color, highlight color, and visual
        // indicating there are unread notifications.
        SharedPreferences mSharedPref;

        // User's preference for if they want visual shown to indicate unread notifications.
        private boolean mUnreadNotificationsPreference;
        private int mNumberOfUnreadNotifications = 0;

        private final BroadcastReceiver mTimeZoneReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        mCalendar.setTimeZone(TimeZone.getDefault());
                        invalidate();
                    }
                };

        // Handler to update the time once a second in interactive mode.
        private final Handler mUpdateTimeHandler =
                new Handler() {
                    @Override
                    public void handleMessage(Message message) {
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    INTERACTIVE_UPDATE_RATE_MS
                                            - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                    }
                };

        @Override
        public void onCreate(SurfaceHolder holder) {
            Log.d(TAG, "onCreate");

            super.onCreate(holder);

            // Used throughout watch face to pull user's preferences.
            Context context = getApplicationContext();
            mSharedPref =
                    context.getSharedPreferences(
                            getString(R.string.analog_complication_preference_file_key),
                            Context.MODE_PRIVATE);

            mCalendar = Calendar.getInstance();

            setWatchFaceStyle(
                    new WatchFaceStyle.Builder(AnalogComplicationWatchFaceService.this)
                            .setAcceptsTapEvents(true)
                            .build());

            loadSavedPreferences();

            Resources resources = AnalogComplicationWatchFaceService.this.getResources();
            Drawable backgroundDrawable = resources.getDrawable(R.drawable.background);
            mBackgroundBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();

            Drawable hourDrawable = resources.getDrawable(R.drawable.hour);
            mHourBitmap = ((BitmapDrawable) hourDrawable).getBitmap();

            Drawable minuteDrawable = resources.getDrawable(R.drawable.minute);
            mMinuteBitmap = ((BitmapDrawable) minuteDrawable).getBitmap();

            Drawable foregroundDrawable = resources.getDrawable(R.drawable.foreground);
            mForegroundBitmap = ((BitmapDrawable) foregroundDrawable).getBitmap();

            Drawable secondDrawable = resources.getDrawable(R.drawable.second);
            mSecondBitmap = ((BitmapDrawable) secondDrawable).getBitmap();

            mPaint = new Paint();
            mPaint.setFilterBitmap(true);

            mTextPaint = new TextPaint();
            mTextPaint.setAntiAlias(true);

            initializeComplicationsAndBackground();
            initializeWatchFace();
        }

        // Pulls all user's preferences for watch face appearance.
        private void loadSavedPreferences() {

            String backgroundColorResourceName =
                    getApplicationContext().getString(R.string.saved_background_color);

            mBackgroundColor = mSharedPref.getInt(backgroundColorResourceName, Color.BLACK);

            String markerColorResourceName =
                    getApplicationContext().getString(R.string.saved_marker_color);

            // Set defaults for colors
            mWatchHandHighlightColor = mSharedPref.getInt(markerColorResourceName, Color.RED);

            if (mBackgroundColor == Color.WHITE) {
                mWatchHandAndComplicationsColor = Color.RED;
                mWatchHandShadowColor = Color.WHITE;
            } else {
                mWatchHandAndComplicationsColor = Color.WHITE;
                mWatchHandShadowColor = Color.BLACK;
            }

            String unreadNotificationPreferenceResourceName =
                    getApplicationContext().getString(R.string.saved_unread_notifications_pref);

            mUnreadNotificationsPreference =
                    mSharedPref.getBoolean(unreadNotificationPreferenceResourceName, true);
        }

        private void initializeComplicationsAndBackground() {
            Log.d(TAG, "initializeComplications()");

            // Initialize background color (in case background complication is inactive).
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mBackgroundColor);

            mActiveComplicationDataSparseArray = new SparseArray<>(COMPLICATION_IDS.length);

            // Creates a ComplicationDrawable for each location where the user can render a
            // complication on the watch face. In this watch face, we create one for left, right,
            // and background, but you could add many more.
            ComplicationDrawable leftComplicationDrawable =
                    new ComplicationDrawable(getApplicationContext());

            ComplicationDrawable rightComplicationDrawable =
                    new ComplicationDrawable(getApplicationContext());

            ComplicationDrawable backgroundComplicationDrawable =
                    new ComplicationDrawable(getApplicationContext());

            // Adds new complications to a SparseArray to simplify setting styles and ambient
            // properties for all complications, i.e., iterate over them all.
            mComplicationDrawableSparseArray = new SparseArray<>(COMPLICATION_IDS.length);

            mComplicationDrawableSparseArray.put(LEFT_COMPLICATION_ID, leftComplicationDrawable);
            mComplicationDrawableSparseArray.put(RIGHT_COMPLICATION_ID, rightComplicationDrawable);
            mComplicationDrawableSparseArray.put(
                    BACKGROUND_COMPLICATION_ID, backgroundComplicationDrawable);

            setComplicationsActiveAndAmbientColors(mWatchHandHighlightColor);
            setActiveComplications(COMPLICATION_IDS);
        }

        private void initializeWatchFace() {

            mHourPaint = new Paint();
            mHourPaint.setColor(mWatchHandAndComplicationsColor);
            mHourPaint.setStrokeWidth(HOUR_STROKE_WIDTH);
            mHourPaint.setAntiAlias(true);
            mHourPaint.setStrokeCap(Paint.Cap.ROUND);
            mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mMinutePaint = new Paint();
            mMinutePaint.setColor(mWatchHandAndComplicationsColor);
            mMinutePaint.setStrokeWidth(MINUTE_STROKE_WIDTH);
            mMinutePaint.setAntiAlias(true);
            mMinutePaint.setStrokeCap(Paint.Cap.ROUND);
            mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mSecondAndHighlightPaint = new Paint();
            mSecondAndHighlightPaint.setColor(mWatchHandHighlightColor);
            mSecondAndHighlightPaint.setStrokeWidth(SECOND_TICK_STROKE_WIDTH);
            mSecondAndHighlightPaint.setAntiAlias(true);
            mSecondAndHighlightPaint.setStrokeCap(Paint.Cap.ROUND);
            mSecondAndHighlightPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);

            mTickAndCirclePaint = new Paint();
            mTickAndCirclePaint.setColor(mWatchHandAndComplicationsColor);
            mTickAndCirclePaint.setStrokeWidth(SECOND_TICK_STROKE_WIDTH);
            mTickAndCirclePaint.setAntiAlias(true);
            mTickAndCirclePaint.setStyle(Paint.Style.STROKE);
            mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
        }

        /* Sets active/ambient mode colors for all complications.
         *
         * Note: With the rest of the watch face, we update the paint colors based on
         * ambient/active mode callbacks, but because the ComplicationDrawable handles
         * the active/ambient colors, we only set the colors twice. Once at initialization and
         * again if the user changes the highlight color via AnalogComplicationConfigActivity.
         */
        private void setComplicationsActiveAndAmbientColors(int primaryComplicationColor) {
            int complicationId;
            ComplicationDrawable complicationDrawable;

            for (int i = 0; i < COMPLICATION_IDS.length; i++) {
                complicationId = COMPLICATION_IDS[i];
                complicationDrawable = mComplicationDrawableSparseArray.get(complicationId);

                if (complicationId == BACKGROUND_COMPLICATION_ID) {
                    // It helps for the background color to be black in case the image used for the
                    // watch face's background takes some time to load.
                    complicationDrawable.setBackgroundColorActive(Color.BLACK);
                } else {
                    // Active mode colors.
                    complicationDrawable.setBorderColorActive(primaryComplicationColor);
                    complicationDrawable.setRangedValuePrimaryColorActive(primaryComplicationColor);

                    // Ambient mode colors.
                    complicationDrawable.setBorderColorAmbient(Color.WHITE);
                    complicationDrawable.setRangedValuePrimaryColorAmbient(Color.WHITE);
                }
            }
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            Log.d(TAG, "onPropertiesChanged: low-bit ambient = " + mLowBitAmbient);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);

            // Updates complications to properly render in ambient mode based on the
            // screen's capabilities.
            ComplicationDrawable complicationDrawable;

            for (int i = 0; i < COMPLICATION_IDS.length; i++) {
                complicationDrawable = mComplicationDrawableSparseArray.get(COMPLICATION_IDS[i]);

                complicationDrawable.setLowBitAmbient(mLowBitAmbient);
                complicationDrawable.setBurnInProtection(mBurnInProtection);
            }
        }

        /*
         * Called when there is updated data for a complication id.
         */
        @Override
        public void onComplicationDataUpdate(
                int complicationId, ComplicationData complicationData) {
            Log.d(TAG, "onComplicationDataUpdate() id: " + complicationId);

            // Adds/updates active complication data in the array.
            mActiveComplicationDataSparseArray.put(complicationId, complicationData);

            // Updates correct ComplicationDrawable with updated data.
            ComplicationDrawable complicationDrawable =
                    mComplicationDrawableSparseArray.get(complicationId);
            complicationDrawable.setComplicationData(complicationData);

            invalidate();
        }

        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Log.d(TAG, "OnTapCommand()");
            switch (tapType) {
                case TAP_TYPE_TAP:
                    int tappedComplicationId = getTappedComplicationId(x, y);
                    if (tappedComplicationId != -1) {
                        onComplicationTap(tappedComplicationId);
                    }
                    break;
            }
        }

        /*
         * Determines if tap inside a complication area or returns -1.
         */
        private int getTappedComplicationId(int x, int y) {
            int complicationId;
            ComplicationData complicationData;
            ComplicationDrawable complicationDrawable;

            long currentTimeMillis = System.currentTimeMillis();

            for (int i = 0; i < COMPLICATION_IDS.length; i++) {
                complicationId = COMPLICATION_IDS[i];
                complicationData = mActiveComplicationDataSparseArray.get(complicationId);

                if ((complicationData != null)
                        && (complicationData.isActive(currentTimeMillis))
                        // The line below this comment block disables taps for background
                        // complications (usually not wanted by the user).
                        // If you do want to enable this, remove the line below and reverse order
                        // of the loop so it checks the last elements (non-background complications)
                        // first. Otherwise, the background complication (since it is the size of
                        // the whole watch face) will capture all taps.
                        && (complicationData.getType() != ComplicationData.TYPE_LARGE_IMAGE)
                        && (complicationData.getType() != ComplicationData.TYPE_NOT_CONFIGURED)
                        && (complicationData.getType() != ComplicationData.TYPE_EMPTY)) {

                    complicationDrawable = mComplicationDrawableSparseArray.get(complicationId);
                    Rect complicationBoundingRect = complicationDrawable.getBounds();

                    if (complicationBoundingRect.width() > 0) {
                        if (complicationBoundingRect.contains(x, y)) {
                            return complicationId;
                        }
                    } else {
                        Log.e(TAG, "Not a recognized complication id.");
                    }
                }
            }
            return -1;
        }

        /*
         * Fires PendingIntent associated with complication (if it has one).
         */
        private void onComplicationTap(int complicationId) {
            Log.d(TAG, "onComplicationTap()");

            ComplicationData complicationData =
                    mActiveComplicationDataSparseArray.get(complicationId);

            if (complicationData != null) {

                if (complicationData.getTapAction() != null) {
                    try {
                        complicationData.getTapAction().send();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "On complication tap action error " + e);
                    }

                } else if (complicationData.getType() == ComplicationData.TYPE_NO_PERMISSION) {

                    // Watch face does not have permission to receive complication data, so launch
                    // permission request.
                    ComponentName componentName =
                            new ComponentName(
                                    getApplicationContext(),
                                    AnalogComplicationWatchFaceService.class);

                    Intent permissionRequestIntent =
                            ComplicationHelperActivity.createPermissionRequestHelperIntent(
                                    getApplicationContext(), componentName);

                    startActivity(permissionRequestIntent);
                }

            } else {
                Log.d(TAG, "No PendingIntent for complication " + complicationId + ".");
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);

            mAmbient = inAmbientMode;

            updateWatchPaintStyles();

            // Update drawable complications' ambient state.
            // Note: ComplicationDrawable handles switching between active/ambient colors, we just
            // have to inform it to enter ambient mode.
            ComplicationDrawable complicationDrawable;

            for (int i = 0; i < COMPLICATION_IDS.length; i++) {
                complicationDrawable = mComplicationDrawableSparseArray.get(COMPLICATION_IDS[i]);
                complicationDrawable.setInAmbientMode(mAmbient);
            }

            // Check and trigger whether or not timer should be running (only in active mode).
            updateTimer();
        }

        private void updateWatchPaintStyles() {
            if (mAmbient) {

                mBackgroundPaint.setColor(Color.BLACK);

                mHourPaint.setColor(Color.WHITE);
                mMinutePaint.setColor(Color.WHITE);
                mSecondAndHighlightPaint.setColor(Color.WHITE);
                mTickAndCirclePaint.setColor(Color.WHITE);

                mHourPaint.setAntiAlias(false);
                mMinutePaint.setAntiAlias(false);
                mSecondAndHighlightPaint.setAntiAlias(false);
                mTickAndCirclePaint.setAntiAlias(false);

                mHourPaint.clearShadowLayer();
                mMinutePaint.clearShadowLayer();
                mSecondAndHighlightPaint.clearShadowLayer();
                mTickAndCirclePaint.clearShadowLayer();

            } else {

                mBackgroundPaint.setColor(mBackgroundColor);

                mHourPaint.setColor(mWatchHandAndComplicationsColor);
                mMinutePaint.setColor(mWatchHandAndComplicationsColor);
                mTickAndCirclePaint.setColor(mWatchHandAndComplicationsColor);

                mSecondAndHighlightPaint.setColor(mWatchHandHighlightColor);

                mHourPaint.setAntiAlias(true);
                mMinutePaint.setAntiAlias(true);
                mSecondAndHighlightPaint.setAntiAlias(true);
                mTickAndCirclePaint.setAntiAlias(true);

                mHourPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mMinutePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mSecondAndHighlightPaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
                mTickAndCirclePaint.setShadowLayer(SHADOW_RADIUS, 0, 0, mWatchHandShadowColor);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);
            boolean inMuteMode = (interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE);

            /* Dim display in mute mode. */
            if (mMuteMode != inMuteMode) {
                mMuteMode = inMuteMode;
                mHourPaint.setAlpha(inMuteMode ? 100 : 255);
                mMinutePaint.setAlpha(inMuteMode ? 100 : 255);
                mSecondAndHighlightPaint.setAlpha(inMuteMode ? 80 : 255);
                invalidate();
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            mCenterX = width / 2f;
            mCenterY = height / 2f;

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            mSecondHandLength = (float) (mCenterX * 0.875);
            mMinuteHandLength = (float) (mCenterX * 0.75);
            mHourHandLength = (float) (mCenterX * 0.5);

            /*
             * Calculates location bounds for right and left circular complications. Please note,
             * we are not demonstrating a long text complication in this watch face.
             *
             * We suggest using at least 1/4 of the screen width for circular (or squared)
             * complications and 2/3 of the screen width for wide rectangular complications for
             * better readability.
             */

            // For most Wear devices, width and height are the same, so we just chose one (width).
            int sizeOfComplication = width / 4;
            int midpointOfScreen = width / 2;

            int horizontalOffset = (midpointOfScreen - sizeOfComplication) / 2;
            int verticalOffset = midpointOfScreen - (sizeOfComplication / 2);

            Rect leftBounds =
                    // Left, Top, Right, Bottom
                    new Rect(
                            horizontalOffset,
                            verticalOffset,
                            (horizontalOffset + sizeOfComplication),
                            (verticalOffset + sizeOfComplication));

            ComplicationDrawable leftComplicationDrawable =
                    mComplicationDrawableSparseArray.get(LEFT_COMPLICATION_ID);
            leftComplicationDrawable.setBounds(leftBounds);

            Rect rightBounds =
                    // Left, Top, Right, Bottom
                    new Rect(
                            (midpointOfScreen + horizontalOffset),
                            verticalOffset,
                            (midpointOfScreen + horizontalOffset + sizeOfComplication),
                            (verticalOffset + sizeOfComplication));

            ComplicationDrawable rightComplicationDrawable =
                    mComplicationDrawableSparseArray.get(RIGHT_COMPLICATION_ID);
            rightComplicationDrawable.setBounds(rightBounds);

            Rect screenForBackgroundBound =
                    // Left, Top, Right, Bottom
                    new Rect(0, 0, width, height);

            ComplicationDrawable backgroundComplicationDrawable =
                    mComplicationDrawableSparseArray.get(BACKGROUND_COMPLICATION_ID);
            backgroundComplicationDrawable.setBounds(screenForBackgroundBound);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            drawBackground(canvas, bounds);
            //drawWatchFace(canvas, bounds);
            //drawImagesWatchFace(canvas, bounds);
            //drawComplications(canvas, now);
            drawText(canvas, bounds);
            //drawUnreadNotificationIcon(canvas);
        }

        private void drawText(Canvas canvas, Rect bounds){
            Path dayPath = new Path();
            Path battPath = new Path();
            int width = bounds.width();
            int height = bounds.height();
            int x = width/2;
            int y = height/2;
            int radius = x/2;
            int offset = 0;
            dayPath.addCircle(x, y, radius, Path.Direction.CW);
            battPath.addCircle(x, y, radius+24/2, Path.Direction.CCW);
            //String myText = String.format("%1$tA %1$tb %1$td %1$tY at %1$tI:%1$tM %1$Tp", mCalendar);
            String dayText = String.format("%1$tA %1$tb %1$td %1$tY", mCalendar);
            String battText = "BATTERY: " + GlobalUtils.getBatteryInfoWatch(getApplicationContext());
            // Log.d(TAG, "Text Size: " + dayText.length());
            mTextPaint.setTextSize(24f);
            canvas.save();
            canvas.rotate(180, mCenterX, mCenterY);
            canvas.drawTextOnPath(dayText, dayPath, 0, 0, mTextPaint);
            canvas.rotate(180, mCenterX, mCenterY);
            canvas.drawTextOnPath(battText, battPath, -battText.length()/2, 0, mTextPaint);
            canvas.restore();
        }

        private void drawUnreadNotificationIcon(Canvas canvas) {

            if (mUnreadNotificationsPreference && (mNumberOfUnreadNotifications > 0)) {

                int width = canvas.getWidth();
                int height = canvas.getHeight();

                canvas.drawCircle(width / 2, height - 40, 10, mTickAndCirclePaint);

                /*
                 * Ensure center highlight circle is only drawn in interactive mode. This ensures
                 * we don't burn the screen with a solid circle in ambient mode.
                 */
                if (!mAmbient) {
                    canvas.drawCircle(width / 2, height - 40, 4, mSecondAndHighlightPaint);
                }
            }
        }

        private void drawBackground(Canvas canvas, Rect bounds) {

/*            if (mAmbient && (mLowBitAmbient || mBurnInProtection)) {
                canvas.drawColor(Color.BLACK);

            } else {
                canvas.drawColor(mBackgroundColor);
            }*/
            int width = bounds.width();
            int height = bounds.height();
            // Draw the background, scaled to fit.
            if (mBackgroundScaledBitmap == null
                    || mBackgroundScaledBitmap.getWidth() != width
                    || mBackgroundScaledBitmap.getHeight() != height) {
                mBackgroundScaledBitmap = Bitmap.createScaledBitmap(mBackgroundBitmap,
                        width, height, true /* filter */);
            }
            canvas.drawBitmap(mBackgroundScaledBitmap, 0, 0, mPaint);
        }

        private void drawComplications(Canvas canvas, long currentTimeMillis) {
            int complicationId;
            ComplicationDrawable complicationDrawable;

            for (int i = 0; i < COMPLICATION_IDS.length; i++) {
                complicationId = COMPLICATION_IDS[i];
                complicationDrawable = mComplicationDrawableSparseArray.get(complicationId);
                complicationDrawable.setBorderStyleActive(0);
                complicationDrawable.setBorderStyleAmbient(0);
                complicationDrawable.setTextSizeActive(15);

                complicationDrawable.draw(canvas, currentTimeMillis);
            }
        }

        private void drawWatchFace(Canvas canvas, Rect bounds) {
            /*
             * Draw ticks. Usually you will want to bake this directly into the photo, but in
             * cases where you want to allow users to select their own photos, this dynamically
             * creates them on top of the photo.
             */

            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;


            float innerTickRadius = mCenterX - 10;
            float outerTickRadius = mCenterX;
            for (int tickIndex = 0; tickIndex < 12; tickIndex++) {
                float tickRot = (float) (tickIndex * Math.PI * 2 / 12);
                float innerX = (float) Math.sin(tickRot) * innerTickRadius;
                float innerY = (float) -Math.cos(tickRot) * innerTickRadius;
                float outerX = (float) Math.sin(tickRot) * outerTickRadius;
                float outerY = (float) -Math.cos(tickRot) * outerTickRadius;
                canvas.drawLine(
                        mCenterX + innerX,
                        mCenterY + innerY,
                        mCenterX + outerX,
                        mCenterY + outerY,
                        mTickAndCirclePaint);
            }

            /*
             * Save the canvas state before we can begin to rotate it.
             */
            canvas.save();

            canvas.rotate(hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - mHourHandLength,
                    mHourPaint);

            canvas.rotate(minutesRotation - hoursRotation, mCenterX, mCenterY);
            canvas.drawLine(
                    mCenterX,
                    mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                    mCenterX,
                    mCenterY - mMinuteHandLength,
                    mMinutePaint);

            /*
             * Ensure the "seconds" hand is drawn only when we are in interactive mode.
             * Otherwise, we only update the watch face once a minute.
             */
            if (!mAmbient) {
                canvas.rotate(secondsRotation - minutesRotation, mCenterX, mCenterY);
                canvas.drawLine(
                        mCenterX,
                        mCenterY - CENTER_GAP_AND_CIRCLE_RADIUS,
                        mCenterX,
                        mCenterY - mSecondHandLength,
                        mSecondAndHighlightPaint);
            }
            canvas.drawCircle(
                    mCenterX, mCenterY, CENTER_GAP_AND_CIRCLE_RADIUS, mTickAndCirclePaint);

            /* Restore the canvas' original orientation. */
            canvas.restore();
        }

        private void drawImagesWatchFace(Canvas canvas, Rect bounds) {

            int width = bounds.width();
            int height = bounds.height();
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            final float seconds =
                    (mCalendar.get(Calendar.SECOND) + mCalendar.get(Calendar.MILLISECOND) / 1000f);
            final float secondsRotation = seconds * 6f;

            final float minutesRotation = mCalendar.get(Calendar.MINUTE) * 6f;

            final float hourHandOffset = mCalendar.get(Calendar.MINUTE) / 2f;
            final float hoursRotation = (mCalendar.get(Calendar.HOUR) * 30) + hourHandOffset;

            // Rotate the hour hand
            if (mHourScaledBitmap == null
                    || mHourScaledBitmap.getWidth() != width
                    || mHourScaledBitmap.getHeight() != height) {
                mHourScaledBitmap = Bitmap.createScaledBitmap(mHourBitmap,
                        width, height, true /* filter */);
            }
            // Rotate
            canvas.save();
            canvas.rotate(hoursRotation,canvas.getWidth()/2,canvas.getHeight()/2);
            //canvas.drawBitmap(mHourScaledBitmap, 0, 0, mPaint);
            canvas.restore();
            // Draw the minute hand, scaled to fit.

            // Rotate the minute hand
            if (mMinuteScaledBitmap == null
                    || mMinuteScaledBitmap.getWidth() != width
                    || mMinuteScaledBitmap.getHeight() != height) {
                mMinuteScaledBitmap = Bitmap.createScaledBitmap(mMinuteBitmap,
                        width, height, true /* filter */);
            }
            // Rotate the second hand
            canvas.save();
            canvas.rotate(minutesRotation,canvas.getWidth()/2,canvas.getHeight()/2);
            //canvas.drawBitmap(mMinuteScaledBitmap, 0, 0, mPaint);
            canvas.restore();
            // Draw foreground
            if (mForegroundScaledBitmap == null
                    || mForegroundScaledBitmap.getWidth() != width
                    || mForegroundScaledBitmap.getHeight() != height) {
                mForegroundScaledBitmap = Bitmap.createScaledBitmap(mForegroundBitmap,
                        width, height, true /* filter */);
            }
            canvas.drawBitmap(mForegroundScaledBitmap, 0, 0, mPaint);
            // Draw seconds if not in Ambient mode
            if (!mAmbient) {
                if (mSecondScaledBitmap == null
                        || mSecondScaledBitmap.getWidth() != width
                        || mSecondScaledBitmap.getHeight() != height) {
                    mSecondScaledBitmap = Bitmap.createScaledBitmap(mSecondBitmap,
                            width, height, true /* filter */);
                }
                // Rotate the second hand
                canvas.save();
                canvas.rotate(secondsRotation,canvas.getWidth()/2,canvas.getHeight()/2);
                //canvas.drawBitmap(mSecondScaledBitmap, 0, 0, mPaint);
                canvas.restore();

            }
        }
        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {

                // Preferences might have changed since last time watch face was visible.
                loadSavedPreferences();

                // With the rest of the watch face, we update the paint colors based on
                // ambient/active mode callbacks, but because the ComplicationDrawable handles
                // the active/ambient colors, we only need to update the complications' colors when
                // the user actually makes a change to the highlight color, not when the watch goes
                // in and out of ambient mode.
                setComplicationsActiveAndAmbientColors(mWatchHandHighlightColor);
                updateWatchPaintStyles();

                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer();
        }

        @Override
        public void onUnreadCountChanged(int count) {
            Log.d(TAG, "onUnreadCountChanged(): " + count);

            if (mUnreadNotificationsPreference) {

                if (mNumberOfUnreadNotifications != count) {
                    mNumberOfUnreadNotifications = count;
                    invalidate();
                }
            }
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            AnalogComplicationWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            AnalogComplicationWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts/stops the {@link #mUpdateTimeHandler} timer based on the state of the watch face.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run in active mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !mAmbient;
        }
    }
}
