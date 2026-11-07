/*
    Privacy Friendly Pedometer is licensed under the GPLv3.
    Copyright (C) 2017  Tobias Neidig

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program. If not, see <http://www.gnu.org/licenses/>.
*/
package org.secuso.privacyfriendlyactivitytracker.utils;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;

/**
 * Resolves the state of the permanent tracking notification and performs the recovery
 * actions which are offered on the home screen (overflow menu) and in the small banner
 * below the step summary card.
 */
public final class TrackingNotificationHelper {

    public static final int STATE_OK = 0;
    public static final int STATE_PAUSED = 1;
    public static final int STATE_NOTIFICATIONS_OFF = 2;
    public static final int STATE_MISSING = 3;

    private TrackingNotificationHelper() {
    }

    public static int getState(Context context) {
        if (!StepDetectionServiceHelper.isStepDetectionEnabled(context)) {
            return STATE_PAUSED;
        }
        if (!NotificationHelper.canPostNotifications(context, AbstractStepDetectorService.CHANNEL_ID)) {
            return STATE_NOTIFICATIONS_OFF;
        }
        if (!NotificationHelper.isNotificationVisible(context, AbstractStepDetectorService.NOTIFICATION_ID)) {
            return STATE_MISSING;
        }
        return STATE_OK;
    }

    /**
     * Handles the button of the banner which is shown below the step summary card.
     */
    public static void onBannerActionClicked(Activity activity) {
        switch (getState(activity)) {
            case STATE_NOTIFICATIONS_OFF:
                showNotificationsOffDialog(activity);
                break;
            case STATE_MISSING:
            default:
                restore(activity);
                break;
        }
    }

    /**
     * Handles the "Restore tracking notification" overflow menu item.
     */
    public static void restore(Activity activity) {
        final Context context = activity.getApplicationContext();
        if (!StepDetectionServiceHelper.isStepDetectionEnabled(context)) {
            resumeTracking(context);
            return;
        }
        if (!NotificationHelper.canPostNotifications(context, AbstractStepDetectorService.CHANNEL_ID)) {
            showNotificationsOffDialog(activity);
            return;
        }
        boolean restored = StepDetectionServiceHelper.forceStepDetectionNotification(context);
        Toast.makeText(context,
                restored ? R.string.tracking_notification_restored : R.string.tracking_notification_failed,
                restored ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
    }

    private static void resumeTracking(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        sharedPref.edit().putBoolean(context.getString(R.string.pref_step_counter_enabled), true).apply();
        StepDetectionServiceHelper.startAllIfEnabled(true, context);
    }

    private static void showNotificationsOffDialog(Activity activity) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.tracking_notifications_off_title)
                .setMessage(R.string.tracking_notifications_off_message)
                .setPositiveButton(R.string.tracking_open_settings, (dialog, which) -> {
                    try {
                        activity.startActivity(NotificationHelper.getNotificationSettingsIntent(activity));
                    } catch (RuntimeException ignored) {
                        // The settings screen is not available on this device.
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
