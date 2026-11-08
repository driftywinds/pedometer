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
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
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
import android.widget.EditText;

import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.jetbrains.annotations.NotNull;
import org.secuso.privacyfriendlyactivitytracker.Factory;
import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.adapters.ReportAdapter;
import org.secuso.privacyfriendlyactivitytracker.models.ActivityChartDataSet;
import org.secuso.privacyfriendlyactivitytracker.models.ActivityDayChart;
import org.secuso.privacyfriendlyactivitytracker.models.ActivitySummary;
import org.secuso.privacyfriendlyactivitytracker.models.TrackingStatus;
import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModePersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.services.AbstractStepDetectorService;
import org.secuso.privacyfriendlyactivitytracker.services.MovementSpeedService;
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
 * {@link DailyReportFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link DailyReportFragment#newInstance} factory method to
 * create an instance of this fragment.
 *
 * @author Tobias Neidig
 * @version 20160727
 */

public class DailyReportFragment extends Fragment implements ReportAdapter.OnItemClickListener, SharedPreferences.OnSharedPreferenceChangeListener {
    public static String LOG_TAG = DailyReportFragment.class.getName();
    private static final long REPORT_REFRESH_DELAY_MS = 300L;
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver();
    private ReportAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private OnFragmentInteractionListener mListener;
    private ActivitySummary activitySummary;
    private ActivityDayChart activityChart;
    private final List<Object> reports = new ArrayList<>();
    private Calendar day;
    private boolean generatingReports;
    private Map<Integer, WalkingMode> menuWalkingModes;
    private int menuCorrectStepId;
    private AbstractStepDetectorService.StepDetectorBinder myBinder;
    private boolean bound;
    private boolean receiverRegistered;
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            generateReports(true);
        }
    };
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
    private MovementSpeedService.MovementSpeedBinder movementSpeedBinder;
    private ServiceConnection mMovementSpeedServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceDisconnected(ComponentName name) {
            movementSpeedBinder = null;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            movementSpeedBinder = (MovementSpeedService.MovementSpeedBinder) service;
            generateReports(true);
        }
    };

    public DailyReportFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @return A new instance of DailyReportFragment.
     */
    public static DailyReportFragment newInstance() {
        DailyReportFragment fragment = new DailyReportFragment();
        Bundle args = new Bundle();
        // args.putString(ARG_PARAM1, param1);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        day = Calendar.getInstance();
        // Bind to stepDetector if today is shown
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_daily_report, container, false);

        mRecyclerView = view.findViewById(R.id.my_recycler_view);

        // Generate the reports
        generateReports(false);

        mAdapter = new ReportAdapter(reports);
        mAdapter.setOnItemClickListener(this);
        mRecyclerView.setAdapter(mAdapter);

        // use a linear layout manager
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(getActivity().getApplicationContext());
        mRecyclerView.setLayoutManager(layoutManager);

        // use this setting to improve performance if you know that changes
        // in content do not change the layout size of the RecyclerView
        mRecyclerView.setHasFixedSize(true);

        return view;
    }

    @Override
    public void onAttach(@NotNull Context context) {
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
        if(isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(context)){
            bindService();
        }
        bindMovementSpeedService();
    }

    @Override
    public void onStart() {
        super.onStart();
        registerReceivers();
    }

    @Override
    public void onResume(){
        super.onResume();
        if(day == null){
            day = Calendar.getInstance();
        } else if(!day.getTimeZone().equals(TimeZone.getDefault())) {
            day = Calendar.getInstance();
        }
        if(isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())){
            bindService();
        }
        registerReceivers();
        bindMovementSpeedService();
    }

    @Override
    public void onPause(){
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        unbindService();
        unbindMovementSpeedService();
        unregisterReceivers();
        super.onPause();
    }

    @Override
    public void onStop() {
        unregisterReceivers();
        super.onStop();
    }

    @Override
    public void onDetach() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        unbindService();
        unbindMovementSpeedService();
        unregisterReceivers();
        mListener = null;
        super.onDetach();
    }

    private void registerReceivers(){
        if (receiverRegistered || getContext() == null) {
            return;
        }
        // subscribe to onStepsSaved and onStepsDetected broadcasts and onSpeedChanged
        IntentFilter filterRefreshUpdate = new IntentFilter();
        filterRefreshUpdate.addAction(StepCountPersistenceHelper.BROADCAST_ACTION_STEPS_SAVED);
        filterRefreshUpdate.addAction(AbstractStepDetectorService.BROADCAST_ACTION_STEPS_DETECTED);
        filterRefreshUpdate.addAction(MovementSpeedService.BROADCAST_ACTION_SPEED_CHANGED);
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

    private void bindService(){
        if(myBinder == null && getActivity() != null) {
            Intent serviceIntent = new Intent(getContext(), Factory.getStepDetectorServiceClass(getContext()));
            bound = getActivity().getApplicationContext().bindService(serviceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
        StepDetectionServiceHelper.startAllIfEnabled(true, getContext());
    }

    private void unbindService(){
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

    private void bindMovementSpeedService(){
        if (getContext() == null || getActivity() == null) {
            return;
        }
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean isVelocityEnabled = sharedPref.getBoolean(getString(R.string.pref_show_velocity), false);
        boolean hasLocationPermission =
                androidx.core.content.ContextCompat.checkSelfPermission(getContext(),
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        || androidx.core.content.ContextCompat.checkSelfPermission(getContext(),
                        android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if(movementSpeedBinder == null && isVelocityEnabled && hasLocationPermission){
            Intent serviceIntent = new Intent(getContext(), MovementSpeedService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getActivity().startForegroundService(serviceIntent);
            } else {
                getActivity().startService(serviceIntent);
            }
            getActivity().getApplicationContext().bindService(serviceIntent, mMovementSpeedServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private void unbindMovementSpeedService(){
        if (getActivity() == null) {
            return;
        }
        if(movementSpeedBinder != null){
            try {
                getActivity().getApplicationContext().unbindService(mMovementSpeedServiceConnection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound.
            }
            movementSpeedBinder = null;
        }
        Intent serviceIntent = new Intent(getContext(), MovementSpeedService.class);
        getActivity().getApplicationContext().stopService(serviceIntent);
    }

    /**
     * @return is the day which is currently shown today?
     */
    private boolean isTodayShown() {
        return (Calendar.getInstance().get(Calendar.YEAR) == day.get(Calendar.YEAR) &&
                Calendar.getInstance().get(Calendar.MONTH) == day.get(Calendar.MONTH) &&
                Calendar.getInstance().get(Calendar.DAY_OF_MONTH) == day.get(Calendar.DAY_OF_MONTH));
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
        Log.i(LOG_TAG, "Generating reports");
        if ((!this.isTodayShown() && updated) || isDetached() || getContext() == null || generatingReports) {
            // the day shown is not today or is detached
            return;
        }
        if (getActivity() == null) {
            return;
        }
        generatingReports = true;
        try {
            final Context context = getActivity().getApplicationContext();
            final Locale locale = UnitHelper.getLocale(context);
            final SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(context);
            final double caloriesFactor = StepCount.getCaloriesFactor(context);
            final int dailyGoal = readDailyStepGoal(sharedPref);

            // One query for the whole day instead of one per hour. The entries are distributed
            // in memory, which keeps a refresh cheap enough to run on the UI thread.
            Calendar start = ReportBuckets.startOfDay(day);
            Calendar end = (Calendar) start.clone();
            end.add(Calendar.DAY_OF_MONTH, 1);
            List<StepCount> storedStepCounts = StepCountPersistenceHelper.getStepCountsForInterval(
                    start.getTimeInMillis(), end.getTimeInMillis() - 1, context);
            int unsavedSteps = myBinder != null ? myBinder.stepsSinceLastSave() : 0;
            WalkingMode activeMode = WalkingModePersistenceHelper.getActiveMode(context);
            List<ReportBuckets.Bucket> buckets = ReportBuckets.hourly(day, storedStepCounts,
                    unsavedSteps, activeMode, caloriesFactor);

            SimpleDateFormat formatHourMinute = new SimpleDateFormat("HH:mm", locale);
            Map<String, ActivityChartDataSet> stepData = new LinkedHashMap<>();
            Map<String, ActivityChartDataSet> distanceData = new LinkedHashMap<>();
            Map<String, ActivityChartDataSet> caloriesData = new LinkedHashMap<>();

            int stepCount = 0;
            double distance = 0;
            int calories = 0;
            long now = System.currentTimeMillis();
            for (ReportBuckets.Bucket bucket : buckets) {
                stepCount += bucket.steps;
                distance += bucket.distanceMeters;
                calories += Math.round(bucket.caloriesKcal);
                String label = formatHourMinute.format(new Date(bucket.endTime));
                if (bucket.startTime > now) {
                    // Hours in the future are not drawn.
                    stepData.put(label, null);
                    distanceData.put(label, null);
                    caloriesData.put(label, null);
                } else {
                    StepCount representative = bucketToStepCount(bucket);
                    stepData.put(label, new ActivityChartDataSet(stepCount, representative));
                    distanceData.put(label, new ActivityChartDataSet(distance, representative));
                    caloriesData.put(label, new ActivityChartDataSet(calories, representative));
                }
            }

            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("dd. MMMM", locale);
            String title = simpleDateFormat.format(day.getTime());

            // create/update view models
            if (activitySummary == null) {
                activitySummary = new ActivitySummary(stepCount, distance, calories, title);
                reports.add(activitySummary);
            }
            activitySummary.setSteps(stepCount);
            activitySummary.setDistance(distance);
            activitySummary.setCalories(calories);
            activitySummary.setTitle(title);
            activitySummary.setHasSuccessor(!this.isTodayShown());
            activitySummary.setHasPredecessor(
                    StepCountPersistenceHelper.getDateOfFirstEntry(context).before(day.getTime()));
            boolean isVelocityEnabled = sharedPref.getBoolean(getString(R.string.pref_show_velocity), false);
            if (movementSpeedBinder != null && isVelocityEnabled && this.isTodayShown()) {
                activitySummary.setCurrentSpeed(movementSpeedBinder.getSpeed());
            } else {
                activitySummary.setCurrentSpeed(null);
            }
            activitySummary.setGoal(dailyGoal);

            if (activityChart == null) {
                activityChart = new ActivityDayChart(stepData, distanceData, caloriesData, title);
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
                        if(!mRecyclerView.isComputingLayout()) {
                            mAdapter.notifyDataSetChanged();
                        }else{
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
     * Coalesces many step broadcasts into one refresh. Without this the report was rebuilt
     * for every detected step (several times per second while walking).
     */
    private void scheduleReportsRefresh() {
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        mRefreshHandler.postDelayed(mRefreshRunnable, REPORT_REFRESH_DELAY_MS);
    }

    private StepCount bucketToStepCount(ReportBuckets.Bucket bucket) {
        StepCount stepCount = new StepCount();
        stepCount.setStartTime(bucket.startTime);
        stepCount.setEndTime(bucket.endTime);
        stepCount.setStepCount(bucket.steps);
        stepCount.setWalkingMode(bucket.walkingMode);
        return stepCount;
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
        this.day.add(Calendar.DAY_OF_MONTH, -1);
        this.generateReports(false);
        if (isTodayShown() && StepDetectionServiceHelper.isStepDetectionEnabled(getContext())) {
            bindService();
        }
    }

    @Override
    public void onNextClicked() {
        this.day.add(Calendar.DAY_OF_MONTH, 1);
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
                DailyReportFragment.this.day.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                DailyReportFragment.this.day.set(Calendar.MONTH, monthOfYear);
                DailyReportFragment.this.day.set(Calendar.YEAR, year);
                DailyReportFragment.this.generateReports(false);
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
        menu.add(1, Menu.FIRST + i, Menu.NONE, getString(R.string.correct_steps)).setCheckable(false);
        menuCorrectStepId = Menu.FIRST + i;
        menu.setGroupCheckable(0, true, true);
    }

    @Override
    public void onWalkingModeClicked(int id) {
        if (menuWalkingModes.containsKey(id)) {
            // update active walking mode
            WalkingMode walkingMode = menuWalkingModes.get(id);
            WalkingModePersistenceHelper.setActiveMode(walkingMode, getContext());
        }else if(id == menuCorrectStepId){
            AlertDialog.Builder alert = new MaterialAlertDialogBuilder(this.getContext());
            LayoutInflater inflater = getActivity().getLayoutInflater();
            final View dialogLayout = inflater.inflate(R.layout.dialog_correct_steps, null);
            final EditText edittext = dialogLayout.findViewById(R.id.steps);
            edittext.setText(String.valueOf(getStepCountInclNonSavedSteps(day)));
            alert.setMessage(getString(R.string.correct_steps_dialog_message));
            alert.setTitle(getString(R.string.correct_steps_dialog_title));
            alert.setView(dialogLayout);
            alert.setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                /* Nothing to do here, we will set an on click listener later
                That allows us to handle the dismiss */
                }
            });
            alert.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {/* nothing to do here */}
            });
            final AlertDialog alertDialog = alert.create();
            alertDialog.show();
            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        int steps_new = Integer.parseInt(edittext.getText().toString());
                        int steps_saved = getStepCountInclNonSavedSteps(day);
                        int diff = steps_new - steps_saved;
                        StepCount stepCount = StepCountPersistenceHelper.getLastStepCountEntryForDay(day, getContext());
                        if(stepCount == null){
                            stepCount = new StepCount();
                            stepCount.setStartTime(day.getTime().getTime());
                            stepCount.setEndTime(day.getTime().getTime());
                            stepCount.setStepCount(diff);
                            stepCount.setWalkingMode(WalkingModePersistenceHelper.getActiveMode(getContext()));
                            StepCountPersistenceHelper.storeStepCount(stepCount, getContext());
                        }else {
                            stepCount.setStepCount(stepCount.getStepCount() + diff);
                            StepCountPersistenceHelper.updateStepCount(stepCount, getContext());
                        }
                        generateReports(false);
                        alertDialog.dismiss();
                    }catch (NumberFormatException e){
                        alertDialog.dismiss();
                    }
                }
            });
        }
    }

    private int getStepCountInclNonSavedSteps(Calendar day){
        int steps = StepCountPersistenceHelper.getStepCountForDay(day, getContext());
        if (this.isTodayShown() && myBinder != null) {
            // Today is shown. Add the steps which are not in database.
            steps += myBinder.stepsSinceLastSave();
        }
        return steps;
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
                || key.equals(getString(R.string.pref_show_velocity))
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
                case MovementSpeedService.BROADCAST_ACTION_SPEED_CHANGED:
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
