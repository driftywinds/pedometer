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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class PeriodicBackupManagerTest {

    @Test
    public void computeNextDue_isAlwaysInTheFutureAtSixAm() {
        Calendar after = Calendar.getInstance();
        after.set(2024, Calendar.JUNE, 15, 12, 30, 0);
        after.set(Calendar.MILLISECOND, 0);

        long due = PeriodicBackupManager.computeNextDue(after.getTimeInMillis());

        Calendar dueCalendar = Calendar.getInstance();
        dueCalendar.setTimeInMillis(due);
        assertTrue(due > after.getTimeInMillis());
        assertEquals(6, dueCalendar.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, dueCalendar.get(Calendar.MINUTE));
    }

    @Test
    public void nextDueAfter_skipsMissedOccurrencesForDailyInterval() {
        Calendar due = Calendar.getInstance();
        due.set(2024, Calendar.JUNE, 15, 6, 0, 0);
        due.set(Calendar.MILLISECOND, 0);
        long dueMillis = due.getTimeInMillis();
        long now = dueMillis + 3 * 24 * 60 * 60 * 1000L;

        long next = PeriodicBackupManager.nextDueAfter(dueMillis, now, PeriodicBackupManager.INTERVAL_DAILY);

        assertTrue(next > now);
        assertTrue(next - dueMillis <= 4 * 24 * 60 * 60 * 1000L);
    }

    @Test
    public void nextDueAfter_advancesByTheConfiguredInterval() {
        long due = 1_700_000_000_000L;
        long now = due + 1000L;

        long weekly = PeriodicBackupManager.nextDueAfter(due, now, PeriodicBackupManager.INTERVAL_WEEKLY);
        Calendar expected = Calendar.getInstance();
        expected.setTimeInMillis(due);
        expected.add(Calendar.DAY_OF_MONTH, 7);

        assertEquals(expected.getTimeInMillis(), weekly);
    }
}
