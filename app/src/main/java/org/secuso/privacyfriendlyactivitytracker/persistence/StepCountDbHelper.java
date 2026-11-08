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
package org.secuso.privacyfriendlyactivitytracker.persistence;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

import org.secuso.privacyfriendlyactivitytracker.models.StepCount;
import org.secuso.privacyfriendlyactivitytracker.models.WalkingMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Database helper class for storing steps
 * The database stores for each entry a timestamp and the number of steps since last entry.
 *
 * @author Tobias Neidig
 * @version 20160630
 */

public class StepCountDbHelper extends SQLiteOpenHelper {
    // If you change the database schema, you must increment the database version.
    public static final int DATABASE_VERSION = 3;

    public static final String DATABASE_NAME = "StepCount.db";

    public static final String TABLE_NAME = "stepcount";

    public static final String KEY_ID = "_id";
    public static final String KEY_STEP_COUNT = "stepcount";
    public static final String KEY_WALKING_MODE = "walking_mode";
    public static final String KEY_TIMESTAMP = "timestamp";

    private static final String INTEGER_TYPE = " INTEGER";
    private static final String COMMA_SEP = ",";

    private static final String INDEX_TIMESTAMP_NAME = "index_stepcount_timestamp";
    private static final String SQL_CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS " + INDEX_TIMESTAMP_NAME +
                    " ON " + TABLE_NAME + " (" + KEY_TIMESTAMP + ")";

    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + TABLE_NAME + " (" +
                    KEY_ID + " INTEGER PRIMARY KEY," +
                    KEY_STEP_COUNT + INTEGER_TYPE + COMMA_SEP +
                    KEY_WALKING_MODE + INTEGER_TYPE + COMMA_SEP +
                    KEY_TIMESTAMP + INTEGER_TYPE +
            " )";

    private static SQLiteDatabase db;

    private final Context context;

    /**
     * Returns the shared database instance of this process.
     * <p>
     * All helper instances share the same connection so that there is exactly one
     * connection per database file. {@link #invalidateReference()} closes it, which is
     * required before the file is replaced by a restored backup.
     *
     * @param instance Instance of stepCountDbHelper to fetch new db-instance if necessary
     * @return static database instance
     */
    private static synchronized SQLiteDatabase getDatabase(StepCountDbHelper instance) {
        if (db == null || !db.isOpen()) {
            db = instance.getWritableDatabase();
        }
        return db;
    }

    /**
     * Closes and forgets the shared connection. Required after the database file was
     * replaced (e.g. by an imported backup) so that the next access opens the new file.
     */
    public static synchronized void invalidateReference() {
        if (db != null) {
            try {
                db.close();
            } catch (RuntimeException ignored) {
                // The connection is already gone - nothing to do.
            }
            db = null;
        }
    }

    public StepCountDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
    }

    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_ENTRIES);
        db.execSQL(SQL_CREATE_INDEX);
    }

    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 3) {
            // Version 3 only adds an index on the timestamp column. Creating it here makes
            // interval queries independent of the table size.
            db.execSQL(SQL_CREATE_INDEX);
        }
    }

    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onUpgrade(db, oldVersion, newVersion);
    }

    /**
     * Stores the given StepCount in database. EndTime will be used for KEY_TIMESTAMP.
     * @param stepCount the StepCount to save.
     */
    public void addStepCount(StepCount stepCount) {
        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(KEY_STEP_COUNT, stepCount.getStepCount());
        values.put(KEY_WALKING_MODE, (stepCount.getWalkingMode() != null) ? stepCount.getWalkingMode().getId() : 1);
        values.put(KEY_TIMESTAMP, stepCount.getEndTime());

        // Insert the new row, returning the primary key value of the new row
        getDatabase(this).insert(
                TABLE_NAME,
                null,
                values);
    }

    /**
     * Return all StepCounts
     * @return all StepCount-Entries
     */
    public List<StepCount> getAllStepCounts() {
        return getStepCountsForInterval(0, Long.MAX_VALUE);
    }

    /**
     * Returns the stepCount models for the steps walked in the given interval.
     *
     * @param start_time The start time in users timezone
     * @param end_time   The end time in users timezone
     * @return The StepCount-Models between start and end time
     */
    public List<StepCount> getStepCountsForInterval(long start_time, long end_time) {
        Cursor c = getDatabase(this).query(TABLE_NAME,
                new String[]{
                        KEY_ID,
                        KEY_STEP_COUNT,
                        KEY_TIMESTAMP,
                        KEY_WALKING_MODE
                },
                KEY_TIMESTAMP + " >= ? AND " + KEY_TIMESTAMP + " <= ?", new String[]{String.valueOf(start_time),
                        String.valueOf(end_time)}, null, null, KEY_TIMESTAMP + " ASC");
        WalkingModeDbHelper walkingModeDbHelper = new WalkingModeDbHelper(context);
        // Resolving the walking mode per row would issue one query per row. Load all modes once
        // instead - there are only a handful of them.
        Map<Long, WalkingMode> walkingModes = new HashMap<>();
        for (WalkingMode mode : walkingModeDbHelper.getAllWalkingModes(true)) {
            walkingModes.put(mode.getId(), mode);
        }
        List<StepCount> steps = new ArrayList<>();
        long start = start_time;
        try {
            while (c.moveToNext()) {
                StepCount s = new StepCount();
                s.setId(c.getLong(c.getColumnIndexOrThrow(KEY_ID)));
                s.setStartTime(start);
                s.setEndTime(c.getLong(c.getColumnIndexOrThrow(KEY_TIMESTAMP)));
                s.setStepCount(c.getInt(c.getColumnIndexOrThrow(KEY_STEP_COUNT)));
                long walkingModeId = c.getLong(c.getColumnIndexOrThrow(KEY_WALKING_MODE));
                WalkingMode mode = walkingModes.get(walkingModeId);
                if (mode == null) {
                    mode = walkingModeDbHelper.getWalkingMode((int) walkingModeId);
                }
                s.setWalkingMode(mode);
                steps.add(s);
                start = s.getEndTime();
            }
        } finally {
            c.close();
        }
        return steps;
    }

    public StepCount getLatestStepCount() {
        return getStepCount(KEY_TIMESTAMP + " DESC");
    }

    public StepCount getFirstStepCount() {
        return getStepCount(KEY_TIMESTAMP + " ASC");
    }

    private StepCount getStepCount(String orderBy) {
        Cursor c = getDatabase(this).query(TABLE_NAME,
                new String[]{
                        KEY_ID,
                        KEY_STEP_COUNT,
                        KEY_TIMESTAMP,
                        KEY_WALKING_MODE
                },
                null, null, null, null, orderBy, "1");
        WalkingModeDbHelper walkingModeDbHelper = new WalkingModeDbHelper(context);
        StepCount s = null;
        try {
            if (c.moveToNext()) {
                s = new StepCount();
                s.setId(c.getLong(c.getColumnIndexOrThrow(KEY_ID)));
                s.setEndTime(c.getLong(c.getColumnIndexOrThrow(KEY_TIMESTAMP)));
                s.setStepCount(c.getInt(c.getColumnIndexOrThrow(KEY_STEP_COUNT)));
                s.setWalkingMode(walkingModeDbHelper.getWalkingMode(c.getInt(c.getColumnIndexOrThrow(KEY_WALKING_MODE))));
            }
        } finally {
            c.close();
        }
        return s;
    }

    /**
     * Updates the given step count in database.
     * <p>
     * The row is identified by its primary key when available. Older call sites that only
     * know the end timestamp fall back to the most recent row with that timestamp, so that
     * a duplicate timestamp can never update more than one row.
     *
     * @param stepCount The step count to save
     */
    public void updateStepCount(StepCount stepCount) {
        // Create a new map of values, where column names are the keys
        ContentValues values = new ContentValues();
        values.put(KEY_STEP_COUNT, stepCount.getStepCount());
        values.put(KEY_WALKING_MODE, (stepCount.getWalkingMode() != null) ? stepCount.getWalkingMode().getId() : 1);
        values.put(KEY_TIMESTAMP, stepCount.getEndTime());

        if (stepCount.getId() > 0) {
            getDatabase(this).update(
                    TABLE_NAME,
                    values,
                    KEY_ID + " = ?",
                    new String[]{String.valueOf(stepCount.getId())}
            );
        } else {
            getDatabase(this).update(
                    TABLE_NAME,
                    values,
                    KEY_ID + " = (SELECT " + KEY_ID + " FROM " + TABLE_NAME +
                            " WHERE " + KEY_TIMESTAMP + " = ? ORDER BY " + KEY_ID + " DESC LIMIT 1)",
                    new String[]{String.valueOf(stepCount.getEndTime())}
            );
        }
    }

    /**
     * Deletes the given StepCount from database
     * @param stepCount The StepCount to delete.
     */
    public void deleteStepCount(StepCount stepCount) {
        if (stepCount.getId() > 0) {
            getDatabase(this).delete(TABLE_NAME, KEY_ID + " = ?",
                    new String[]{String.valueOf(stepCount.getId())});
            return;
        }
        getDatabase(this).delete(TABLE_NAME, KEY_TIMESTAMP + " = ?",
                new String[]{String.valueOf(stepCount.getEndTime())});
    }

    /**
     * Deletes all StepCounts from database
     */
    public void deleteAllStepCounts() {
        getDatabase(this).execSQL("delete from " + TABLE_NAME);
    }

    /**
     * @deprecated This class is deprecated due to structural updates to match pfa sample app.
     *             Please use {@link StepCountDbHelper} instead.
     */
    public static abstract class StepCountEntry implements BaseColumns {
        public static final String TABLE_NAME = StepCountDbHelper.TABLE_NAME;
        public static final String KEY_STEP_COUNT = StepCountDbHelper.KEY_STEP_COUNT;
        public static final String KEY_WALKING_MODE = StepCountDbHelper.KEY_WALKING_MODE;
        public static final String KEY_TIMESTAMP = StepCountDbHelper.KEY_TIMESTAMP;
    }


}
