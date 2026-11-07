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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.utils.PeriodicBackupManager;

/**
 * Lets the user select the folder, interval and retention of the periodic backup.
 */
public class PeriodicBackupActivity extends AppCompatActivity {

    private static final String TAG = PeriodicBackupActivity.class.getSimpleName();
    private static final int REQUEST_TREE = 41;

    private TextView locationSummary;
    private TextView nextBackupSummary;
    private RadioGroup intervalGroup;
    private Spinner keepSpinner;
    private MaterialSwitch enableSwitch;

    private boolean binding;
    private boolean pendingEnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_periodic_backup);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.periodic_backup_title);
        }

        locationSummary = findViewById(R.id.locationSummary);
        nextBackupSummary = findViewById(R.id.nextBackupSummary);
        intervalGroup = findViewById(R.id.intervalGroup);
        keepSpinner = findViewById(R.id.keepSpinner);
        enableSwitch = findViewById(R.id.enableSwitch);

        bindViews();

        findViewById(R.id.chooseLocationButton).setOnClickListener(v -> pickFolder());
        findViewById(R.id.backupNowButton).setOnClickListener(v -> backupNow());

        intervalGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding) {
                return;
            }
            PeriodicBackupManager.setInterval(this, intervalForId(checkedId));
            PeriodicBackupManager.schedule(this);
            updateNextBackup();
        });

        keepSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!binding) {
                    PeriodicBackupManager.setKeepCount(PeriodicBackupActivity.this, position + 1);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) {
                return;
            }
            if (isChecked && PeriodicBackupManager.getTreeUri(this) == null) {
                binding = true;
                enableSwitch.setChecked(false);
                binding = false;
                pendingEnable = true;
                pickFolder();
                return;
            }
            pendingEnable = false;
            PeriodicBackupManager.setEnabled(this, isChecked);
            updateNextBackup();
        });

        if (PeriodicBackupManager.getTreeUri(this) == null) {
            // First time setup: ask for the folder right away.
            pickFolder();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PeriodicBackupManager.isEnabled(this)) {
            PeriodicBackupManager.schedule(this);
        }
        updateLocation();
        updateNextBackup();
    }

    private void bindViews() {
        binding = true;

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.periodic_backup_keep_values, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        keepSpinner.setAdapter(adapter);
        keepSpinner.setSelection(Math.max(0, PeriodicBackupManager.getKeepCount(this) - 1));

        intervalGroup.check(idForInterval(PeriodicBackupManager.getInterval(this)));
        enableSwitch.setChecked(PeriodicBackupManager.isEnabled(this));

        binding = false;
    }

    private void updateLocation() {
        if (locationSummary == null) {
            return;
        }
        String name = PeriodicBackupManager.getLocationName(this);
        if (name != null) {
            locationSummary.setText(name);
            locationSummary.setTextColor(getColorCompat(R.color.text_primary));
        } else {
            locationSummary.setText(R.string.periodic_backup_location_none);
            locationSummary.setTextColor(getColorCompat(R.color.text_secondary));
        }
    }

    private void updateNextBackup() {
        if (nextBackupSummary == null) {
            return;
        }
        if (!PeriodicBackupManager.isEnabled(this)) {
            nextBackupSummary.setText(R.string.periodic_backup_summary_off);
            return;
        }
        long due = PeriodicBackupManager.getNextDue(this);
        if (due <= 0) {
            nextBackupSummary.setText(R.string.periodic_backup_summary_off);
            return;
        }
        nextBackupSummary.setText(getString(R.string.periodic_backup_next_run,
                PeriodicBackupManager.formatDue(this, due)));
    }

    private int getColorCompat(int colorRes) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return getResources().getColor(colorRes, getTheme());
        }
        return getResources().getColor(colorRes);
    }

    private static String intervalForId(int checkedId) {
        if (checkedId == R.id.intervalEveryOtherDay) {
            return PeriodicBackupManager.INTERVAL_EVERY_OTHER_DAY;
        } else if (checkedId == R.id.intervalWeekly) {
            return PeriodicBackupManager.INTERVAL_WEEKLY;
        } else if (checkedId == R.id.intervalMonthly) {
            return PeriodicBackupManager.INTERVAL_MONTHLY;
        }
        return PeriodicBackupManager.INTERVAL_DAILY;
    }

    private static int idForInterval(String interval) {
        switch (interval) {
            case PeriodicBackupManager.INTERVAL_EVERY_OTHER_DAY:
                return R.id.intervalEveryOtherDay;
            case PeriodicBackupManager.INTERVAL_WEEKLY:
                return R.id.intervalWeekly;
            case PeriodicBackupManager.INTERVAL_MONTHLY:
                return R.id.intervalMonthly;
            case PeriodicBackupManager.INTERVAL_DAILY:
            default:
                return R.id.intervalDaily;
        }
    }

    private void pickFolder() {
        if (!PeriodicBackupManager.isSupported()) {
            Toast.makeText(this, R.string.periodic_backup_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_TREE);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "No document tree picker available.", e);
            Toast.makeText(this, R.string.periodic_backup_error, Toast.LENGTH_LONG).show();
        }
    }

    private void backupNow() {
        if (PeriodicBackupManager.getTreeUri(this) == null) {
            Toast.makeText(this, R.string.periodic_backup_no_location, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, R.string.periodic_backup_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean success = PeriodicBackupManager.performBackup(getApplicationContext());
            runOnUiThread(() -> Toast.makeText(PeriodicBackupActivity.this,
                    success ? R.string.export_backup_success : R.string.export_backup_error,
                    Toast.LENGTH_LONG).show());
        }, "pedometer-periodic-backup-now").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(TAG, "Could not persist the folder permission.", e);
            }
            PeriodicBackupManager.setTreeUri(this, treeUri.toString());
            updateLocation();
            if (pendingEnable) {
                pendingEnable = false;
                binding = true;
                enableSwitch.setChecked(true);
                binding = false;
                PeriodicBackupManager.setEnabled(this, true);
            }
        } else {
            pendingEnable = false;
        }
        updateNextBackup();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
