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
package org.secuso.privacyfriendlyactivitytracker.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class ModelTest {

    @Test
    public void walkingModeWithSameId_isEqual() {
        WalkingMode first = new WalkingMode();
        first.setId(7);
        WalkingMode second = new WalkingMode();
        second.setId(7);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void walkingModeWithDifferentId_isNotEqual() {
        WalkingMode first = new WalkingMode();
        first.setId(7);
        WalkingMode second = new WalkingMode();
        second.setId(8);

        assertNotEquals(first, second);
    }

    @Test
    public void unsavedWalkingModes_areOnlyEqualToThemselves() {
        WalkingMode first = new WalkingMode();
        WalkingMode second = new WalkingMode();

        assertNotEquals(first, second);
        assertEquals(first, first);
    }

    @Test
    public void stepCountCalculatesDistanceFromWalkingModeStepLength() {
        WalkingMode mode = new WalkingMode();
        mode.setStepLength(0.75);
        StepCount stepCount = new StepCount();
        stepCount.setStepCount(1000);
        stepCount.setWalkingMode(mode);

        assertEquals(750.0, stepCount.getDistance(), 0.0001);
    }

    @Test
    public void stepCountWithoutWalkingMode_hasNoDistance() {
        StepCount stepCount = new StepCount();
        stepCount.setStepCount(1000);

        assertEquals(0.0, stepCount.getDistance(), 0.0001);
        assertFalse(stepCount.getDistance() > 0);
    }

    @Test
    public void stepCountCaloriesScaleWithDistanceAndFactor() {
        WalkingMode mode = new WalkingMode();
        mode.setStepLength(1.0);
        StepCount stepCount = new StepCount();
        stepCount.setStepCount(2000); // 2 km
        stepCount.setWalkingMode(mode);

        assertEquals(2.0 * 60.0, stepCount.getCalories(60.0), 0.0001);
    }

    @Test
    public void trainingDurationAndVelocityAreCalculatedInSeconds() {
        Training training = new Training();
        training.setStart(1_000_000L);
        training.setEnd(1_090_000L); // 90 s
        training.setDistance(180.0);

        assertEquals(90, training.getDuration());
        assertEquals(2.0, training.getVelocity(), 0.0001);
    }

    @Test
    public void trainingWithoutDuration_hasNoVelocity() {
        Training training = new Training();
        training.setStart(1_000_000L);
        training.setEnd(1_000_000L);
        training.setDistance(180.0);

        assertEquals(0.0, training.getVelocity(), 0.0001);
    }
}
