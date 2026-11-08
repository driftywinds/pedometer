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
package org.secuso.privacyfriendlyactivitytracker.persistence;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Helper to save and restore step count from database.
 *
 * @author Tobias Neidig
 * @version 20160720
 */

public class StepCountPersistenceHelper {

    /**
     * Broadcast action identifier for messages broadcast when step count was saved
     */
    public static final String BROADCAST_ACTION_STEPS_SAVED = "org.secuso.privacyfriendlystepcounter.STEPS_SAVED";
    public static final String BROADCAST_ACTION_STEPS_UPDATED = "org.secuso.privacyfriendlystepcounter.STEPS_UPDATED";
    public static final String BROADCAST_ACTION_STEPS_INSERTED = "org.secuso.privacyfriendlystepcounter.STEPS_INSERTED";
    public static String LOG_CLASS = StepCountPersistenceHelper.class.getName();

    /**
     * Stores the current step count to database and resets the step counter in step-detector
     *
     * @param serviceBinder The binder for stepCountService
     * @param context       The application context
     * @return true if save was successful else false.
     */
    public static boolean storeStepCounts(IBinder serviceBinder, Context context, WalkingMode walkingMode) {
        if (serviceBinder == null) {
            Log.e(LOG_CLASS, "Cannot store step count because service binder is null.");
            return false;
        }
        if (!(serviceBinder instanceof AbstractStepDetectorService.StepDetectorBinder)) {
            Log.e(LOG_CLASS, "Cannot store step count - unexpected binder type.");
            return false;
        }
        AbstractStepDetectorService.StepDetectorBinder myBinder = (AbstractStepDetectorService.StepDetectorBinder) serviceBinder;

        // Get the steps since last save
        int stepCountSinceLastSave = myBinder.stepsSinceLastSave();

        long updateInterval = getUpdateInterval(context);
        StepCount lastStoredStepCount = new StepCountDbHelper(context).getLatestStepCount();
        long currentTime = System.currentTimeMillis();
        long currentUpdateIntervalStartTime = currentTime - (updateInterval > 0 ? currentTime % updateInterval : 0);
        boolean sameWalkingMode = sameWalkingMode(lastStoredStepCount, walkingMode);
        boolean sameDay = lastStoredStepCount != null && isSameLocalDay(lastStoredStepCount.getEndTime(), currentTime);
        if (lastStoredStepCount == null
                || !sameDay
                || lastStoredStepCount.getEndTime() < currentUpdateIntervalStartTime
                || !sameWalkingMode) {
            // Create a new entry when there is none yet, when the previous one belongs to the
            // previous day, when it is older than the current update interval or when the
            // walking mode changed.
            StepCount stepCount = new StepCount();
            stepCount.setWalkingMode(walkingMode);
            stepCount.setStepCount(stepCountSinceLastSave);
            stepCount.setEndTime(currentTime);
            new StepCountDbHelper(context).addStepCount(stepCount);
            Log.i(LOG_CLASS, "Creating new step count");
        } else {
            lastStoredStepCount.setStepCount(lastStoredStepCount.getStepCount() + stepCountSinceLastSave);
            lastStoredStepCount.setEndTime(currentTime);
            new StepCountDbHelper(context).updateStepCount(lastStoredStepCount);
            Log.i(LOG_CLASS, "Updating last stored step count - not creating a new one");
        }
        // reset step count
        myBinder.resetStepCount();
        Log.i(LOG_CLASS, "Stored " + stepCountSinceLastSave + " steps");

        // broadcast the event
        Intent localIntent = new Intent(BROADCAST_ACTION_STEPS_SAVED);
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);

        return true;
    }

    private static boolean sameWalkingMode(StepCount stepCount, WalkingMode walkingMode) {
        if (stepCount == null) {
            return false;
        }
        WalkingMode storedMode = stepCount.getWalkingMode();
        if (storedMode == null && walkingMode == null) {
            return true;
        }
        if (storedMode == null || walkingMode == null) {
            return false;
        }
        return storedMode.getId() == walkingMode.getId();
    }

    /**
     * Steps that cross midnight must not be merged into one entry, otherwise the whole entry
     * would be counted for the day it ends in.
     */
    private static boolean isSameLocalDay(long first, long second) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(first);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * The configured interval after which a new database entry is started.
     */
    public static long getUpdateInterval(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String value = sharedPref.getString(context.getString(R.string.pref_hw_background_counter_frequency), "3600000");
        try {
            return Math.max(1L, Long.parseLong(value));
        } catch (NumberFormatException e) {
            return 3600000L;
        }
    }

    /**
     * Stores the given step count to database and sends broadcast-event
     *
     * @param stepCount The step count to save
     * @param context       The application context
     * @return true if save was successful else false.
     */
    public static boolean storeStepCount(StepCount stepCount, Context context) {
        new StepCountDbHelper(context).addStepCount(stepCount);

        // broadcast the event
        Intent localIntent = new Intent(BROADCAST_ACTION_STEPS_INSERTED);
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
        return true;
    }

    /**
     * Updates the given step count in database
     *
     * @param stepCount The step count to save
     * @param context       The application context
     * @return true if save was successful else false.
     */
    public static boolean updateStepCount(StepCount stepCount, Context context) {
        new StepCountDbHelper(context).updateStepCount(stepCount);
        // broadcast the event
        Intent localIntent = new Intent(BROADCAST_ACTION_STEPS_UPDATED);
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(context).sendBroadcast(localIntent);
        return true;
    }

    /**
     * Get the number of steps for the given day
     *
     * @param calendar The day (in user's timezone)
     * @param context  The application context
     * @return the number of steps
     */
    public static int getStepCountForDay(Calendar calendar, Context context) {
        long[] interval = getDayInterval(calendar);
        return StepCountPersistenceHelper.getStepCountForInterval(interval[0], interval[1], context);
    }

    /**
     * Get the step count models for the given day
     *
     * @param calendar The day (in user's timezone)
     * @param context  The application context
     * @return the step count models for the day
     */
    public static List<StepCount> getStepCountsForDay(Calendar calendar, Context context) {
        long[] interval = getDayInterval(calendar);
        return StepCountPersistenceHelper.getStepCountsForInterval(interval[0], interval[1], context);
    }

    /**
     * Returns the start (inclusive) and end (inclusive) of the day of the given calendar.
     * The passed calendar is not modified.
     */
    private static long[] getDayInterval(Calendar calendar) {
        Calendar start = (Calendar) calendar.clone();
        start.set(Calendar.MILLISECOND, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.HOUR_OF_DAY, 0);
        long startTime = start.getTimeInMillis();
        start.add(Calendar.DAY_OF_MONTH, 1);
        // The database query uses an inclusive upper bound.
        long endTime = start.getTimeInMillis() - 1;
        return new long[]{startTime, endTime};
    }

    /**
     * @deprecated Use {@link StepCountDbHelper#getStepCountsForInterval(long, long)} instead.
     *
     * Returns the stepCount models for the steps walked in the given interval.
     *
     * @param start_time The start time in users timezone
     * @param end_time   The end time in users timezone
     * @param context    The application context
     * @return The @{see StepCount}-Models between start and end time
     */
    public static List<StepCount> getStepCountsForInterval(long start_time, long end_time, Context context) {
        if (context == null) {
            Log.e(LOG_CLASS, "Cannot get step count - context is null");
            return new ArrayList<>();
        }
        return new StepCountDbHelper(context).getStepCountsForInterval(start_time, end_time);
    }

    /**
     * Returns the stepCount models for the steps walked.
     *
     * @param context    The application context
     * @return The @{see StepCount}-Models
     */
    public static List<StepCount> getStepCountsForever(Context context) {
        if (context == null) {
            Log.e(LOG_CLASS, "Cannot get step count - context is null");
            return new ArrayList<>();
        }
        return new StepCountDbHelper(context).getAllStepCounts();
    }

    /**
     * Returns the number of steps walked in the given time interval
     *
     * @param start_time The start time
     * @param end_time   The end time
     * @param context    The application context
     * @return Number of steps between start and end time
     */
    public static int getStepCountForInterval(long start_time, long end_time, Context context) {
        int steps = 0;
        for (StepCount s : getStepCountsForInterval(start_time, end_time, context)) {
            steps += s.getStepCount();
        }
        return steps;
    }

    /**
     * Returns the date of first entry in database
     * @param context Application context
     * @return Date of first entry or default today
     */
    public static Date getDateOfFirstEntry(Context context) {
        StepCount s = new StepCountDbHelper(context).getFirstStepCount();
        Date date = Calendar.getInstance().getTime(); // fallback is today
        if (s != null) {
            date.setTime(s.getEndTime());
        }
        return date;
    }

    /**
     * Returns the last step count entry for given day
     * @param day the day
     * @param context application context
     * @return the last step count entry for given day
     */
    public static StepCount getLastStepCountEntryForDay(Calendar day, Context context) {
        List<StepCount> stepCounts = getStepCountsForDay(day, context);
        if (stepCounts.isEmpty()) {
            return null;
        }
        return stepCounts.get(stepCounts.size() - 1);
    }

    /**
     * Closes the cached database connection. This is required after the database file was
     * replaced (e.g. by an imported backup) so that the next access opens the new file.
     */
    public static void invalidateReference() {
        StepCountDbHelper.invalidateReference();
    }
}
