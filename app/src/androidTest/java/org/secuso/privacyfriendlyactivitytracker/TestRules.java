/*
    Privacy Friendly Pedometer is licensed under the GPLv3.

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
package org.secuso.privacyfriendlyactivitytracker;

import android.os.Build;

import androidx.test.rule.GrantPermissionRule;

import org.junit.rules.TestRule;

/**
 * JUnit 4 rejects {@code null} rule fields, so a permission rule which does not apply to the
 * current API level must be a no-op rule instead of {@code null}.
 */
public final class TestRules {

    private static final TestRule NO_OP = (base, description) -> base;

    private TestRules() {
    }

    /**
     * Grants the given runtime permissions when the device runs at least the given API level.
     */
    public static TestRule grantFromSdk(int minSdk, String... permissions) {
        return Build.VERSION.SDK_INT >= minSdk
                ? GrantPermissionRule.grant(permissions)
                : NO_OP;
    }

    /**
     * Grants the given permissions only on API levels below the given one (for permissions which
     * were removed from the runtime model later).
     */
    public static TestRule grantBelowSdk(int maxSdk, String... permissions) {
        return Build.VERSION.SDK_INT < maxSdk
                ? GrantPermissionRule.grant(permissions)
                : NO_OP;
    }
}
