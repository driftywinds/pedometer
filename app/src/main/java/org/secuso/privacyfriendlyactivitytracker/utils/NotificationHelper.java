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
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

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
}
