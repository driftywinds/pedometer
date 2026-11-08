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
package org.secuso.privacyfriendlyactivitytracker.fragments;

import android.app.DatePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;

import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.secuso.privacyfriendlyactivitytracker.Factory;
import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.adapters.ReportAdapter;
import org.secuso.privacyfriendlyactivitytracker.models.ActivityChart;
import org.secuso.privacyfriendlyactivitytracker.models.ActivityDayChart;
import org.secuso.privacyfriendlyactivitytracker.models.ActivitySummary;
import org.secuso.privacyfriendlyactivitytracker.models.TrackingStatus;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModePersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;
import org.secuso.privacyfriendlyactivitytracker.utils.ReportBuckets;
import org.secuso.privacyfriendlyactivitytracker.utils.StepDetectionServiceHelper;
import org.secuso.privacyfriendlyactivitytracker.utils.UnitHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Report-fragment for one specific day
 * <p/>
 * Activities that contain this fragment must implement the
 * {@link MonthlyReportFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link MonthlyReportFragment#newInstance} factory method to
 * create an instance of this fragment.
 *
 * @author Tobias Neidig
 * @version 20160606
 */

public class MonthlyReportFragment extends Fragment implements ReportAdapter.OnItemClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    public static String LOG_TAG = MonthlyReportFragment.class.getName();
    private static final long REPORT_REFRESH_DELAY_MS = 300L;

    private ReportAdapter mAdapter;
    private RecyclerView mRecyclerView;

    private OnFragmentInteractionListener mListener;
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver();

    private Calendar day;
    private ActivitySummary activitySummary;
    private ActivityChart activityChart;
    private final List<Object> reports = new ArrayList<>();
    private boolean generatingReports;
    private Map<Integer, WalkingMode> menuWalkingModes;
    private boolean bound;
    private boolean receiverRegistered;
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            generateReports(true);
        }
    };

    private AbstractStepDetectorService.StepDetectorBinder myBinder;
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            myBinder = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            myBinder = (AbstractStepDetectorService.StepDetectorBinder) service;
            generateReports(true);
        }
    };

    public MonthlyReportFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of MonthlyReportFragment.
     */
    public static MonthlyReportFragment newInstance() {
        MonthlyReportFragment fragment = new MonthlyReportFragment();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        day = Calendar.getInstance();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_daily_report, container, false);

        mRecyclerView = view.findViewById(R.id.my_recycler_view);

        // specify an adapter
        // using sample data.
        generateReports(false);
        mAdapter = new ReportAdapter(reports);
        mAdapter.setOnItemClickListener(this);
        mRecyclerView.setAdapter(mAdapter);

        // use a linear layout manager
        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(getActivity().getApplicationContext());
        mRecyclerView.setLayoutManager(mLayoutManager);

        // use this setting to improve performance if you know that changes
        // in content do not change the layout size of the RecyclerView
        mRecyclerView.setHasFixedSize(true);

        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
        if(day == null){
            day = Calendar.getInstance();
        } else if(!day.getTimeZone().equals(TimeZone.getDefault())) {
            day = Calendar.getInstance();
        }
        // Bind to stepDetector if the current month is shown
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();
        }
        registerReceivers();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceivers();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(day == null){
            day = Calendar.getInstance();
        } else if(!day.getTimeZone().equals(TimeZone.getDefault())) {
            day = Calendar.getInstance();
        }
        // Bind to stepDetector if the current month is shown
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();
        }
        registerReceivers();
    }

    @Override
    public void onDetach() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        unbindService();
        mListener = null;
        unregisterReceivers();
        super.onDetach();
    }

    @Override
    public void onPause() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        unbindService();
        unregisterReceivers();
        super.onPause();
    }

    @Override
    public void onStop() {
        unregisterReceivers();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        unbindService();
        unregisterReceivers();
        super.onDestroy();
    }

    private void bindService() {
        if (bound || getActivity() == null) {
            return;
        }
        Intent serviceIntent = new Intent(getContext(), Factory.getStepDetectorServiceClass(getContext()));
        bound = getActivity().getApplicationContext().bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        StepDetectionServiceHelper.startAllIfEnabled(true, getContext());
    }

    private void unbindService() {
        if (bound && getActivity() != null) {
            try {
                getActivity().getApplicationContext().unbindService(mServiceConnection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound.
            }
            bound = false;
        }
        myBinder = null;
    }

    private void registerReceivers(){
        if (receiverRegistered || getContext() == null) {
            return;
        }
        // subscribe to onStepsSaved and onStepsDetected broadcasts
        IntentFilter filterRefreshUpdate = new IntentFilter();
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_SAVED);
        filterRefreshUpdate.addAction(AbstractStepDetectorService.BROADCAST_ACTION_STEPS_DETECTED);
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_INSERTED);
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_UPDATED);
        filterRefreshUpdate.addAction(WalkingModePersistenceHelper.BROADCAST_ACTION_WALKING_MODE_CHANGED);
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(broadcastReceiver, filterRefreshUpdate);
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
        sharedPref.registerOnSharedPreferenceChangeListener(this);
        receiverRegistered = true;
    }

    private void unregisterReceivers(){
        if (!receiverRegistered || getContext() == null) {
            return;
        }
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(broadcastReceiver);
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
        sharedPref.unregisterOnSharedPreferenceChangeListener(this);
        receiverRegistered = false;
    }
    /**
     * @return is the day which is currently shown today?
     */
    private boolean isTodayShown() {
        if(day == null){
            return false;
        }
        Calendar start = getStartDay();
        Calendar end = getEndDay();
        return (start.before(day) || start.equals(day)) && end.after(day);
    }

    /**
     * The start day is midnight of the first day of the shown month.
     * @return The start day of shown interval
     */
    private Calendar getStartDay(){
        if(day == null){
            return Calendar.getInstance();
        }
        Calendar start = (Calendar) day.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        start.set(Calendar.MILLISECOND, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.HOUR_OF_DAY, 0);
        return start;
    }

    /**
     * The end day is midnight of first day of next month.
     * @return The end day of shown interval
     */
    private Calendar getEndDay(){
        Calendar end = getStartDay();
        end.add(Calendar.MONTH, 1);
        return end;
    }

    /**
     * Generates the report objects and adds them to the recycler view adapter.
     * The following reports will be generated:
     * * ActivitySummary
     * * ActivityChart
     * If one of these reports does not exist it will be created and added at the end of view.
     *
     * @param updated determines if the method is called because of an update of current steps.
     *                If set to true and another day than today is shown the call will be ignored.
     */
    private void generateReports(boolean updated) {
        if ((!this.isTodayShown() && updated) || isDetached() || getContext() == null
                || getActivity() == null || generatingReports) {
            // the period shown is not the current one or the fragment is detached
            return;
        }
        Log.i(LOG_TAG, "Generating reports");
        generatingReports = true;
        try {
            final Context context = getActivity().getApplicationContext();
            final Locale locale = UnitHelper.getLocale(context);
            final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            final double caloriesFactor = StepCount.getCaloriesFactor(context);
            final int dailyGoal = readDailyStepGoal(sharedPref);

            Calendar start = getStartDay();
            Calendar end = getEndDay();
            int dayCount = start.getActualMaximum(Calendar.DAY_OF_MONTH);
            List<StepCount> storedStepCounts = StepCountPersistenceHelper.getStepCountsForInterval(
                    start.getTimeInMillis(), end.getTimeInMillis() - 1, context);
            int unsavedSteps = myBinder != null ? myBinder.stepsSinceLastSave() : 0;
            WalkingMode activeMode = WalkingModePersistenceHelper.getActiveMode(context);
            List<ReportBuckets.Bucket> buckets = ReportBuckets.daily(start, dayCount,
                    storedStepCounts, unsavedSteps, activeMode, caloriesFactor);

            SimpleDateFormat formatDate = new SimpleDateFormat("dd.MM", locale);
            Map<String, Double> stepData = new LinkedHashMap<>();
            Map<String, Double> distanceData = new LinkedHashMap<>();
            Map<String, Double> caloriesData = new LinkedHashMap<>();
            stepData.put("", null);
            distanceData.put("", null);
            caloriesData.put("", null);

            int totalSteps = 0;
            double totalDistance = 0;
            int totalCalories = 0;
            for (ReportBuckets.Bucket bucket : buckets) {
                totalSteps += bucket.steps;
                totalDistance += bucket.distanceMeters;
                totalCalories += Math.round(bucket.caloriesKcal);
                String label = formatDate.format(new Date(bucket.startTime));
                stepData.put(label, (double) bucket.steps);
                distanceData.put(label, bucket.distanceMeters);
                caloriesData.put(label, (double) Math.round(bucket.caloriesKcal));
            }
            stepData.put(" ", null);
            distanceData.put(" ", null);
            caloriesData.put(" ", null);

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MMMM yy", locale);
            String title = simpleDateFormat.format(day.getTime());

            // create/update view models
            if (activitySummary == null) {
                activitySummary = new ActivitySummary(totalSteps, totalDistance, totalCalories, title);
                reports.add(activitySummary);
            }
            activitySummary.setSteps(totalSteps);
            activitySummary.setDistance(totalDistance);
            activitySummary.setCalories(totalCalories);
            activitySummary.setTitle(title);
            activitySummary.setHasSuccessor(new Date().after(getEndDay().getTime()));
            activitySummary.setHasPredecessor(
                    StepCountPersistenceHelper.getDateOfFirstEntry(context).before(day.getTime()));
            // The summary ring shows the progress of the whole displayed month.
            activitySummary.setGoal(dailyGoal * dayCount);

            if (activityChart == null) {
                activityChart = new ActivityChart(stepData, distanceData, caloriesData, title);
                activityChart.setDisplayedDataType(ActivityDayChart.DataType.STEPS);
                reports.add(activityChart);
            }
            activityChart.setSteps(stepData);
            activityChart.setDistance(distanceData);
            activityChart.setCalories(caloriesData);
            activityChart.setTitle(title);
            activityChart.setGoal(dailyGoal);

            if (reports.isEmpty() || !(reports.get(reports.size() - 1) instanceof TrackingStatus)) {
                reports.add(new TrackingStatus());
            }

            // notify ui
            if (mAdapter != null && mRecyclerView != null && getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!mRecyclerView.isComputingLayout()) {
                            mAdapter.notifyDataSetChanged();
                        } else {
                            Log.w(LOG_TAG, "Cannot inform adapter for changes - RecyclerView is computing layout.");
                        }
                    }
                });
            } else {
                Log.w(LOG_TAG, "Cannot inform adapter for changes.");
            }
        } finally {
            generatingReports = false;
        }
    }

    /**
     * Coalesces many step broadcasts into one refresh.
     */
    private void scheduleReportsRefresh() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        mRefreshHandler.postDelayed(mRefreshRunnable, REPORT_REFRESH_DELAY_MS);
    }

    private int readDailyStepGoal(SharedPreferences sharedPref) {
        String value = sharedPref.getString(getString(R.string.pref_daily_step_goal), "10000");
        try {
            int goal = Integer.parseInt(value);
            return goal > 0 ? goal : 10000;
        } catch (NumberFormatException e) {
            return 10000;
        }
    }

    @Override
    public void onActivityChartDataTypeClicked(ActivityDayChart.DataType newDataType) {
        Log.i(LOG_TAG, "Changing  displayed data type to " + newDataType.toString());
        if (this.activityChart == null) {
            return;
        }
        if (this.activityChart.getDisplayedDataType() == newDataType) {
            return;
        }
        this.activityChart.setDisplayedDataType(newDataType);
        if (this.mAdapter != null) {
            this.mAdapter.notifyItemChanged(this.reports.indexOf(this.activityChart));
        }
    }

    @Override
    public void setActivityChartDataTypeChecked(Menu menu) {
        if (this.activityChart == null) {
            return;
        }
        if (this.activityChart.getDisplayedDataType() == null) {
            menu.findItem(R.id.menu_steps).setChecked(true);
        }
        switch (this.activityChart.getDisplayedDataType()) {
            case DISTANCE:
                menu.findItem(R.id.menu_distance).setChecked(true);
                break;
            case CALORIES:
                menu.findItem(R.id.menu_calories).setChecked(true);
                break;
            case STEPS:
            default:
                menu.findItem(R.id.menu_steps).setChecked(true);
        }
    }

    @Override
    public void onPrevClicked() {
        this.day.add(Calendar.MONTH, -1);
        this.generateReports(false);
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();

        }
    }

    @Override
    public void onNextClicked() {
        this.day.add(Calendar.MONTH, 1);
        this.generateReports(false);
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();

        }
    }

    @Override
    public void onTitleClicked() {
        int year = this.day.get(Calendar.YEAR);
        int month = this.day.get(Calendar.MONTH);
        int day = this.day.get(Calendar.DAY_OF_MONTH);

        // Create a new instance of DatePickerDialog and return it
        DatePickerDialog dialog = new DatePickerDialog(getContext(), R.style.AppTheme_DatePickerDialog, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {
                MonthlyReportFragment.this.day.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                MonthlyReportFragment.this.day.set(Calendar.MONTH, monthOfYear);
                MonthlyReportFragment.this.day.set(Calendar.YEAR, year);
                MonthlyReportFragment.this.generateReports(false);
                if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
                    bindService();

                }
            }
        }, year, month, day);
        dialog.getDatePicker().setMaxDate(new Date().getTime()); // Max date is today
        dialog.getDatePicker().setMinDate(StepCountPersistenceHelper.getDateOfFirstEntry(getContext()).getTime());
        dialog.show();
    }

    @Override
    public void inflateWalkingModeMenu(Menu menu) {
        // Add the walking modes to option menu
        menu.clear();
        menuWalkingModes = new HashMap<>();
        List<WalkingMode> walkingModes = WalkingModePersistenceHelper.getAllItems(getContext());
        int i = 0;
        for (WalkingMode walkingMode : walkingModes) {
            int id = Menu.FIRST + (i++);
            menuWalkingModes.put(id, walkingMode);
            menu.add(0, id, Menu.NONE, walkingMode.getName()).setChecked(walkingMode.isActive());
        }
        menu.setGroupCheckable(0, true, true);
    }

    @Override
    public void onWalkingModeClicked(int id) {
        if (!menuWalkingModes.containsKey(id)) {
            return;
        }
        // update active walking mode
        WalkingMode walkingMode = menuWalkingModes.get(id);
        WalkingModePersistenceHelper.setActiveMode(walkingMode, getContext());
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if(key.equals(getString(R.string.pref_step_counter_enabled)) || key.equals(getString(R.string.pref_use_step_hardware))){
            if(!StepDetectionServiceHelper.isStepDetectionEnabled(getContext())){
                unbindService();
            }else if(this.isTodayShown()){
                bindService();
            }
        }
        if (key.equals(getString(R.string.pref_daily_step_goal))
                || key.equals(getString(R.string.pref_weight))
                || key.equals(getString(R.string.pref_unit_of_length))
                || key.equals(getString(R.string.pref_unit_of_energy))) {
            scheduleReportsRefresh();
        }
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     */
    public interface OnFragmentInteractionListener {
        // Currently doing nothing here.
    }

    public class BroadcastReceiver extends android.content.BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                Log.w(LOG_TAG, "Received intent which is null.");
                return;
            }
            switch (intent.getAction()) {
                case AbstractStepDetectorService.BROADCAST_ACTION_STEPS_DETECTED:
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_SAVED:
                case WalkingModePersistenceHelper.BROADCAST_ACTION_WALKING_MODE_CHANGED:
                    // Coalesce bursts of step broadcasts into at most one refresh per 300 ms.
                    scheduleReportsRefresh();
                    break;
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_INSERTED:
                case StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_UPDATED:
                    generateReports(false);
                    break;
                default:
            }
        }
    }
}
