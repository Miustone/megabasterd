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
        assertTrue(highEnd.getRecommendedConcurrentDownloads() <= 6);
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

    @Test
    void restartCommandPrefersJpackageLauncherWhenPresent(@TempDir Path tmp) throws Exception {
        Path exe = tmp.resolve("MegaBasterd.exe");
        Files.writeString(exe, "stub");

        List<String> jpackageCmd = MiscTools.buildRestartCommand(
                exe.toString(), "C:\\jdk", List.of("-Xmx1g"), "app.jar", true);
        assertEquals(List.of(exe.toString(), "native", "1"), jpackageCmd);

        List<String> fallbackCmd = MiscTools.buildRestartCommand(
                null, tmp.toString(), List.of("--enable-native-access=ALL-UNNAMED"), "app.jar", false);
        assertTrue(fallbackCmd.get(0).endsWith("java"));
        assertTrue(fallbackCmd.contains(MainPanel.class.getName()));
    }

    @Test
    void updateFontsHandlesFileChooserAndFontlessComponentsUnderWinUI3() throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return;
        }
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            WinUI3Theme.apply(false);
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            java.awt.Font font = WinUI3Theme.resolveWin11Font(java.awt.Font.PLAIN, 13);
            assertDoesNotThrow(() -> MiscTools.updateFonts(fc, font, 1.25f));

            javax.swing.JPanel fontless = new javax.swing.JPanel();
            fontless.setFont(null);
            assertDoesNotThrow(() -> MiscTools.updateFonts(fontless, font, 1.0f));
        });
    }

    @Test
    void extractMegaLinksHandlesModernSpacesBracketsAndDomains() {
        String input = String.join("\n",
                "https://mega.nz/file/AbCd1234#Key+WithPlus/trailing?usp=sharing",
                "[Movie](https://mega.io/file/EfGh5678#KeyTwo90)",
                "https://mega.nz/folder/Fold1111#FoldKey1 https://mega.nz/folder/Fold2222#FoldKey2/file/Sub333");
        String extracted = MiscTools.extractMegaLinksFromString(input);
        assertTrue(extracted.contains("https://mega.nz/#!AbCd1234!Key+WithPlus"));
        assertTrue(extracted.contains("https://mega.nz/#!EfGh5678!KeyTwo90"));
        assertTrue(extracted.contains("https://mega.nz/#F!Fold1111!FoldKey1"));
        assertTrue(extracted.contains("https://mega.nz/#F*Sub333!Fold2222!FoldKey2"));
    }

    @Test
    void cleanFilePathStripsLeadingSeparatorsSoWindowsResolveStaysInsideBaseDir(@TempDir Path baseDir) {
        String cleaned = MiscTools.cleanFilePath("\\Puddingnice\\PICS\\img1.jpg");
        assertFalse(cleaned.startsWith("\\"));
        assertFalse(cleaned.startsWith("/"));
        Path resolved = baseDir.toAbsolutePath().normalize().resolve(cleaned).normalize();
        assertTrue(resolved.startsWith(baseDir.toAbsolutePath().normalize()));
        assertEquals("Puddingnice", baseDir.toAbsolutePath().normalize().relativize(resolved).getName(0).toString());
    }

    @Test
    void upgradeMegaStorageUrlPromotesHttpStorageLinksOnly() {
        assertEquals(
                "https://gfs214n224.userstorage.mega.co.nz/dl/abc",
                MiscTools.upgradeMegaStorageUrlToHttps("http://gfs214n224.userstorage.mega.co.nz/dl/abc"));
        assertEquals(
                "https://gfs214n224.userstorage.mega.co.nz/dl/abc",
                MiscTools.upgradeMegaStorageUrlToHttps("https://gfs214n224.userstorage.mega.co.nz/dl/abc"));
        assertEquals(
                "http://127.0.0.1:1337/stream",
                MiscTools.upgradeMegaStorageUrlToHttps("http://127.0.0.1:1337/stream"));
    }

    @Test
    void ipv6BlacklistTracksUnreachableHosts() throws Exception {
        String host = "dead-ipv6-test-node.invalid";
        assertFalse(HttpClientFactory.isHostIpv6Blacklisted(host));
        HttpClientFactory.markHostIpv6Blacklisted(host);
        assertTrue(HttpClientFactory.isHostIpv6Blacklisted(host));

        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
                java.net.Socket client = HttpClientFactory.ipv4SocketFactory().createSocket()) {
            client.connect(new java.net.InetSocketAddress("localhost", ss.getLocalPort()), 1000);
            try (java.net.Socket accepted = ss.accept()) {
                assertInstanceOf(java.net.Inet4Address.class, client.getInetAddress());
            }
        }
    }
}
