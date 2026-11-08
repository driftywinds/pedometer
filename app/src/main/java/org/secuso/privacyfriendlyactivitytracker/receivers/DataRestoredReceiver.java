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

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.secuso.privacyfriendlyactivitytracker.activities.MainActivity;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.TrainingDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModeDbHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;

/**
 * Receives the notification of the backup process after a restore was applied.
 * <p>
 * The restore runs in the {@code :backup} process, so the database connections of this process
 * would otherwise keep reading and writing the replaced files. This receiver closes them,
 * tells the UI to reload and restarts tracking.
 */
public class DataRestoredReceiver extends BroadcastReceiver {
    private static final String LOG_CLASS = DataRestoredReceiver.class.getName();

    public static final String ACTION_DATA_RESTORED =
            "org.secuso.privacyfriendlyactivitytracker.action.DATA_RESTORED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(LOG_CLASS, "Data was restored, dropping database connections.");
        Context appContext = context.getApplicationContext();

        StepDetectionServiceHelper.stopStepDetection(appContext);
        StepCountPersistenceHelper.invalidateReference();
        StepCountDbHelper.invalidateReference();
        TrainingDbHelper.invalidateReference();
        WalkingModeDbHelper.invalidateReference();

        LocalBroadcastManager.getInstance(appContext).sendBroadcast(
                new Intent(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_UPDATED));
        LocalBroadcastManager.getInstance(appContext).sendBroadcast(
                new Intent(MainActivity.BROADCAST_ACTION_DATA_REPLACED));

        StepDetectionServiceHelper.startAllIfEnabled(appContext);
        WidgetReceiver.forceWidgetUpdate(appContext);
    }
}
