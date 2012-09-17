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

package com.android.camera;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.camera.ui.OtherSettingsPopup;
import com.android.camera.ui.PieItem;
import com.android.camera.ui.PieRenderer;

public class VideoController extends PieController
        implements OtherSettingsPopup.Listener {

    private static String TAG = "CAM_videocontrol";
    private static float FLOAT_PI_DIVIDED_BY_TWO = (float) Math.PI / 2;

    private VideoModule mModule;
    private String[] mOtherKeys;
    private OtherSettingsPopup mPopup;

    public VideoController(CameraActivity activity, VideoModule module, PieRenderer pie) {
        super(activity, pie);
        mModule = module;
    }

    public void initialize(PreferenceGroup group) {
        super.initialize(group);
        float sweep = FLOAT_PI_DIVIDED_BY_TWO / 2;

        addItem(CameraSettings.KEY_VIDEOCAMERA_FLASH_MODE, FLOAT_PI_DIVIDED_BY_TWO - sweep, sweep);
        addItem(CameraSettings.KEY_WHITE_BALANCE, 3 * FLOAT_PI_DIVIDED_BY_TWO + sweep, sweep);
        if (group.findPreference(CameraSettings.KEY_CAMERA_ID) != null) {
            PieItem item = makeItem(R.drawable.ic_switch_video_facing_holo_light);
            item.setFixedSlice(FLOAT_PI_DIVIDED_BY_TWO + sweep,  sweep);
            item.setOnClickListener(new OnClickListener() {

                @Override
                public void onClick(PieItem item) {
                    // Find the index of next camera.
                    ListPreference pref = mPreferenceGroup.findPreference(CameraSettings.KEY_CAMERA_ID);
                    if (pref != null) {
                        int index = pref.findIndexOfValue(pref.getValue());
                        CharSequence[] values = pref.getEntryValues();
                        index = (index + 1) % values.length;
                        int newCameraId = Integer.parseInt((String) values[index]);
                        mListener.onCameraPickerClicked(newCameraId);
                    }
                }
            });
            mRenderer.addItem(item);
        }
        mOtherKeys = new String[] {
                CameraSettings.KEY_VIDEO_EFFECT,
                CameraSettings.KEY_VIDEO_TIME_LAPSE_FRAME_INTERVAL,
                CameraSettings.KEY_VIDEO_QUALITY,
                CameraSettings.KEY_RECORD_LOCATION,
                CameraSettings.KEY_POWER_SHUTTER,
                CameraSettings.KEY_COLOR_EFFECT};

        PieItem item = makeItem(R.drawable.ic_settings_holo_light);
        item.setFixedSlice(FLOAT_PI_DIVIDED_BY_TWO * 3, sweep);
        item.getView().setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPopup == null) {
                    initializePopup();
                }
                mModule.showPopup(mPopup);
            }
        });
        mRenderer.addItem(item);
    }

    protected void setCameraId(int cameraId) {
        ListPreference pref = mPreferenceGroup.findPreference(CameraSettings.KEY_CAMERA_ID);
        pref.setValue("" + cameraId);
    }

    @Override
    public void overrideSettings(final String ... keyvalues) {
        super.overrideSettings(keyvalues);
        if (mPopup == null) {
            initializePopup();
        }
        ((OtherSettingsPopup)mPopup).overrideSettings(keyvalues);
    }

    protected void initializePopup() {
        LayoutInflater inflater = (LayoutInflater) mActivity.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);

        OtherSettingsPopup popup = (OtherSettingsPopup) inflater.inflate(
                R.layout.other_setting_popup, null, false);
        popup.setSettingChangedListener(this);
        popup.initialize(mPreferenceGroup, mOtherKeys);
        if (mActivity.isSecureCamera()) {
            // Prevent location preference from getting changed in secure camera mode
            popup.setPreferenceEnabled(CameraSettings.KEY_RECORD_LOCATION, false);
        }
        mPopup = popup;
    }

    public void popupDismissed(boolean topPopupOnly) {
        initializePopup();
        // if the 2nd level popup gets dismissed
        if (mPopupStatus == POPUP_SECOND_LEVEL) {
            mPopupStatus = POPUP_FIRST_LEVEL;
            if (topPopupOnly) mModule.showPopup(mPopup);
        }
        mPopup = popup;
    }

    @Override
    public void onRestorePreferencesClicked() {
        mModule.onRestorePreferencesClicked();
    }

}
