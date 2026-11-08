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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import org.secuso.privacyfriendlyactivitytracker.Factory;
import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.persistence.TrainingPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.receivers.MotivationAlertReceiver;
import org.secuso.privacyfriendlyactivitytracker.receivers.StepCountPersistenceReceiver;
import org.secuso.privacyfriendlyactivitytracker.receivers.WidgetReceiver;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;
import org.secuso.privacyfriendlyactivitytracker.services.AccelerometerStepDetectorService;
import org.secuso.privacyfriendlyactivitytracker.services.HardwareStepService;

import java.util.Calendar;

/**
 * Helper class to start and stop the necessary services
 *
 * @author Tobias Neidig
 * @version 20160729
 */
public class StepDetectionServiceHelper {

    private static final String LOG_CLASS = StepDetectionServiceHelper.class.getName();

    private static final int PERSISTENCE_REQUEST_CODE = 2;
    private static final int END_OF_DAY_REQUEST_CODE = 5;
    private static final int MOTIVATION_ALERT_REQUEST_CODE = 1;
    /**
     * Value which older versions wrote as default for the motivation alert time (18:00 UTC).
     * It is interpreted as 18:00 local time.
     */
    private static final long LEGACY_DEFAULT_MOTIVATION_ALERT_TIME = 64800000L;

    /**
     * Starts the step detection, persistence service and notification service if they are enabled in settings.
     *
     * @param context The application context.
     */
    public static void startAllIfEnabled(Context context) {
        startAllIfEnabled(false, context);
    }

    public static void startAllIfEnabled(boolean forceRealTimeStepDetection, Context context){

        Log.i(LOG_CLASS, "Start of all service requested");
        // Start the step detection if enabled or training is active
        if (isStepDetectionEnabled(context)) {
            Log.i(LOG_CLASS, "Start step detection");
            StepDetectionServiceHelper.startStepDetection(context);
            // schedule stepCountPersistenceService
            StepDetectionServiceHelper.schedulePersistenceService(context);
        }

        if(isMotivationAlertEnabled(context)){
            Log.i(LOG_CLASS, "Schedule motivation alert");
            // set motivation alert
            setMotivationAlert(context);
        }
    }

    public static void stopAllIfNotRequired(Context context){
        stopAllIfNotRequired(true, context);
        WidgetReceiver.forceWidgetUpdate(context);
    }

    public static void stopAllIfNotRequired(boolean forceSave, Context context){

        // Start the step detection if enabled or training is active
        if (!isStepDetectionEnabled(context)) {
            Log.i(LOG_CLASS, "Stopping all services");
            if(forceSave){
                // un-schedule stepCountPersistenceService
                // persistence service will stop step detection service.
                StepDetectionServiceHelper.cancelPersistenceService(forceSave, context);
            }else {
                StepDetectionServiceHelper.stopStepDetection(context);
            }
        }

        if(!isMotivationAlertEnabled(context)){
            // cancel motivation alert
            cancelMotivationAlert(context);
        }
    }


    public static void restartStepDetection(Context context){
        startPersistenceService(context);
        stopStepDetection(context);
        startAllIfEnabled(context);
    }



    /**
     * Starts the step detection service
     *
     * @param context The application context
     */
    public static void startStepDetection(Context context) {
        startStepDetection(context, null);
    }

    /**
     * Starts the step detection service and optionally delivers an action to it.
     *
     * @param context The application context
     * @param action  An optional action for the service (may be null)
     */
    public static boolean startStepDetection(Context context, String action) {
        Log.i(LOG_CLASS, "Started step detection service.");
        Intent stepDetectorServiceIntent = new Intent(context, Factory.getStepDetectorServiceClass(context));
        if (action != null) {
            stepDetectorServiceIntent.setAction(action);
        }

        boolean started = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.getApplicationContext().startForegroundService(stepDetectorServiceIntent);
            } else {
                context.getApplicationContext().startService(stepDetectorServiceIntent);
            }
            started = true;
        } catch (RuntimeException e) {
            // Android 12+ forbids starting a foreground service from the background and
            // Android 14+ validates the foreground service type. Never crash the caller
            // (e.g. a broadcast receiver); the user can restore tracking from the home screen.
            Log.e(LOG_CLASS, "Could not start the step detection service.", e);
        }
        WidgetReceiver.forceWidgetUpdate(context);
        return started;
    }
    /**
     * Forces the step detection service to (re-)post its permanent notification.
     * <p>
     * The step counter is a foreground service and depends on its notification to be kept
     * alive by the system. Some devices (or the user) remove the notification or kill the
     * service, after which steps are not counted anymore. This method restarts the service
     * if it is not running and asks a running service to refresh the notification, so the
     * user can recover tracking without toggling any setting.
     *
     * @param context The application context
     * @return true if the service start was accepted by the system, false if step detection
     *         is disabled or the start was refused
     */
    public static boolean forceStepDetectionNotification(Context context) {
        if (!isStepDetectionEnabled(context)) {
            Log.i(LOG_CLASS, "Force notification requested, but step detection is disabled.");
            return false;
        }
        boolean started = startStepDetection(context, AbstractStepDetectorService.ACTION_FORCE_NOTIFICATION);
        // Make sure the regular persistence alarm is still in place as well.
        schedulePersistenceService(context);
        Log.i(LOG_CLASS, "Force notification requested.");
        return started;
    }

    /**
     * Stops the step detection service
     *
     * @param context The application context
     */
    public static void stopStepDetection(Context context){
        Log.i(LOG_CLASS, "Stopping step detection service.");
        Intent hardwareStepDetectorServiceIntent = new Intent(context, HardwareStepService.class);
        Intent accelerometerStepDetectorServiceIntent = new Intent(context, AccelerometerStepDetectorService.class);
        if(!context.getApplicationContext().stopService(hardwareStepDetectorServiceIntent)){
            Log.w(LOG_CLASS, "Stopping of HARDWARE service failed or it is not running.");
        }
        if(!context.getApplicationContext().stopService(accelerometerStepDetectorServiceIntent)){
            Log.w(LOG_CLASS, "Stopping of ACCELEROMETER service failed or it is not running.");
        }
    }


    /**
     *  Schedules the step count persistence service.
     * <p>
     * One-shot alarms are used instead of repeating alarms: they are re-armed by
     * {@link StepCountPersistenceReceiver} after every run. This keeps working when the device
     * is in Doze mode and allows the end-of-day save to be scheduled for every day.
     *
     * @param context The application context
     */
    public static void schedulePersistenceService(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.w(LOG_CLASS, "Cannot schedule the persistence service - alarm manager is not available.");
            return;
        }

        // Fire at next half hour
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        int unroundedMinutes = calendar.get(Calendar.MINUTE);
        int mod = unroundedMinutes % 30;
        int minutesToAdd = (mod == 0) ? 30 : 30 - mod;
        calendar.add(Calendar.MINUTE, minutesToAdd);

        setAlarm(am, calendar.getTimeInMillis(),
                getPersistencePendingIntent(context, PERSISTENCE_REQUEST_CODE));
        Log.i(LOG_CLASS, "Scheduled persistence service at start time " + calendar.getTime());

        // End of day save
        Calendar daysEnd = Calendar.getInstance();
        daysEnd.set(Calendar.HOUR_OF_DAY, 23);
        daysEnd.set(Calendar.MINUTE, 59);
        daysEnd.set(Calendar.SECOND, 0);
        daysEnd.set(Calendar.MILLISECOND, 0);
        if (daysEnd.getTimeInMillis() <= System.currentTimeMillis()) {
            daysEnd.add(Calendar.DAY_OF_MONTH, 1);
        }
        setAlarm(am, daysEnd.getTimeInMillis(),
                getPersistencePendingIntent(context, END_OF_DAY_REQUEST_CODE));
        Log.i(LOG_CLASS, "Scheduled end of day persistence service at time " + daysEnd.getTime());

    }

    private static PendingIntent getPersistencePendingIntent(Context context, int requestCode) {
        Intent stepCountPersistenceServiceIntent = new Intent(context, StepCountPersistenceReceiver.class);
        return PendingIntent.getBroadcast(context, requestCode, stepCountPersistenceServiceIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Sets an alarm which is also delivered in Doze mode. No exact-alarm permission is required,
     * so the alarm may be delayed by the system but is never dropped because of a missing
     * special permission.
     */
    private static void setAlarm(AlarmManager am, long triggerAtMillis, PendingIntent pendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    /**
     * Cancel the scheduled persistence service
     * @param forceSave if true the persistence service will be execute now and canceled after
     * @param context The application context
     */
    public static void cancelPersistenceService(boolean forceSave, Context context){
        // force save
        if(forceSave) {
            startPersistenceService(context);
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        am.cancel(getPersistencePendingIntent(context, PERSISTENCE_REQUEST_CODE));
        // The end-of-day alarm uses another request code and has to be cancelled explicitly.
        am.cancel(getPersistencePendingIntent(context, END_OF_DAY_REQUEST_CODE));
    }

    /**
     * Asks the step detector service to persist the current step count.
     */
    public static void startPersistenceService(Context context) {
        Log.i(LOG_CLASS, "Started persistence service.");
        saveStepsNow(context, -1);
    }

    /**
     * Asks the step detector service to store the steps which were counted since the last save.
     * <p>
     * If the service is not running, it is started (as a foreground service where required).
     * The call is best effort: a background start may be rejected by the system, in which case
     * the persisted alarm will try again later.
     *
     * @param context        The application context
     * @param walkingModeId  The walking mode the steps belong to, or a value &lt;= 0 for the
     *                       currently active mode
     */
    public static void saveStepsNow(Context context, long walkingModeId) {
        Intent intent = new Intent(context, Factory.getStepDetectorServiceClass(context));
        intent.setAction(AbstractStepDetectorService.ACTION_SAVE_STEPS);
        if (walkingModeId > 0) {
            intent.putExtra(AbstractStepDetectorService.EXTENDED_DATA_WALKING_MODE_ID, walkingModeId);
        }
        Context appContext = context.getApplicationContext();
        try {
            appContext.startService(intent);
        } catch (RuntimeException e) {
            // Starting a background service is restricted since Android 8. Try the foreground
            // variant, which may still be rejected when the app is in the background.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent);
                }
            } catch (RuntimeException foregroundException) {
                Log.e(LOG_CLASS, "Could not ask the step detector to save the steps.", foregroundException);
            }
        }
    }

    /**
     * Is the step detection enabled? This could be the case if the permanent step counter or a training
     * session is active
     * @param context The application context
     * @return true if step detection is enabled
     */
    public static boolean isStepDetectionEnabled(Context context) {
        // Get user preferences
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isStepDetectionEnabled = sharedPref.getBoolean(context.getString(R.string.pref_step_counter_enabled), true);
        boolean isWalkingModeLearningActive = sharedPref.getBoolean(context.getString(R.string.pref_walking_mode_learning_active), false);
        boolean isDistanceMeasurementActive = sharedPref.getLong(context.getString(R.string.pref_distance_measurement_start_timestamp), -1) > 0;
        return isStepDetectionEnabled || (TrainingPersistenceHelper.getActiveItem(context) != null) || isWalkingModeLearningActive || isDistanceMeasurementActive;
    }

    /**
     * Is the motivation alert notification enabled by user?
     * @param context The application context
     * @return true if enabled
     */
    public static boolean isMotivationAlertEnabled(Context context){
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        return sharedPref.getBoolean(context.getString(R.string.pref_notification_motivation_alert_enabled), true);
    }

    /**
     * Schedules (or updates) the motivation alert notification alarm
     * @param context The application context
     */
    public static void setMotivationAlert(Context context){
        Log.i(LOG_CLASS, "Setting motivation alert alarm");
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long triggerAt = getNextMotivationAlertTime(context);
        setAlarm(am, triggerAt, getMotivationAlertPendingIntent(context));
        Log.i(LOG_CLASS, "Scheduled motivation alert for " + Calendar.getInstance().getTime());
    }

    /**
     * Calculates the next point in time at which the motivation alert should be shown.
     * <p>
     * The preference stores an absolute timestamp which was created by the time picker. Only its
     * hour and minute are reused (in the current timezone); the date is always today or tomorrow.
     * The legacy default value (18:00 UTC) is treated as 18:00 local time so that the default
     * does not shift with the timezone.
     */
    public static long getNextMotivationAlertTime(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        long timestamp = sharedPref.getLong(
                context.getString(R.string.pref_notification_motivation_alert_time),
                LEGACY_DEFAULT_MOTIVATION_ALERT_TIME);
        Calendar calendar = Calendar.getInstance();
        if (timestamp == LEGACY_DEFAULT_MOTIVATION_ALERT_TIME || timestamp <= 0) {
            calendar.set(Calendar.HOUR_OF_DAY, 18);
            calendar.set(Calendar.MINUTE, 0);
        } else {
            Calendar stored = Calendar.getInstance();
            stored.setTimeInMillis(timestamp);
            calendar.set(Calendar.HOUR_OF_DAY, stored.get(Calendar.HOUR_OF_DAY));
            calendar.set(Calendar.MINUTE, stored.get(Calendar.MINUTE));
        }
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static PendingIntent getMotivationAlertPendingIntent(Context context) {
        Intent motivationAlertIntent = new Intent(context, MotivationAlertReceiver.class);
        return PendingIntent.getBroadcast(context, MOTIVATION_ALERT_REQUEST_CODE,
                motivationAlertIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Cancels the motivation alert (if any)
     * @param context The application context
     */
    public static void cancelMotivationAlert(Context context){
        Log.i(LOG_CLASS, "Canceling motivation alert alarm");
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(getMotivationAlertPendingIntent(context));
        }
    }
}
