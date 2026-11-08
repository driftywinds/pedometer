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

import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates stored step counts into the buckets which are displayed by the report screens.
 * <p>
 * All report screens used to run one database query per hour or day. This helper is fed with
 * the result of a single query for the whole period and distributes the entries in memory,
 * which makes a refresh cheap enough to run on the UI thread.
 */
public final class ReportBuckets {

    private ReportBuckets() {
    }

    /**
     * One hour or one day of the report.
     */
    public static final class Bucket {
        public final long startTime;
        public final long endTime;
        public int steps;
        public double distanceMeters;
        public double caloriesKcal;
        public WalkingMode walkingMode;

        Bucket(long startTime, long endTime) {
            this.startTime = startTime;
            this.endTime = endTime;
        }
    }

    /**
     * Splits the given entries into the 24 hours of the given day and adds steps which were
     * counted but not saved yet to the current hour.
     *
     * @param day            the displayed day (not modified)
     * @param storedEntries  entries of the whole day, as returned by the database
     * @param unsavedSteps   steps which are not in the database yet (may be 0)
     * @param activeMode     the walking mode of the unsaved steps
     * @param caloriesFactor factor which converts the walked kilometers into kcal
     * @return 24 buckets, one per hour
     */
    public static List<Bucket> hourly(Calendar day, List<StepCount> storedEntries,
                                      int unsavedSteps, WalkingMode activeMode,
                                      double caloriesFactor) {
        Calendar cursor = startOfDay(day);
        List<Bucket> buckets = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            cursor.set(Calendar.HOUR_OF_DAY, hour);
            Calendar next = (Calendar) cursor.clone();
            next.add(Calendar.HOUR_OF_DAY, 1);
            buckets.add(new Bucket(cursor.getTimeInMillis(), next.getTimeInMillis() - 1));
        }
        distribute(buckets, storedEntries, caloriesFactor, true);
        addUnsavedSteps(buckets, unsavedSteps, activeMode, caloriesFactor);
        return buckets;
    }

    /**
     * Splits the given entries into consecutive days and adds steps which were counted but not
     * saved yet to today's bucket (if today is part of the period).
     *
     * @param firstDay       the first day of the period (not modified)
     * @param dayCount       number of days of the period
     * @param storedEntries  entries of the whole period, as returned by the database
     * @param unsavedSteps   steps which are not in the database yet (may be 0)
     * @param activeMode     the walking mode of the unsaved steps
     * @param caloriesFactor factor which converts the walked kilometers into kcal
     * @return one bucket per day
     */
    public static List<Bucket> daily(Calendar firstDay, int dayCount,
                                     List<StepCount> storedEntries, int unsavedSteps,
                                     WalkingMode activeMode, double caloriesFactor) {
        Calendar cursor = startOfDay(firstDay);
        List<Bucket> buckets = new ArrayList<>(Math.max(0, dayCount));
        for (int day = 0; day < dayCount; day++) {
            Calendar next = (Calendar) cursor.clone();
            next.add(Calendar.DAY_OF_MONTH, 1);
            buckets.add(new Bucket(cursor.getTimeInMillis(), next.getTimeInMillis() - 1));
            cursor = next;
        }
        distribute(buckets, storedEntries, caloriesFactor, false);
        addUnsavedSteps(buckets, unsavedSteps, activeMode, caloriesFactor);
        return buckets;
    }

    private static void distribute(List<Bucket> buckets, List<StepCount> storedEntries,
                                   double caloriesFactor, boolean hourly) {
        if (storedEntries == null || storedEntries.isEmpty()) {
            return;
        }
        Map<Long, Bucket> byStart = new HashMap<>();
        for (Bucket bucket : buckets) {
            byStart.put(bucket.startTime, bucket);
        }
        Calendar cursor = Calendar.getInstance();
        for (StepCount stepCount : storedEntries) {
            cursor.setTimeInMillis(stepCount.getEndTime());
            cursor.set(Calendar.MINUTE, 0);
            cursor.set(Calendar.SECOND, 0);
            cursor.set(Calendar.MILLISECOND, 0);
            if (!hourly) {
                cursor.set(Calendar.HOUR_OF_DAY, 0);
            }
            Bucket bucket = byStart.get(cursor.getTimeInMillis());
            if (bucket == null) {
                continue;
            }
            bucket.steps += stepCount.getStepCount();
            bucket.distanceMeters += stepCount.getDistance();
            if (stepCount.getWalkingMode() != null) {
                bucket.walkingMode = stepCount.getWalkingMode();
            }
        }
        for (Bucket bucket : buckets) {
            bucket.caloriesKcal = UnitHelper.metersToKilometers(bucket.distanceMeters) * caloriesFactor;
        }
    }

    private static void addUnsavedSteps(List<Bucket> buckets, int unsavedSteps,
                                        WalkingMode activeMode, double caloriesFactor) {
        if (unsavedSteps <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Bucket bucket : buckets) {
            if (now >= bucket.startTime && now <= bucket.endTime) {
                bucket.steps += unsavedSteps;
                if (activeMode != null) {
                    bucket.distanceMeters += unsavedSteps * activeMode.getStepLength();
                    bucket.walkingMode = activeMode;
                }
                bucket.caloriesKcal = UnitHelper.metersToKilometers(bucket.distanceMeters) * caloriesFactor;
                return;
            }
        }
    }

    /**
     * @return a calendar at 00:00:00.000 of the day of the given calendar (the input is not
     * modified).
     */
    public static Calendar startOfDay(Calendar day) {
        Calendar start = (Calendar) day.clone();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return start;
    }
}
