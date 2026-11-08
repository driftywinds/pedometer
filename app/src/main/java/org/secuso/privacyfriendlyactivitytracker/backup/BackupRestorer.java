package org.secuso.privacyfriendlyactivitytracker.backup;


import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.JsonReader;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountPersistenceHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.TrainingDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModeDbHelper;
import org.secuso.privacyfriendlyactivitytracker.receivers.DataRestoredReceiver;
import org.secuso.privacyfriendlybackup.api.backup.DatabaseUtil;
import org.secuso.privacyfriendlybackup.api.backup.FileUtil;
import org.secuso.privacyfriendlybackup.api.pfa.IBackupRestorer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.secuso.privacyfriendlyactivitytracker.tutorial.PrefManager.PREF_NAME;

public class BackupRestorer implements IBackupRestorer {

    /**
     * Prefix of the temporary databases which are written while the backup is parsed.
     */
    private static final String TEMP_DATABASE_SUFFIX = "-import";
    /**
     * Suffix of the renamed live databases while they are replaced.
     */
    private static final String BACKUP_DATABASE_SUFFIX = ".importbak";
    /**
     * Suffix of the failed import while it is rolled back.
     */
    private static final String FAILED_IMPORT_SUFFIX = ".failedimport";
    private static final String LOG_TAG = BackupRestorer.class.getSimpleName();

    /**
     * Databases that were parsed successfully and wait to replace the live databases.
     */
    private final List<File[]> pendingDatabases = new ArrayList<>();
    /**
     * Pairs of {backup file, live database} that were replaced and can still be rolled back.
     */
    private final List<File[]> replacedDatabases = new ArrayList<>();
    /**
     * Preferences from the backup. They are only applied after the databases were replaced,
     * so that listeners cannot start services while the databases are being swapped.
     */
    private final Map<String, Object> pendingPreferences = new HashMap<>();
    private final Map<String, Object> pendingTutorialPreferences = new HashMap<>();

    private void readDatabase(@NonNull JsonReader reader, @NonNull Context context, String dbName) throws IOException {
        reader.beginObject();

        String n1 = reader.nextName();
        if (!n1.equals("version")) {
            throw new RuntimeException("Unknown value " + n1);
        }
        int version = reader.nextInt();

        String n2 = reader.nextName();
        if (!n2.equals("content")) {
            throw new RuntimeException("Unknown value " + n2);
        }

        // Write the database from the backup into a temporary file first. The live database
        // is only replaced once the complete backup was read without errors.
        String tempDatabaseName = dbName + TEMP_DATABASE_SUFFIX;
        DatabaseUtil.deleteRoomDatabase(context, tempDatabaseName);
        // Also remove a stale journal of a previous (failed) import.
        deleteSidecarFiles(context.getDatabasePath(tempDatabaseName));
        SupportSQLiteDatabase db = DatabaseUtil.getSupportSQLiteOpenHelper(context, tempDatabaseName, version).getWritableDatabase();
        try {
            db.beginTransaction();
            db.setVersion(version);

            DatabaseUtil.readDatabaseContent(reader, db);

            db.setTransactionSuccessful();
            db.endTransaction();
        } finally {
            db.close();
        }
        reader.endObject();

        pendingDatabases.add(new File[]{
                context.getDatabasePath(tempDatabaseName),
                context.getDatabasePath(dbName)
        });
    }

    /**
     * Replaces the live databases with the imported ones. The current databases are kept
     * as *.importbak files until every database was replaced, so a failed import can be
     * rolled back without losing the existing data.
     */
    private void replaceDatabases(@NonNull Context context) throws IOException {
        // Close the connections of this process first. On Linux an open file handle would keep
        // pointing to the renamed (old) file, so writes after the import would go to the
        // replaced database instead of the imported one.
        closeDatabaseReferences();
        for (File[] pair : pendingDatabases) {
            File tempDatabase = pair[0];
            File liveDatabase = pair[1];
            File backup = new File(liveDatabase.getPath() + BACKUP_DATABASE_SUFFIX);
            backup.delete();
            if (liveDatabase.exists() && !liveDatabase.renameTo(backup)) {
                throw new IOException("Could not move the current database " + liveDatabase);
            }
            // Register the pair before copying, so a failed copy can still be rolled back.
            replacedDatabases.add(new File[]{backup, liveDatabase});
            deleteSidecarFiles(liveDatabase);
            FileUtil.copyFile(tempDatabase, liveDatabase);
            tempDatabase.delete();
        }
        pendingDatabases.clear();
    }

    private void closeDatabaseReferences() {
        StepCountDbHelper.invalidateReference();
        StepCountPersistenceHelper.invalidateReference();
        TrainingDbHelper.invalidateReference();
        WalkingModeDbHelper.invalidateReference();
    }

    private void rollbackDatabases() {
        for (int i = replacedDatabases.size() - 1; i >= 0; i--) {
            File[] pair = replacedDatabases.get(i);
            File backup = pair[0];
            File liveDatabase = pair[1];
            if (!backup.exists()) {
                // Nothing to roll back to.
                continue;
            }
            // Move the failed import out of the way instead of deleting it first, so that the
            // data is never lost when the second rename fails as well.
            File failedImport = new File(liveDatabase.getPath() + FAILED_IMPORT_SUFFIX);
            //noinspection ResultOfMethodCallIgnored
            failedImport.delete();
            if (liveDatabase.exists() && !liveDatabase.renameTo(failedImport)) {
                Log.e(LOG_TAG, "Could not move the failed import aside: " + liveDatabase);
                continue;
            }
            if (backup.renameTo(liveDatabase)) {
                //noinspection ResultOfMethodCallIgnored
                failedImport.delete();
            } else {
                Log.e(LOG_TAG, "Could not restore the database after a failed import: " + liveDatabase);
                // Keep the failed import and the backup on disk so that the data can be
                // recovered manually instead of silently losing it.
                //noinspection ResultOfMethodCallIgnored
                failedImport.renameTo(liveDatabase);
            }
        }
        replacedDatabases.clear();
    }

    private void finishImport() {
        for (File[] pair : replacedDatabases) {
            //noinspection ResultOfMethodCallIgnored
            pair[0].delete();
        }
        replacedDatabases.clear();
    }

    private void cleanUpTempDatabases() {
        for (File[] pair : pendingDatabases) {
            //noinspection ResultOfMethodCallIgnored
            pair[0].delete();
        }
        pendingDatabases.clear();
    }

    private void deleteSidecarFiles(@NonNull File databaseFile) {
        //noinspection ResultOfMethodCallIgnored
        new File(databaseFile.getPath() + "-journal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(databaseFile.getPath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(databaseFile.getPath() + "-shm").delete();
    }

    private void readDefaultPreferences(@NonNull JsonReader reader, @NonNull Context context) throws IOException {
        reader.beginObject();

        while (reader.hasNext()) {
            String name = reader.nextName();

            switch (name) {
                case "org.secuso.privacyfriendlyactivitytracker.pref.step_counter_enabled":
                case "org.secuso.privacyfriendlyactivitytracker.pref.use_wake_lock":
                case "org.secuso.privacyfriendlyactivitytracker.pref.use_wake_lock_during_training":
                case "org.secuso.privacyfriendlyactivitytracker.pref.show_velocity":
                case "org.secuso.privacyfriendlyactivitytracker.pref.permanent_notification_show_steps":
                case "org.secuso.privacyfriendlyactivitytracker.pref.permanent_notification_show_distance":
                case "org.secuso.privacyfriendlyactivitytracker.pref.permanent_notification_show_calories":
                case "org.secuso.privacyfriendlyactivitytracker.pref.motivation_alert_enabled":
                case "org.secuso.privacyfriendlyactivitytracker.pref.use_step_hardware":
                case "org.secuso.privacyfriendlyactivitytracker.pref.walking_mode_learning_active":

                    pendingPreferences.put(name, reader.nextBoolean());
                    break;

                case "org.secuso.privacyfriendlyactivitytracker.pref.unit_of_length":
                case "org.secuso.privacyfriendlyactivitytracker.pref.unit_of_energy":
                case "org.secuso.privacyfriendlyactivitytracker.pref.accelerometer_threshold":
                case "org.secuso.privacyfriendlyactivitytracker.pref.accelerometer_step_threshold":
                case "org.secuso.privacyfriendlyactivitytracker.pref.daily_step_goal":
                case "org.secuso.privacyfriendlyactivitytracker.pref.weight":
                case "org.secuso.privacyfriendlyactivitytracker.pref.gender":
                case "org.secuso.privacyfriendlyactivitytracker.pref.motivation_alert_criterion":
                case "org.secuso.privacyfriendlyactivitytracker.pref.which_step_hardware":
                case "org.secuso.privacyfriendlyactivitytracker.pref.hw_background_counter_frequency":

                    pendingPreferences.put(name, reader.nextString());
                    break;

                case "org.secuso.privacyfriendlyactivitytracker.pref.motivation_alert_time":
                case "org.secuso.privacyfriendlyactivitytracker.pref.distance_measurement_start_timestamp":

                    pendingPreferences.put(name, reader.nextLong());
                    break;

                case "org.secuso.privacyfriendlyactivitytracker.pref.hw_steps_on_last_save":

                    // Stored as float by OnBootCompletedBroadcastReceiver.
                    pendingPreferences.put(name, (float) reader.nextDouble());
                    break;

                case "org.secuso.privacyfriendlyactivitytracker.pref.motivation_alert_texts":

                    reader.beginArray();
                    Set<String> alertTexts = new HashSet<>();
                    while(reader.hasNext()) {
                        alertTexts.add(reader.nextString());
                    }
                    reader.endArray();
                    pendingPreferences.put(name, alertTexts);
                    break;

                default:
                    if (name.startsWith("org.secuso.privacyfriendlyactivitytracker.pref.widget_data_set_")) {
                        // Widget data sets are stored per widget id as int.
                        pendingPreferences.put(name, reader.nextInt());
                    } else {
                        // Unknown preference (e.g. from a newer app version). Skip its value
                        // instead of failing the whole import.
                        reader.skipValue();
                    }
                    break;
            }
        }

        reader.endObject();
    }

    /**
     * Applies the preferences that were read from the backup.
     */
    private void applyPreferences(@NonNull Context context) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(context).edit();
        putAll(editor, pendingPreferences);
        editor.apply();
        pendingPreferences.clear();

        SharedPreferences.Editor tutorialEditor = context.getSharedPreferences(PREF_NAME, 0).edit();
        putAll(tutorialEditor, pendingTutorialPreferences);
        tutorialEditor.apply();
        pendingTutorialPreferences.clear();
    }

    private void putAll(@NonNull SharedPreferences.Editor editor, @NonNull Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else if (value instanceof Long) {
                editor.putLong(key, (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(key, (Float) value);
            } else if (value instanceof Integer) {
                editor.putInt(key, (Integer) value);
            } else if (value instanceof Set) {
                //noinspection unchecked
                editor.putStringSet(key, (Set<String>) value);
            }
        }
    }

    @Override
    public boolean restoreBackup(@NonNull Context context, @NonNull InputStream restoreData) {
        pendingDatabases.clear();
        replacedDatabases.clear();
        pendingPreferences.clear();
        pendingTutorialPreferences.clear();
        try {
            InputStreamReader isReader = new InputStreamReader(restoreData, StandardCharsets.UTF_8);
            JsonReader reader = new JsonReader(isReader);

            // START
            reader.beginObject();

            while (reader.hasNext()) {
                String type = reader.nextName();

                switch (type) {
                    case "database_stepCount":
                        readDatabase(reader, context, StepCountDbHelper.DATABASE_NAME);
                        break;
                    case "database_trainings":
                        readDatabase(reader, context, TrainingDbHelper.DATABASE_NAME);
                        break;
                    case "database_walkingMode":
                        readDatabase(reader, context, WalkingModeDbHelper.DATABASE_NAME);
                        break;
                    case "preferences":
                        readDefaultPreferences(reader, context);
                        break;
                    case "tutorial_preferences":
                        readTutorialPreferences(reader, context);
                        break;
                    default:
                        throw new RuntimeException("Can not parse type " + type);
                }

            }

            reader.endObject();

            // The complete backup was read successfully - replace the live databases now.
            replaceDatabases(context);
            applyPreferences(context);

            StepCountDbHelper.invalidateReference();
            StepCountPersistenceHelper.invalidateReference();
            TrainingDbHelper.invalidateReference();
            WalkingModeDbHelper.invalidateReference();

            finishImport();
            notifyDataRestored(context);

            return true;
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not restore the backup.", e);
            rollbackDatabases();
            closeDatabaseReferences();
            return false;
        } finally {
            cleanUpTempDatabases();
        }
    }

    /**
     * Tells the main process that the data was replaced. The restore runs in the
     * {@code :backup} process, so the database connections of the main process have to be
     * closed there and tracking has to be restarted.
     */
    private void notifyDataRestored(@NonNull Context context) {
        try {
            Intent intent = new Intent(context, DataRestoredReceiver.class);
            intent.setAction(DataRestoredReceiver.ACTION_DATA_RESTORED);
            context.sendBroadcast(intent);
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Could not notify the main process about the restored data.", e);
        }
    }

    private void readTutorialPreferences(@NonNull JsonReader reader, @NonNull Context context) throws IOException {
        reader.beginObject();

        while (reader.hasNext()) {
            String name = reader.nextName();

            if (name.equals("IsFirstTimeLaunch")) {
                pendingTutorialPreferences.put(name, reader.nextBoolean());
            } else {
                // Unknown preference (e.g. from a newer app version) - skip it.
                reader.skipValue();
            }
        }

        reader.endObject();
    }
}
