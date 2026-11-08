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
package org.secuso.privacyfriendlyactivitytracker.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.app.JobIntentService;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.activities.MainActivity;
import org.secuso.privacyfriendlyactivitytracker.activities.TrainingActivity;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.TrainingPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModePersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.AndroidVersionHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.NotificationHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.UnitHelper;

import java.util.Calendar;
import java.util.List;

/**
 * Generic class for a step detector.
 * Does not detect steps itself - the step detection has to be done in the subclasses.
 *
 * @author Tobias Neidig
 * @version 20160810
 */

public abstract class AbstractStepDetectorService extends JobIntentService implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * Broadcast action identifier for messages broadcasted when new steps were detected
     */
    public static final String BROADCAST_ACTION_STEPS_DETECTED = "org.secuso.privacyfriendlystepcounter.STEPS_DETECTED";
    /**
     * Extra key for new steps which were added since last broadcast.
     */
    public static final String EXTENDED_DATA_NEW_STEPS = "org.secuso.privacyfriendlystepcounter.NEW_STEPS";
    /**
     * Extra key for total step count since service start
     */
    public static final String EXTENDED_DATA_TOTAL_STEPS = "org.secuso.privacyfriendlystepcounter.TOTAL_STEPS";
    /**
     * Action which asks a running step detector service to (re-)post its permanent notification.
     * This is used by the "force notification" control on the home screen to recover the
     * notification (and with it the foreground state of the service) after it was lost.
     */
    public static final String ACTION_FORCE_NOTIFICATION = "org.secuso.privacyfriendlyactivitytracker.action.FORCE_NOTIFICATION";
    /**
     * The notification id used for permanent step count notification
     */
    public static String CHANNEL_ID = "pedometer";
    public static final int NOTIFICATION_ID = 42;
    private static final String LOG_TAG = AbstractStepDetectorService.class.getName();
    private final IBinder mBinder = new StepDetectorBinder();
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver();
    private NotificationManager mNotifyManager;
    private PowerManager.WakeLock mWakeLock;
    /**
     * Number of steps the user wants to walk every day
     */
    private int dailyStepGoal = 0;
    /**
     * Number of in-database-saved steps.
     */
    private int totalStepsAtLastSave = 0;
    /**
     * Number of in-database-saved calories;
     */
    private double totalCaloriesAtLastSave = 0;
    /**
     * Distance of in-database-saved steps
     */
    private double totalDistanceAtLastSave = 0;

    /**
     * Number of steps counted since service start
     */
    private int total_steps = 0;

    /**
     * Creates an IntentService.  Invoked by your subclass's constructor.
     *
     */
    public AbstractStepDetectorService() {
        super();
    }

    /**
     * Notifies any subscriber about the detected amount of steps
     *
     * @param count The number of detected steps (greater zero)
     */
    protected void onStepDetected(int count) {
        if (count <= 0) {
            return;
        }
        this.total_steps += count;
        Log.i(LOG_TAG, count + " Step(s) detected. Steps since service start: " + this.total_steps);
        // broadcast the new steps
        Intent localIntent = new Intent(BROADCAST_ACTION_STEPS_DETECTED)
                // Add new step count
                .putExtra(EXTENDED_DATA_NEW_STEPS, count)
                .putExtra(EXTENDED_DATA_TOTAL_STEPS, total_steps);
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

        // Update notification
        updateNotification();
    }

    /**
     * Builds the permanent step count notification
     *
     * @param additionalStepCount The number of steps since last save
     * @return the new notification
     */
    protected Notification buildNotification(StepCount additionalStepCount) {
        int totalSteps = this.totalStepsAtLastSave + additionalStepCount.getStepCount();
        double totalDistance = this.totalDistanceAtLastSave + additionalStepCount.getDistance();
        double totalCalories = this.totalCaloriesAtLastSave
                + additionalStepCount.getCalories(getApplicationContext());
        return NotificationHelper.buildTrackingNotification(this, totalSteps, totalDistance,
                totalCalories, this.dailyStepGoal);
    }

    // has to be implemented by subclasses
    @Override
    public abstract void onSensorChanged(SensorEvent event);

    /**
     * Whether the notification should be canceled when service dies.
     * @return true if notification should be canceled else false
     */
    protected boolean cancelNotificationOnDestroy(){
        return true;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // currently doing nothing here.
    }

    @Override
    public void onCreate() {
        createNotificationChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification(this.stepCountFromTotalSteps()));
        } catch (RuntimeException e) {
            // This can happen if the system does not allow starting a foreground service
            // (e.g. a missing notification permission or a revoked activity recognition
            // permission on Android 14). In that case we stop ourselves instead of crashing
            // the whole application; the user can restore tracking from the home screen.
            Log.e(LOG_TAG, "Could not start the foreground notification.", e);
            stopSelf();
        }
        super.onCreate();
        Log.i(LOG_TAG, "Creating service cycle."+ this.getClass().getName());
    }

    @Override
    public void onDestroy() {
        Log.i(LOG_TAG, "Destroying service cycle."+ this.getClass().getName());
        // release wake lock if any
        acquireOrReleaseWakeLock();
        // Unregister sensor listeners
        SensorManager sensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
        sensorManager.unregisterListener(this);
        // Cancel notification
        if (mNotifyManager != null && cancelNotificationOnDestroy()) {
            mNotifyManager.cancel(NOTIFICATION_ID);
        }
        // Unregister shared preferences listeners
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPref.unregisterOnSharedPreferenceChangeListener(this);
        // Force save of step count
        StepCountPersistenceHelper.storeStepCounts(this.mBinder, getApplicationContext(), WalkingModePersistenceHelper.getActiveMode(getApplicationContext()));
        // StepDetectionServiceHelper.startPersistenceService(this);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(LOG_TAG, "Starting service cycle." + this.getClass().getName());
        acquireOrReleaseWakeLock();

        if(!StepDetectionServiceHelper.isStepDetectionEnabled(getApplicationContext())){
            stopSelf();
        }

        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean counter = Integer.valueOf(sharedPref.getString(this.getString(R.string.pref_which_step_hardware), "0")) == 0;

        // register for sensors
        if(!AndroidVersionHelper.isHardwareStepCounterEnabled(this)){
            //use accelerometer
            SensorManager sensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        } else if(counter) {
            SensorManager sensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                Long updateInterval = Long.valueOf(sharedPref.getString(this.getString(R.string.pref_hw_background_counter_frequency), "3600000"));
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, updateInterval.intValue());
            } else {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
        } else {
            SensorManager sensorManager = (SensorManager) getApplicationContext().getSystemService(SENSOR_SERVICE);
            Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        }


        // Get daily goal(s) from preferences
        String d = sharedPref.getString(getString(R.string.pref_daily_step_goal), "10000");
        this.dailyStepGoal = Integer.parseInt(d);
        sharedPref.registerOnSharedPreferenceChangeListener(this);

        // register for steps-saved-event
        IntentFilter filterRefreshUpdate = new IntentFilter();
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_SAVED);
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_INSERTED);
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_UPDATED );
        filterRefreshUpdate.addAction(TrainingActivity.BROADCAST_ACTION_TRAINING_STOPPED);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, filterRefreshUpdate);
        // load step count from database
        getStepsAtLastSave();

        boolean forceNotification = intent != null && ACTION_FORCE_NOTIFICATION.equals(intent.getAction());
        // Refresh the notification so that it always reflects the current step goal and step
        // count. This also re-posts it when the system restarted the service in the background
        // and when the user explicitly asked for it from the home screen.
        updateNotification();
        if (forceNotification) {
            Log.i(LOG_TAG, "Permanent notification was force-triggered.");
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return this.mBinder;
    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // Detect changes on preferences and update our internal variable
        if (key.equals(getString(R.string.pref_daily_step_goal))) {
            dailyStepGoal = Integer.parseInt(sharedPreferences.getString(getString(R.string.pref_daily_step_goal), "10000"));
            updateNotification();
        } else if (key.equals(getString(R.string.pref_notification_permanent_show_steps)) ||
                key.equals(getString(R.string.pref_notification_permanent_show_distance)) ||
                key.equals(getString(R.string.pref_notification_permanent_show_calories))) {
            updateNotification();
        } else if(key.equals(getString(R.string.pref_use_wake_lock))){
            acquireOrReleaseWakeLock();
        }
    }

    /**
     * Fetches the step count for this day from database
     */
    private void getStepsAtLastSave() {
        List<StepCount> stepCounts = StepCountPersistenceHelper.getStepCountsForDay(Calendar.getInstance(), getApplicationContext());
        totalStepsAtLastSave = 0;
        totalDistanceAtLastSave = 0;
        totalCaloriesAtLastSave = 0;
        for (StepCount stepCount : stepCounts) {
            totalStepsAtLastSave += stepCount.getStepCount();
            totalDistanceAtLastSave += stepCount.getDistance();
            totalCaloriesAtLastSave += stepCount.getCalories(getApplicationContext());
        }
    }

    /**
     * Transforms the current total_steps (total steps since last save) in an @{see StepCount} object
     *
     * @return total_steps since last save as stepCount object
     */
    protected StepCount stepCountFromTotalSteps() {
        StepCount stepCount = new StepCount();
        stepCount.setStepCount(total_steps);
        stepCount.setWalkingMode(WalkingModePersistenceHelper.getActiveMode(getApplicationContext())); // use current walking mode
        return stepCount;
    }

    /**
     * Updates or creates the progress notification
     */
    protected void updateNotification() {
        if (mNotifyManager == null) {
            mNotifyManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        if (mNotifyManager == null) {
            Log.w(LOG_TAG, "Cannot update notification - notification manager is not available.");
            return;
        }
        if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean(this.getString(R.string.pref_step_counter_enabled), true)){
            Notification notification = buildNotification(this.stepCountFromTotalSteps());
            mNotifyManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private void acquireOrReleaseWakeLock(){
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useWakeLock = sharedPref.getBoolean(getString(R.string.pref_use_wake_lock), false);
        boolean useWakeLockDuringTraining = sharedPref.getBoolean(getString(R.string.pref_use_wake_lock_during_training), true);
        boolean isTrainingActive = TrainingPersistenceHelper.getActiveItem(getApplicationContext()) != null;
        if(mWakeLock == null && (useWakeLock || (useWakeLockDuringTraining && isTrainingActive))) {
            acquireWakeLock();
        }
        if(mWakeLock != null && !(useWakeLock || (useWakeLockDuringTraining && isTrainingActive))){
            releaseWakeLock();
        }
    }

    /**
     * Acquires a wakelock
     */
    private void acquireWakeLock(){
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if(mWakeLock == null || !mWakeLock.isHeld()) {
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pfPedometer:StepDetectorWakeLock");
            mWakeLock.acquire();
        }
    }

    /**
     * Releases the wake lock if there is any.
     */
    private void releaseWakeLock(){
        if(mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
    }

    /**
     * Class used for the client Binder.
     *
     * @author Tobias Neidig
     * @version 20160611
     */
    public class StepDetectorBinder extends Binder {
        /**
         * Get the number of steps which were taken since service starts.
         *
         * @return Step count since service start
         */
        public int stepsSinceLastSave() {
            return total_steps;
        }

        /**
         * Resets the step count since last save
         * Is usually called when we saved the steps.
         */
        public void resetStepCount() {
            total_steps = 0;
        }

        public AbstractStepDetectorService getService() {
            return AbstractStepDetectorService.this;
        }
    }

    public class BroadcastReceiver extends android.content.BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                Log.w(LOG_TAG, "Received intent which is null.");
                return;
            }
            switch (intent.getAction()) {
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_INSERTED:
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_UPDATED:
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_SAVED:
                    // Steps were saved, reload step count from database
                    getStepsAtLastSave();
                    updateNotification();
                    break;
                case TrainingActivity.BROADCAST_ACTION_TRAINING_STOPPED:
                    acquireOrReleaseWakeLock();
                default:
            }
        }
    }

    void createNotificationChannel() {
        NotificationHelper.ensureTrackingChannel(this);
    }

    public IBinder getmStepDetectorBinder() {
        return mBinder;
    }
}
