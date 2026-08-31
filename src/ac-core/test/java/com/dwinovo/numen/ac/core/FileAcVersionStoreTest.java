package com.dwinovo.numen.ac.core;

import com.dwinovo.numen.ac.api.AcDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileAcVersionStoreTest {

    @Test
    void persistsAcrossStoreInstances() throws Exception {
        Path tmp = Files.createTempFile("ac-store-test", ".json");
        try {
            var store1 = new FileAcVersionStore(tmp);
            var ac = new AcDefinition("persist-demo", List.of(new AcDefinition.AcStep("a", "ok", Map.of())));
            store1.put("persist-demo", "1", ac);

            // 新实例（模拟重启）应从磁盘恢复
            var store2 = new FileAcVersionStore(tmp);
            assertTrue(store2.get("persist-demo", "1").isPresent(), "重启后应能按名+版本取回");
            assertEquals("ok", store2.latest("persist-demo").get().steps().get(0).tool());
            assertEquals(1, store2.versions("persist-demo").size());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
