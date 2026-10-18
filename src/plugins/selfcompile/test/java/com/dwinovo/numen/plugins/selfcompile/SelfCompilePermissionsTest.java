package com.dwinovo.numen.plugins.selfcompile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** P5.2 权限分级：缺省最小权限、档位蕴含、CSV 载入、坏配置不越权。 */
class SelfCompilePermissionsTest {

    private static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CAROL = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void unknownUuidDefaultsToObserver() {
        SelfCompilePermissions p = new SelfCompilePermissions();
        assertEquals(SelfCompilePermissions.Tier.OBSERVER, p.tierOf(ALICE));
        assertTrue(p.allowed(ALICE, SelfCompilePermissions.Action.VIEW));
        assertFalse(p.allowed(ALICE, SelfCompilePermissions.Action.PROPOSE));
        assertFalse(p.allowed(ALICE, SelfCompilePermissions.Action.APPROVE));
        assertFalse(p.allowed((UUID) null, SelfCompilePermissions.Action.VIEW), "null 身份不得通过");
    }

    @Test
    void higherTierImpliesLowerActions() {
        var T = SelfCompilePermissions.Tier.MAINTAINER;
        assertTrue(SelfCompilePermissions.allowed(SelfCompilePermissions.Tier.MAINTAINER,
                SelfCompilePermissions.Action.PROPOSE));
        assertTrue(SelfCompilePermissions.allowed(SelfCompilePermissions.Tier.CONTRIBUTOR,
                SelfCompilePermissions.Action.PROPOSE));
        assertTrue(SelfCompilePermissions.allowed(SelfCompilePermissions.Tier.OBSERVER,
                SelfCompilePermissions.Action.VIEW));
        assertFalse(SelfCompilePermissions.allowed(SelfCompilePermissions.Tier.CONTRIBUTOR,
                SelfCompilePermissions.Action.APPROVE));
        assertFalse(SelfCompilePermissions.allowed(SelfCompilePermissions.Tier.OBSERVER,
                SelfCompilePermissions.Action.PROPOSE));
        assertTrue(SelfCompilePermissions.allowed(T, SelfCompilePermissions.Action.DEPLOY));
    }

    @Test
    void loadsTiersFromCsv() throws Exception {
        Path f = Files.createTempFile("selfcompile-perms-", ".csv");
        Files.writeString(f, "# tier,uuid\n"
                + "MAINTAINER," + ALICE + "\n"
                + "CONTRIBUTOR," + BOB + "\n"
                + "OBSERVER," + CAROL + "\n");
        SelfCompilePermissions p = SelfCompilePermissions.load(f);
        assertEquals(SelfCompilePermissions.Tier.MAINTAINER, p.tierOf(ALICE));
        assertEquals(SelfCompilePermissions.Tier.CONTRIBUTOR, p.tierOf(BOB));
        assertEquals(SelfCompilePermissions.Tier.OBSERVER, p.tierOf(CAROL));
        assertTrue(p.allowed(BOB, SelfCompilePermissions.Action.PROPOSE));
        assertFalse(p.allowed(BOB, SelfCompilePermissions.Action.APPROVE));
        assertTrue(p.allowed(ALICE, SelfCompilePermissions.Action.APPROVE));
        assertFalse(p.allowed(CAROL, SelfCompilePermissions.Action.PROPOSE));
    }

    @Test
    void missingConfigNeverEscalates() {
        SelfCompilePermissions missing = SelfCompilePermissions.load(Path.of("no-such-selfcompile-perms.csv"));
        assertEquals(0, missing.size());
        assertEquals(SelfCompilePermissions.Tier.OBSERVER, missing.tierOf(ALICE));
    }

    @Test
    void corruptConfigSkipsBadLinesAndNeverEscalates() throws Exception {
        Path bad = Files.createTempFile("selfcompile-perms-bad-", ".csv");
        Files.writeString(bad, "SUPERUSER,not-a-uuid\n"
                + ",,\n"
                + "MAINTAINER," + ALICE + "\n"
                + "GARBAGE,also-not-a-uuid\n"
                + "CONTRIBUTOR," + BOB + "\n");
        SelfCompilePermissions p = SelfCompilePermissions.load(bad);
        assertEquals(SelfCompilePermissions.Tier.MAINTAINER, p.tierOf(ALICE), "合法行仍生效");
        assertEquals(SelfCompilePermissions.Tier.CONTRIBUTOR, p.tierOf(BOB), "合法行仍生效");
        assertEquals(SelfCompilePermissions.Tier.OBSERVER, p.tierOf(UUID.randomUUID()), "未列出者仍最小权限");
        assertEquals(2, p.size(), "只收下两行合法条目");
    }
}
