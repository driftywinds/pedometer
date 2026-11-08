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

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;

import androidx.core.app.NotificationCompat;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.activities.MainActivity;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;

import java.util.Calendar;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * Helper methods around the permanent step count notification.
 * <p>
 * The step counter runs as a foreground service and therefore depends on being able to
 * show its notification. If the user (or the system) disabled notifications for the app,
 * forcing the notification will not work until the user re-enables them, so the home
 * screen shows an explanation and a shortcut to the system settings instead.
 */
public final class NotificationHelper {

    private NotificationHelper() {
    }

    /**
     * @return true if the app is allowed to post notifications (Android 13+ runtime permission)
     */
    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /**
     * @return true if notifications are enabled for this app in the system settings
     */
    public static boolean areNotificationsEnabled(Context context) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * @return true if the passed notification channel exists but was disabled by the user
     */
    public static boolean isChannelBlocked(Context context, String channelId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return false;
        }
        NotificationChannel channel = notificationManager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
    }

    /**
     * @return true if the app may show notifications on the given channel
     */
    public static boolean canPostNotifications(Context context, String channelId) {
        return hasNotificationPermission(context)
                && areNotificationsEnabled(context)
                && !isChannelBlocked(context, channelId);
    }

    /**
     * Checks whether a notification with the given id is currently posted by this app.
     * Before Android 6.0 this cannot be queried, so true is returned to avoid false alarms.
     *
     * @param context        The application context
     * @param notificationId The id of the notification to look for
     * @return true if the notification is currently visible
     */
    public static boolean isNotificationVisible(Context context, int notificationId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                List<StatusBarNotification> notifications =
                        NotificationManagerCompat.from(context).getActiveNotifications();
                for (StatusBarNotification notification : notifications) {
                    if (notification.getId() == notificationId) {
                        return true;
                    }
                }
                return false;
            } catch (RuntimeException e) {
                // Do not report a problem if the system does not let us inspect notifications.
                return true;
            }
        }
        // Before Android 6.0 active notifications cannot be queried.
        return true;
    }

    /**
     * @return an intent which opens the notification settings for this app
     */
    public static Intent getNotificationSettingsIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.getPackageName(), null));
    }
    private static final String TAG = "NotificationHelper";

    /**
     * Creates the permanent tracking notification channel if it does not exist yet.
     * Posting to a not yet created channel would be silently dropped on Android 8+.
     */
    public static void ensureTrackingChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null
                || notificationManager.getNotificationChannel(AbstractStepDetectorService.CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                AbstractStepDetectorService.CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    /**
     * Builds the permanent step counter notification which keeps the foreground
     * service alive. Shared by the service itself and the "restore notification" action.
     */
    public static Notification buildTrackingNotification(Context context, int totalSteps,
                                                        double totalDistance, double totalCalories,
                                                        int dailyStepGoal) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean showSteps = sharedPref.getBoolean(
                context.getString(R.string.pref_notification_permanent_show_steps), true);
        boolean showDistance = sharedPref.getBoolean(
                context.getString(R.string.pref_notification_permanent_show_distance), false);
        boolean showCalories = sharedPref.getBoolean(
                context.getString(R.string.pref_notification_permanent_show_calories), false);
        String message = "";
        if (showSteps) {
            message = String.format(context.getString(R.string.notification_text_steps),
                    totalSteps, dailyStepGoal);
        }
        if (showDistance) {
            message += (!message.isEmpty()) ? "\n" : "";
            message += String.format(context.getString(R.string.notification_text_distance),
                    UnitHelper.kilometerToUsersLengthUnit(
                            UnitHelper.metersToKilometers(totalDistance), context),
                    UnitHelper.usersLengthDescriptionShort(context));
        }
        if (showCalories) {
            message += (!message.isEmpty()) ? "\n" : "";
            message += String.format(context.getString(R.string.notification_text_calories), totalCalories);
        }
        if (message.isEmpty()) {
            message = context.getString(R.string.notification_text_default);
        }
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pIntent = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder mBuilder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mBuilder = new NotificationCompat.Builder(context, AbstractStepDetectorService.CHANNEL_ID);
            mBuilder.setOnlyAlertOnce(true);
        } else {
            mBuilder = new NotificationCompat.Builder(context);
        }
        mBuilder.setContentTitle(context.getString(R.string.app_name))
                .setContentText(message)
                .setTicker(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(R.string.app_name)))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(R.drawable.ic_stat_directions_walk);
        mBuilder.setContentIntent(pIntent);
        if (dailyStepGoal > 0) {
            mBuilder.setProgress(dailyStepGoal, totalSteps, false);
        }
        mBuilder.setVisibility(NotificationCompat.VISIBILITY_SECRET);
        mBuilder.setPriority(NotificationCompat.PRIORITY_MIN);
        mBuilder.setSilent(true);
        // The service relies on this notification to stay in the foreground. Making it
        // ongoing prevents (accidental) dismissal that would leave the step counter
        // running "headless" until the next update.
        mBuilder.setOngoing(true);
        mBuilder.setAutoCancel(false);
        mBuilder.setShowWhen(false);
        mBuilder.setCategory(NotificationCompat.CATEGORY_SERVICE);
        return mBuilder.build();
    }

    /**
     * Posts the permanent tracking notification immediately, using the step totals which
     * are already stored in the database. This makes the "restore notification" action
     * instant instead of waiting for the service to be (re)started by the system.
     *
     * @return true if the notification was handed to the notification manager
     */
    public static boolean postTrackingNotification(Context context) {
        try {
            ensureTrackingChannel(context);
            List<StepCount> stepCounts = StepCountPersistenceHelper.getStepCountsForDay(
                    Calendar.getInstance(), context);
            int steps = 0;
            double distance = 0;
            double calories = 0;
            for (StepCount stepCount : stepCounts) {
                steps += stepCount.getStepCount();
                distance += stepCount.getDistance();
                calories += stepCount.getCalories(context);
            }
            int goal = 10000;
            try {
                String goalString = PreferenceManager.getDefaultSharedPreferences(context)
                        .getString(context.getString(R.string.pref_daily_step_goal), "10000");
                if (goalString != null) {
                    goal = Integer.parseInt(goalString);
                }
            } catch (NumberFormatException ignored) {
                // Fall back to the default goal.
            }
            NotificationManagerCompat.from(context).notify(
                    AbstractStepDetectorService.NOTIFICATION_ID,
                    buildTrackingNotification(context, steps, distance, calories, goal));
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not post the tracking notification.", e);
            return false;
        }
    }
}
