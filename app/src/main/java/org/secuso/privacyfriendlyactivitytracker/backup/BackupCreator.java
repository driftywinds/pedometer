package org.secuso.privacyfriendlyactivitytracker.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.JsonWriter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.secuso.privacyfriendlyactivitytracker.PFAPedometerApplication;
import org.secuso.privacyfriendlyactivitytracker.persistence.StepCountDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.TrainingDbHelper;
import org.secuso.privacyfriendlyactivitytracker.persistence.WalkingModeDbHelper;
import org.secuso.privacyfriendlyactivitytracker.tutorial.PrefManager;
import org.secuso.privacyfriendlybackup.api.backup.DatabaseUtil;
import org.secuso.privacyfriendlybackup.api.backup.PreferenceUtil;
import org.secuso.privacyfriendlybackup.api.pfa.IBackupCreator;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;


public class BackupCreator implements IBackupCreator {
    private static final String TAG = "PFA BackupCreator";

    @Override
    public boolean writeBackup(@NonNull Context context, @NonNull OutputStream outputStream) {
        PFAPedometerApplication application =
                (PFAPedometerApplication) context.getApplicationContext();
        // Lock the application, so no changes can be made as long as this backup is created.
        // Depending on the size of the application this could take a bit.
        application.lock();

        Log.d(TAG, "createBackup() started");
        JsonWriter writer = new JsonWriter(
                new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
        writer.setIndent("");

        try {
            writer.beginObject();

            writeDatabase(writer, context, "database_stepCount", StepCountDbHelper.DATABASE_NAME);
            writeDatabase(writer, context, "database_trainings", TrainingDbHelper.DATABASE_NAME);
            writeDatabase(writer, context, "database_walkingMode", WalkingModeDbHelper.DATABASE_NAME);

            Log.d(TAG, "Writing preferences");
            writer.name("preferences");
            SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
            PreferenceUtil.writePreferences(writer, pref);

            writer.name("tutorial_preferences");
            pref = context.getSharedPreferences(PrefManager.PREF_NAME, 0);
            PreferenceUtil.writePreferences(writer, pref);

            writer.endObject();
            writer.flush();

            Log.d(TAG, "Backup created successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error while writing the backup.", e);
            return false;
        } finally {
            try {
                writer.close();
            } catch (IOException e) {
                Log.w(TAG, "Could not close the backup writer.", e);
            }
            application.release();
        }
    }

    private void writeDatabase(@NonNull JsonWriter writer, @NonNull Context context,
                               @NonNull String jsonName, @NonNull String databaseName)
            throws IOException {
        writer.name(jsonName);
        Log.d(TAG, "Writing " + databaseName);
        SupportSQLiteDatabase database = DatabaseUtil
                .getSupportSQLiteOpenHelper(context, databaseName)
                .getReadableDatabase();
        try {
            DatabaseUtil.writeDatabase(writer, database);
        } finally {
            database.close();
        }
    }
}
