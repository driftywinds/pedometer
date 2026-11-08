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
import android.os.PowerManager;
import android.util.Log;

import org.secuso.privacyfriendlyactivitytracker.utils.PeriodicBackupManager;

/**
 * Receives the periodic backup alarm as well as the boot broadcast, so the next alarm is
 * re-armed after a reboot.
 * <p>
 * The receiver keeps the process alive with {@link #goAsync()} and a partial wake lock while
 * the backup is written, so that a sleeping device cannot kill the process in the middle of
 * the export.
 */
public class PeriodicBackupReceiver extends BroadcastReceiver {
    private static final String TAG = PeriodicBackupReceiver.class.getSimpleName();
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final String action = intent.getAction();
        final PendingResult pendingResult = goAsync();
        final PowerManager.WakeLock wakeLock = acquireWakeLock(appContext);
        new Thread(() -> {
            try {
                if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    PeriodicBackupManager.schedule(appContext);
                }
                PeriodicBackupManager.runIfDueBlocking(appContext);
            } catch (RuntimeException e) {
                Log.e(TAG, "Periodic backup failed.", e);
            } finally {
                if (wakeLock != null) {
                    try {
                        wakeLock.release();
                    } catch (RuntimeException ignored) {
                        // Already released.
                    }
                }
                pendingResult.finish();
            }
        }, "pedometer-periodic-backup-receiver").start();
    }

    private PowerManager.WakeLock acquireWakeLock(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            return null;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "pfPedometer:PeriodicBackup");
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return wakeLock;
    }
}
