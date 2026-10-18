package com.dwinovo.numen.rdd.side;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 无游戏的世界端口：纯 JVM 测试注入。 */
final class FakeWorldPort implements SideTaskWorldPort {

    long gameTime;
    long dayTime;
    long gameDay;
    boolean hasDayNightCycle = true;
    String worldId = "minecraft:overworld";
    boolean day;
    boolean inCombat;
    boolean bodyBusy;
    String mainlineObjective;
    private final Map<UUID, Boolean> sleeping = new HashMap<>();
    private final Map<UUID, Wake> wakes = new HashMap<>();

    FakeWorldPort at(long gameTime, long gameDay) {
        this.gameTime = gameTime;
        this.dayTime = gameTime;
        this.gameDay = gameDay;
        return this;
    }

    FakeWorldPort dayTime(long value) {
        this.dayTime = value;
        return this;
    }

    FakeWorldPort day(boolean value) {
        this.day = value;
        return this;
    }

    FakeWorldPort cycle(boolean value) {
        this.hasDayNightCycle = value;
        return this;
    }

    FakeWorldPort world(String id) {
        this.worldId = id;
        return this;
    }

    FakeWorldPort asleep(UUID companionId, boolean value) {
        sleeping.put(companionId, value);
        return this;
    }

    FakeWorldPort wake(UUID companionId, Wake wake) {
        wakes.put(companionId, wake);
        return this;
    }

    FakeWorldPort inCombat(boolean value) {
        this.inCombat = value;
        return this;
    }

    FakeWorldPort bodyBusy(boolean value) {
        this.bodyBusy = value;
        return this;
    }

    FakeWorldPort objective(String value) {
        this.mainlineObjective = value;
        return this;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public long dayTime() {
        return dayTime;
    }

    @Override
    public long gameDay() {
        return gameDay;
    }

    @Override
    public boolean hasDayNightCycle() {
        return hasDayNightCycle;
    }

    @Override
    public String worldId() {
        return worldId;
    }

    @Override
    public boolean isSleeping(UUID companionId) {
        return sleeping.getOrDefault(companionId, false);
    }

    @Override
    public boolean isDay() {
        return day;
    }

    @Override
    public Wake pollWake(UUID companionId) {
        Wake wake = wakes.remove(companionId);
        return wake == null ? Wake.NONE : wake;
    }

    @Override
    public boolean inCombat(UUID companionId) {
        return inCombat;
    }

    @Override
    public boolean bodyBusy(UUID companionId) {
        return bodyBusy;
    }

    @Override
    public String mainlineObjective(UUID companionId) {
        return mainlineObjective;
    }
}
