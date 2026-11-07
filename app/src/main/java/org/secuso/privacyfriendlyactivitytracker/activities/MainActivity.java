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
package org.secuso.privacyfriendlyactivitytracker.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.fragments.DailyReportFragment;
import org.secuso.privacyfriendlyactivitytracker.fragments.MainFragment;
import org.secuso.privacyfriendlyactivitytracker.fragments.MonthlyReportFragment;
import org.secuso.privacyfriendlyactivitytracker.fragments.WeeklyReportFragment;
import org.secuso.privacyfriendlyactivitytracker.tutorial.TutorialActivity;

/**
 * Main view incl. navigation drawer and fragments
 *
 * @author Tobias Neidig, Karola Marky
 * @version 20161214
 */

public class MainActivity extends BaseActivity implements DailyReportFragment.OnFragmentInteractionListener, WeeklyReportFragment.OnFragmentInteractionListener, MonthlyReportFragment.OnFragmentInteractionListener {

    /**
     * Broadcast action which is sent when all local data was replaced by an imported backup.
     * The visible reports have to be rebuilt in that case.
     */
    public static final String BROADCAST_ACTION_DATA_REPLACED = "org.secuso.privacyfriendlyactivitytracker.action.DATA_REPLACED";

    private final BroadcastReceiver dataReplacedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            // Rebuild the home view so that all reports are read from the imported data.
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content_frame, new MainFragment(), "MainFragment")
                    .commitAllowingStateLoss();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!hasActivityRecognitionPermission()) {
            startActivity(new Intent(MainActivity.this, TutorialActivity.class));
            finish();
        }
        setContentView(R.layout.activity_main);
        // init preferences
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        PreferenceManager.setDefaultValues(this, R.xml.pref_notification, false);

        LocalBroadcastManager.getInstance(this).registerReceiver(dataReplacedReceiver,
                new IntentFilter(BROADCAST_ACTION_DATA_REPLACED));

        // Load first view
        final FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.replace(R.id.content_frame, new MainFragment(), "MainFragment");
        fragmentTransaction.commit();

    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReplacedReceiver);
        super.onDestroy();
    }

    @Override
    protected int getNavigationDrawerID() {
        return R.id.menu_home;
    }


}
