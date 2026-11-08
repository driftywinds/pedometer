/*
    Privacy Friendly Pedometer is licensed under the GPLv3.

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class ReportBucketsTest {

    private WalkingMode modeWithStepLength(double stepLength) {
        WalkingMode mode = new WalkingMode();
        mode.setStepLength(stepLength);
        return mode;
    }

    private StepCount stepCountAt(Calendar time, int steps, WalkingMode mode) {
        StepCount stepCount = new StepCount();
        stepCount.setEndTime(time.getTimeInMillis());
        stepCount.setStepCount(steps);
        stepCount.setWalkingMode(mode);
        return stepCount;
    }

    private Calendar at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    @Test
    public void hourly_createsTwentyFourBucketsAndDistributesSteps() {
        Calendar day = at(2024, Calendar.MARCH, 12, 12, 0);
        WalkingMode mode = modeWithStepLength(0.5);
        List<StepCount> entries = new ArrayList<>();
        entries.add(stepCountAt(at(2024, Calendar.MARCH, 12, 10, 30), 100, mode));

        List<ReportBuckets.Bucket> buckets = ReportBuckets.hourly(day, entries, 0, mode, 1.0);

        assertEquals(24, buckets.size());
        assertEquals(100, buckets.get(10).steps);
        assertEquals(50.0, buckets.get(10).distanceMeters, 0.0001);
        assertEquals(0.05, buckets.get(10).caloriesKcal, 0.0001);
        assertEquals(0, buckets.get(9).steps);
        assertSame(mode, buckets.get(10).walkingMode);
    }

    @Test
    public void hourly_addsUnsavedStepsToTheCurrentHour() {
        Calendar now = Calendar.getInstance();
        WalkingMode mode = modeWithStepLength(1.0);
        List<ReportBuckets.Bucket> buckets = ReportBuckets.hourly(now, new ArrayList<>(), 250, mode, 1.0);

        int total = 0;
        int bucketsWithSteps = 0;
        for (ReportBuckets.Bucket bucket : buckets) {
            total += bucket.steps;
            if (bucket.steps > 0) {
                bucketsWithSteps++;
                assertEquals(250.0, bucket.distanceMeters, 0.0001);
            }
        }
        assertEquals(250, total);
        assertEquals(1, bucketsWithSteps);
    }

    @Test
    public void daily_createsOneBucketPerDayAndIgnoresEntriesOutsideThePeriod() {
        Calendar firstDay = at(2024, Calendar.FEBRUARY, 10, 12, 0);
        WalkingMode mode = modeWithStepLength(0.8);
        List<StepCount> entries = new ArrayList<>();
        entries.add(stepCountAt(at(2024, Calendar.FEBRUARY, 10, 23, 59), 10, mode));
        entries.add(stepCountAt(at(2024, Calendar.FEBRUARY, 16, 1, 0), 20, mode));
        entries.add(stepCountAt(at(2024, Calendar.FEBRUARY, 17, 1, 0), 30, mode));

        List<ReportBuckets.Bucket> buckets = ReportBuckets.daily(firstDay, 7, entries, 0, mode, 2.0);

        assertEquals(7, buckets.size());
        assertEquals(10, buckets.get(0).steps);
        assertEquals(20, buckets.get(6).steps);
        assertEquals(0, buckets.get(3).steps);
        // The entry of February 17 is outside of the period and must not be counted.
        int total = 0;
        for (ReportBuckets.Bucket bucket : buckets) {
            total += bucket.steps;
        }
        assertEquals(30, total);
        assertEquals(10 * 0.8 / 1000.0 * 2.0, buckets.get(0).caloriesKcal, 0.0001);
    }

    @Test
    public void startOfDay_doesNotModifyTheInput() {
        Calendar day = at(2024, Calendar.MAY, 5, 17, 45);
        Calendar start = ReportBuckets.startOfDay(day);

        assertEquals(17, day.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, start.get(Calendar.MINUTE));
        assertEquals(0, start.get(Calendar.SECOND));
        assertEquals(0, start.get(Calendar.MILLISECOND));
    }
}
