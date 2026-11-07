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
package org.secuso.privacyfriendlyactivitytracker.utils;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.util.Log;

import org.secuso.privacyfriendlyactivitytracker.R;
import org.secuso.privacyfriendlyactivitytracker.backup.BackupCreator;
import org.secuso.privacyfriendlyactivitytracker.receivers.PeriodicBackupReceiver;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stores the periodic backup configuration and performs the scheduled backups.
 *
 * Backups run at 06:00 local time on the configured interval. When the device was in deep sleep
 * the alarm may be delayed; the application also checks on start whether a backup is overdue.
 */
public final class PeriodicBackupManager {

    private static final String TAG = PeriodicBackupManager.class.getSimpleName();

    public static final String ACTION_PERIODIC_BACKUP =
            "org.secuso.privacyfriendlyactivitytracker.action.PERIODIC_BACKUP";

    public static final String PREF_ENABLED = "pref_periodic_backup_enabled";
    public static final String PREF_TREE_URI = "pref_periodic_backup_tree_uri";
    public static final String PREF_INTERVAL = "pref_periodic_backup_interval";
    public static final String PREF_KEEP = "pref_periodic_backup_keep";
    public static final String PREF_NEXT_DUE = "pref_periodic_backup_next_due";
    public static final String PREF_LAST_RUN = "pref_periodic_backup_last_run";

    public static final String INTERVAL_DAILY = "daily";
    public static final String INTERVAL_EVERY_OTHER_DAY = "every_other_day";
    public static final String INTERVAL_WEEKLY = "weekly";
    public static final String INTERVAL_MONTHLY = "monthly";

    private static final String BACKUP_FILE_PREFIX = "pedometer-backup-";
    private static final int DEFAULT_KEEP = 3;
    private static final int SIX_AM_HOUR = 6;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private PeriodicBackupManager() {
    }

    private static SharedPreferences preferences(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /** Storage Access Framework based backups need at least Android 5.0. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            if (getNextDue(context) <= 0) {
                setNextDue(context, computeNextDue(System.currentTimeMillis()));
            }
            schedule(context);
        } else {
            cancelAlarm(context);
        }
    }

    public static String getInterval(Context context) {
        return preferences(context).getString(PREF_INTERVAL, INTERVAL_DAILY);
    }

    public static void setInterval(Context context, String interval) {
        preferences(context).edit().putString(PREF_INTERVAL, interval).apply();
    }

    public static int getKeepCount(Context context) {
        int keep = preferences(context).getInt(PREF_KEEP, DEFAULT_KEEP);
        return Math.max(1, Math.min(5, keep));
    }

    public static void setKeepCount(Context context, int keep) {
        preferences(context).edit().putInt(PREF_KEEP, Math.max(1, Math.min(5, keep))).apply();
    }

    public static String getTreeUri(Context context) {
        return preferences(context).getString(PREF_TREE_URI, null);
    }

    public static void setTreeUri(Context context, String treeUri) {
        preferences(context).edit().putString(PREF_TREE_URI, treeUri).apply();
    }

    public static long getNextDue(Context context) {
        return preferences(context).getLong(PREF_NEXT_DUE, 0L);
    }

    public static void setNextDue(Context context, long due) {
        preferences(context).edit().putLong(PREF_NEXT_DUE, due).apply();
    }

    public static long getLastRun(Context context) {
        return preferences(context).getLong(PREF_LAST_RUN, 0L);
    }

    private static void setLastRun(Context context, long time) {
        preferences(context).edit().putLong(PREF_LAST_RUN, time).apply();
    }

    @SuppressLint("NewApi")
    /** Human readable folder name of the selected tree, or null when none is selected. */
    public static String getLocationName(Context context) {
        String tree = getTreeUri(context);
        if (tree == null || !isSupported()) {
            return null;
        }
        Uri treeUri = Uri.parse(tree);
        try {
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            try (Cursor cursor = context.getContentResolver().query(
                    documentUri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the backup folder name.", e);
        }
        // Fall back to the document id, e.g. "primary:Download".
        String documentId = null;
        try {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception ignored) {
        }
        return documentId != null ? documentId : treeUri.getLastPathSegment();
    }

    /** Summary for the settings preference. */
    public static String getSummary(Context context) {
        if (!isEnabled(context)) {
            return context.getString(R.string.periodic_backup_summary_off);
        }
        return context.getString(R.string.periodic_backup_summary_on,
                intervalLabel(context, getInterval(context)), getKeepCount(context));
    }

    public static String intervalLabel(Context context, String interval) {
        switch (interval) {
            case INTERVAL_EVERY_OTHER_DAY:
                return context.getString(R.string.periodic_backup_interval_every_other_day);
            case INTERVAL_WEEKLY:
                return context.getString(R.string.periodic_backup_interval_weekly);
            case INTERVAL_MONTHLY:
                return context.getString(R.string.periodic_backup_interval_monthly);
            case INTERVAL_DAILY:
            default:
                return context.getString(R.string.periodic_backup_interval_daily);
        }
    }

    public static String formatDue(Context context, long time) {
        if (time <= 0) {
            return "";
        }
        SimpleDateFormat format = new SimpleDateFormat("dd. MMM yyyy, HH:mm", Locale.getDefault());
        return format.format(new Date(time));
    }

    /** Next 06:00 strictly after the given time. */
    public static long computeNextDue(long after) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(after);
        calendar.set(Calendar.HOUR_OF_DAY, SIX_AM_HOUR);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= after) {
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        return calendar.getTimeInMillis();
    }

    private static void addInterval(Calendar calendar, String interval) {
        switch (interval) {
            case INTERVAL_EVERY_OTHER_DAY:
                calendar.add(Calendar.DAY_OF_MONTH, 2);
                break;
            case INTERVAL_WEEKLY:
                calendar.add(Calendar.DAY_OF_MONTH, 7);
                break;
            case INTERVAL_MONTHLY:
                calendar.add(Calendar.MONTH, 1);
                break;
            case INTERVAL_DAILY:
            default:
                calendar.add(Calendar.DAY_OF_MONTH, 1);
                break;
        }
    }

    /** Next due time after a backup that was scheduled at {@code due} and ran at {@code now}. */
    static long nextDueAfter(long due, long now, String interval) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(due);
        do {
            addInterval(calendar, interval);
        } while (calendar.getTimeInMillis() <= now);
        return calendar.getTimeInMillis();
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, PeriodicBackupReceiver.class);
        intent.setAction(ACTION_PERIODIC_BACKUP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags);
    }

    public static void schedule(Context context) {
        if (!isSupported() || !isEnabled(context)) {
            cancelAlarm(context);
            return;
        }
        long due = getNextDue(context);
        if (due <= 0) {
            due = computeNextDue(System.currentTimeMillis());
            setNextDue(context, due);
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = alarmIntent(context);
        alarmManager.cancel(pendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, due, pendingIntent);
        }
    }

    public static void cancelAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(alarmIntent(context));
        }
    }

    /**
     * Runs a backup when one is due. Called when the app starts, from the alarm receiver and from
     * the settings screen.
     */
    public static void runIfDue(final Context context) {
        if (!isSupported() || !isEnabled(context)) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long due = getNextDue(context);
        if (due <= 0) {
            schedule(context);
            return;
        }
        if (now < due) {
            schedule(context);
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean success = performBackup(appContext);
                    setNextDue(appContext, nextDueAfter(due, System.currentTimeMillis(), getInterval(appContext)));
                    if (success) {
                        setLastRun(appContext, System.currentTimeMillis());
                    }
                } finally {
                    RUNNING.set(false);
                    schedule(appContext);
                }
            }
        }, "pedometer-periodic-backup").start();
    }

    @SuppressLint("NewApi")
    /** Writes one backup file into the selected folder and prunes old files. */
    public static boolean performBackup(Context context) {
        if (!isSupported()) {
            return false;
        }
        String tree = getTreeUri(context);
        if (tree == null) {
            return false;
        }
        Uri treeUri = Uri.parse(tree);
        ContentResolver resolver = context.getContentResolver();
        SimpleDateFormat fileFormat = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US);
        String fileName = BACKUP_FILE_PREFIX + fileFormat.format(new Date()) + ".json";
        Uri document = null;
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            document = DocumentsContract.createDocument(resolver, parent, "application/json", fileName);
            if (document == null) {
                return false;
            }
            boolean success;
            try (OutputStream out = resolver.openOutputStream(document)) {
                if (out == null) {
                    return false;
                }
                success = new BackupCreator().writeBackup(context, out);
            }
            if (!success) {
                try {
                    DocumentsContract.deleteDocument(resolver, document);
                } catch (Exception ignored) {
                }
                return false;
            }
            prune(context, resolver, treeUri);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not create the periodic backup.", e);
            if (document != null) {
                try {
                    DocumentsContract.deleteDocument(resolver, document);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }

    @SuppressLint("NewApi")
    private static void prune(Context context, ContentResolver resolver, Uri treeUri) {
        int keep = getKeepCount(context);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        List<String[]> backups = new ArrayList<>();
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (name != null && name.startsWith(BACKUP_FILE_PREFIX) && name.endsWith(".json")) {
                        backups.add(new String[]{
                                name,
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                        });
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not list existing backups.", e);
            return;
        }
        Collections.sort(backups, (a, b) -> a[0].compareTo(b[0]));
        while (backups.size() > keep) {
            String[] oldest = backups.remove(0);
            try {
                DocumentsContract.deleteDocument(resolver, Uri.parse(oldest[1]));
            } catch (Exception e) {
                Log.w(TAG, "Could not delete an old backup.", e);
            }
        }
    }
}
