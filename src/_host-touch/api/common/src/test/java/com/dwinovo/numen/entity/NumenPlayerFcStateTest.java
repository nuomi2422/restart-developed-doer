package com.dwinovo.numen.entity;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumenPlayerFcStateTest {

    @Test
    void oldCompanionDataKeepsFcEnabled() {
        assertTrue(NumenPlayer.fcEnabledFromSaveData(new CompoundTag()));
    }

    @Test
    void explicitFcSwitchRoundTripsBothStates() {
        CompoundTag data = new CompoundTag();
        data.putBoolean("NumenFcEnabled", false);
        assertFalse(NumenPlayer.fcEnabledFromSaveData(data));

        data.putBoolean("NumenFcEnabled", true);
        assertTrue(NumenPlayer.fcEnabledFromSaveData(data));
    }
}
