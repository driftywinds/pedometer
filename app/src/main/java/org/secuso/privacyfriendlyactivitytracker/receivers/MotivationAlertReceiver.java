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
package org.secuso.privacyfriendlyactivitytracker.receivers;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.activities.MainActivity;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.NotificationHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Receives the motivation alert event and notifies the user.
 * <p>
 * The receiver does not bind to the step detector service anymore: binding could fail (for
 * example because of background execution limits), in which case the alert was neither shown
 * nor rescheduled. The step count is read from the database instead and the alarm is always
 * re-armed.
 *
 * @author Tobias Neidig
 * @version 20160729
 */

public class MotivationAlertReceiver extends BroadcastReceiver {
    public static final int NOTIFICATION_ID = 0;
    public static final String CHANNEL_ID = "motivation_alert";
    private static final String LOG_CLASS = MotivationAlertReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(LOG_CLASS, "Motivate the user!");
        Context appContext = context.getApplicationContext();
        try {
            showMotivationIfRequired(appContext);
        } catch (RuntimeException e) {
            Log.e(LOG_CLASS, "Could not show the motivation notification.", e);
        } finally {
            // The alarm is one-shot; it has to be re-armed in every case, otherwise the
            // motivation alert would silently stop working.
            if (StepDetectionServiceHelper.isMotivationAlertEnabled(appContext)) {
                StepDetectionServiceHelper.setMotivationAlert(appContext);
            }
        }
    }

    private void showMotivationIfRequired(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        float criterion = parseCriterion(sharedPref, context);
        if (criterion < 0 || criterion > 100) {
            Log.e(LOG_CLASS, "Invalid motivation criterion. Cannot notify the user.");
            return;
        }

        int stepCount = StepCountPersistenceHelper.getStepCountForDay(Calendar.getInstance(), context);
        int dailyGoal = getDailyGoal(sharedPref, context);
        if (dailyGoal * criterion / 100 <= stepCount) {
            Log.i(LOG_CLASS, "No motivation required.");
            return;
        }

        Set<String> defaultStringSet = new HashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.pref_default_notification_motivation_alert_messages)));
        Set<String> storedTexts = sharedPref.getStringSet(
                context.getString(R.string.pref_notification_motivation_alert_texts), defaultStringSet);
        List<String> motivationTexts = new ArrayList<>(storedTexts);
        if (motivationTexts.isEmpty()) {
            Log.e(LOG_CLASS, "Motivation texts are empty. Cannot notify the user.");
            return;
        }
        Collections.shuffle(motivationTexts);
        String motivationText = motivationTexts.get(0);

        ensureChannel(context);
        if (!NotificationHelper.canPostNotifications(context, CHANNEL_ID)) {
            Log.i(LOG_CLASS, "Notifications are disabled for the app - skipping the alert.");
            return;
        }

        Intent mainIntent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, mainIntent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_walk_black_24dp)
                .setContentTitle(context.getString(R.string.motivation_alert_notification_title))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setContentText(motivationText)
                .setLights(ContextCompat.getColor(context, R.color.colorPrimary), 1000, 1000)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
    }

    private float parseCriterion(SharedPreferences sharedPref, Context context) {
        String value = sharedPref.getString(
                context.getString(R.string.pref_notification_motivation_alert_criterion), "100");
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            Log.w(LOG_CLASS, "Invalid motivation criterion, using the default.", e);
            return 100f;
        }
    }

    private int getDailyGoal(SharedPreferences sharedPref, Context context) {
        String value = sharedPref.getString(context.getString(R.string.pref_daily_step_goal), "10000");
        try {
            int goal = Integer.parseInt(value);
            return goal > 0 ? goal : 10000;
        } catch (NumberFormatException e) {
            return 10000;
        }
    }

    private void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null
                || notificationManager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.motivation_alert_notification_title),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        notificationManager.createNotificationChannel(channel);
    }
}
