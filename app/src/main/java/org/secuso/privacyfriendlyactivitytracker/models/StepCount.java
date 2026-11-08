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
package org.secuso.privacyfriendlyactivitytracker.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.utils.UnitHelper;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * A step count object represents an interval in which some steps were taken and which walking mode
 * is related to this interval.
 */

public class StepCount {

    // from https://github.com/bagilevi/android-pedometer/blob/master/src/name/bagi/levente/pedometer/CaloriesNotifier.java
    private static double METRIC_RUNNING_FACTOR = 1.02784823;
    private static double METRIC_WALKING_FACTOR = 0.708;
    private static double METRIC_AVG_FACTOR = (METRIC_RUNNING_FACTOR + METRIC_WALKING_FACTOR) / 2;


    private long id;
    private int stepCount;
    private long startTime;
    private long endTime;
    private WalkingMode walkingMode;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public long getStartTime() {
        return startTime;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public void setEndTime(long endTime) {
        this.endTime = endTime;
    }

    public WalkingMode getWalkingMode() {
        return walkingMode;
    }

    public void setWalkingMode(WalkingMode walkingMode) {
        this.walkingMode = walkingMode;
    }

    /**
     * Gets the distance walked in this interval.
     *
     * @return The distance in meters
     */
    public double getDistance(){
        if(getWalkingMode() != null) {
            return getStepCount() * getWalkingMode().getStepLength();
        }else{
            return 0;
        }
    }

    /**
     * Gets the calories
     * @return the calories in kcal
     */
    public double getCalories(Context context){
        // inspired by https://github.com/bagilevi/android-pedometer/blob/master/src/name/bagi/levente/pedometer/CaloriesNotifier.java
        return getCaloriesFactor(context) * UnitHelper.metersToKilometers(getDistance());
    }

    /**
     * The calorie factor (body weight times the walking/running factor) for the current user.
     * Exposed so that the factor can be read once and reused for many entries.
     *
     * @return the factor which turns the distance in kilometers into kcal
     */
    public static double getCaloriesFactor(Context context) {
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
        String weight = sharedPref.getString(context.getString(R.string.pref_weight), context.getString(R.string.pref_default_weight));
        try {
            return Float.parseFloat(weight) * METRIC_AVG_FACTOR;
        } catch (NumberFormatException e) {
            return Float.parseFloat(context.getString(R.string.pref_default_weight)) * METRIC_AVG_FACTOR;
        }
    }

    /**
     * Gets the calories with a calorie factor which was read once via {@link #getCaloriesFactor(Context)}.
     *
     * @param caloriesFactor the factor returned by {@link #getCaloriesFactor(Context)}
     * @return the calories in kcal
     */
    public double getCalories(double caloriesFactor) {
        return caloriesFactor * UnitHelper.metersToKilometers(getDistance());
    }

    @Override
    public String toString() {
        SimpleDateFormat format = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        return "StepCount{" + format.format(new Date(startTime)) +
                " - " + format.format(new Date(endTime)) +
                ": " + stepCount + " @ " + ((walkingMode == null) ? -1 : walkingMode.getId())+
                '}';
    }
}
