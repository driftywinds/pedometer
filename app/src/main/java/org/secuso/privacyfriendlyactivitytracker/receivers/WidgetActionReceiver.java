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
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;

/**
 * Handles the pause/continue actions of the home screen widget.
 * <p>
 * The receiver is not exported, so that only this application (or an explicit pending
 * intent created by it) can toggle the step counter.
 */
public class WidgetActionReceiver extends BroadcastReceiver {
    public static final String PAUSE_STEP_DETECTION_ACTION = "org.secuso.privacyfriendlyactivitytracker.PAUSE_STEP_DETECTION_ACTION";
    public static final String CONTINUE_STEP_DETECTION_ACTION = "org.secuso.privacyfriendlyactivitytracker.CONTINUE_STEP_DETECTION_ACTION";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        Context appContext = context.getApplicationContext();
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(appContext);
        SharedPreferences.Editor editor = sharedPref.edit();
        switch (intent.getAction()) {
            case PAUSE_STEP_DETECTION_ACTION:
                editor.putBoolean(appContext.getString(R.string.pref_step_counter_enabled), false);
                editor.apply();
                StepDetectionServiceHelper.stopAllIfNotRequired(appContext);
                break;
            case CONTINUE_STEP_DETECTION_ACTION:
                editor.putBoolean(appContext.getString(R.string.pref_step_counter_enabled), true);
                editor.apply();
                StepDetectionServiceHelper.startAllIfEnabled(appContext);
                break;
            default:
                // Unknown action - ignore.
        }
        WidgetReceiver.forceWidgetUpdate(appContext);
    }
}
