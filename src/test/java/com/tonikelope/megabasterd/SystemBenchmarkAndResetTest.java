package com.tonikelope.megabasterd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SystemBenchmarkAndResetTest {

    @Test
    void autoTuneScalesWithHardwareWithinHostSafeCaps() {
        SystemBenchmark.AutoTuningProfile eco = SystemBenchmark.computeProfile(
                2, 4096, 1024, 150.0, 60.0, 15.0, 80);
        SystemBenchmark.AutoTuningProfile highEnd = SystemBenchmark.computeProfile(
                16, 32768, 8192, 2500.0, 2000.0, 600.0, 10);

        assertEquals(SystemBenchmark.HardwareTier.ECO, eco.getTier());
        assertEquals(SystemBenchmark.HardwareTier.HIGH_END, highEnd.getTier());
        assertTrue(highEnd.getRecommendedDownloadSlots() > eco.getRecommendedDownloadSlots());
        assertTrue(highEnd.getRecommendedConcurrentDownloads() >= eco.getRecommendedConcurrentDownloads());
        assertTrue(highEnd.getRecommendedBufferBytes() >= eco.getRecommendedBufferBytes());

        // Host protection caps
        assertTrue(highEnd.getRecommendedDownloadSlots() <= 10);
        assertTrue(highEnd.getRecommendedConcurrentDownloads() <= 5);
    }

    @Test
    void resetWipesMegaBasterdArtifactsAndPreservesUnrelatedFiles(@TempDir Path tmp) throws Exception {
        Path fakeHome = tmp.resolve("home");
        Path dlDir = tmp.resolve("downloads");
        Files.createDirectories(fakeHome.resolve(".megabasterd9.0"));
        Files.createDirectories(fakeHome.resolve(".megabasterd_old_backups"));
        Files.createDirectories(fakeHome.resolve("MEGABASTERD_UPLOAD_LOGS"));
        Files.createDirectories(fakeHome.resolve("Documents"));
        Files.createDirectories(dlDir);

        Files.writeString(fakeHome.resolve(".megabasterd9.0/megabasterd.db"), "db");
        Files.writeString(fakeHome.resolve("MEGABASTERD_DEBUG.log"), "log");
        Files.writeString(fakeHome.resolve("Documents/keep_me.txt"), "important");

        Path trackedFile = dlDir.resolve("movie.mkv");
        Path trackedPart = dlDir.resolve("movie.mkv.mbpart");
        Path unrelatedFile = dlDir.resolve("other_user_file.txt");
        Files.writeString(trackedFile, "data");
        Files.writeString(trackedPart, "part");
        Files.writeString(unrelatedFile, "stay");

        ResetManager.ResetResult res = ResetManager.wipeToolData(fakeHome, List.of(trackedFile));
        assertEquals(0, res.failedItems);
        assertFalse(Files.exists(fakeHome.resolve(".megabasterd9.0")));
        assertFalse(Files.exists(fakeHome.resolve(".megabasterd_old_backups")));
        assertFalse(Files.exists(fakeHome.resolve("MEGABASTERD_UPLOAD_LOGS")));
        assertFalse(Files.exists(fakeHome.resolve("MEGABASTERD_DEBUG.log")));
        assertFalse(Files.exists(trackedFile));
        assertFalse(Files.exists(trackedPart));

        assertTrue(Files.exists(fakeHome.resolve("Documents/keep_me.txt")));
        assertTrue(Files.exists(unrelatedFile));
    }
}
