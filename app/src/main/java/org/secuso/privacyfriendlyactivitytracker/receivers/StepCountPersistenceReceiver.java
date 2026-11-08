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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModePersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;

/**
 * Asks the step detector service to store the current step count.
 * <p>
 * The receiver used to bind to the service and write the database from the binder callback
 * after {@link #onReceive} had returned. That made the write depend on the process staying
 * alive and duplicated the service lifecycle. The service now owns the write via
 * {@code ACTION_SAVE_STEPS}, which is also the only place which resets the counter.
 *
 * @author Tobias Neidig
 * @version 20160720
 */

public class StepCountPersistenceReceiver extends BroadcastReceiver {
    private static final String LOG_CLASS = StepCountPersistenceReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(LOG_CLASS, "Storing the steps");
        Context appContext = context.getApplicationContext();
        long oldWalkingModeId = -1;
        if (intent != null) {
            oldWalkingModeId = intent.getLongExtra(
                    WalkingModePersistenceHelper.BROADCAST_EXTRA_OLD_WALKING_MODE, -1);
        }
        // Ask the service to persist the steps. The service keeps the counted steps in memory,
        // so this is the only place which knows them.
        StepDetectionServiceHelper.saveStepsNow(appContext, oldWalkingModeId);
        // Re-arm the alarms - they are one-shot so that they also work in Doze mode.
        StepDetectionServiceHelper.schedulePersistenceService(appContext);
        WidgetReceiver.forceWidgetUpdate(appContext);
    }
}
