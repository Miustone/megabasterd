package com.tonikelope.megabasterd;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Runs a lightweight, quota-safe hardware & internet capability benchmark at
 * startup so high-end PCs and fast connections automatically get higher
 * throughput while staying within MEGA-friendly connection limits.
 */
public final class SystemBenchmark {

    private static final Logger LOG = Logger.getLogger(SystemBenchmark.class.getName());

    public enum HardwareTier {
        ECO("Eco"),
        BALANCED("Balanced"),
        PERFORMANCE("Performance"),
        HIGH_END("High-End");

        private final String label;

        HardwareTier(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static final class AutoTuningProfile {

        private final HardwareTier tier;
        private final int cpuCores;
        private final long totalRamMb;
        private final long maxHeapMb;
        private final double aesThroughputMbPerSec;
        private final double diskWriteMbPerSec;
        private final double estimatedMbps;
        private final long latencyMs;
        private final int recommendedConcurrentDownloads;
        private final int recommendedDownloadSlots;
        private final int recommendedUploadSlots;
        private final int recommendedBufferBytes;

        public AutoTuningProfile(
                HardwareTier tier,
                int cpuCores,
                long totalRamMb,
                long maxHeapMb,
                double aesThroughputMbPerSec,
                double diskWriteMbPerSec,
                double estimatedMbps,
                long latencyMs,
                int recommendedConcurrentDownloads,
                int recommendedDownloadSlots,
                int recommendedUploadSlots,
                int recommendedBufferBytes) {
            this.tier = tier;
            this.cpuCores = cpuCores;
            this.totalRamMb = totalRamMb;
            this.maxHeapMb = maxHeapMb;
            this.aesThroughputMbPerSec = aesThroughputMbPerSec;
            this.diskWriteMbPerSec = diskWriteMbPerSec;
            this.estimatedMbps = estimatedMbps;
            this.latencyMs = latencyMs;
            this.recommendedConcurrentDownloads = recommendedConcurrentDownloads;
            this.recommendedDownloadSlots = recommendedDownloadSlots;
            this.recommendedUploadSlots = recommendedUploadSlots;
            this.recommendedBufferBytes = recommendedBufferBytes;
        }

        public HardwareTier getTier() {
            return tier;
        }

        public int getCpuCores() {
            return cpuCores;
        }

        public long getTotalRamMb() {
            return totalRamMb;
        }

        public long getMaxHeapMb() {
            return maxHeapMb;
        }

        public double getAesThroughputMbPerSec() {
            return aesThroughputMbPerSec;
        }

        public double getDiskWriteMbPerSec() {
            return diskWriteMbPerSec;
        }

        public double getEstimatedMbps() {
            return estimatedMbps;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public int getRecommendedConcurrentDownloads() {
            return recommendedConcurrentDownloads;
        }

        public int getRecommendedDownloadSlots() {
            return recommendedDownloadSlots;
        }

        public int getRecommendedUploadSlots() {
            return recommendedUploadSlots;
        }

        public int getRecommendedBufferBytes() {
            return recommendedBufferBytes;
        }

        public String toSummaryString() {
            String netPart = estimatedMbps > 0
                    ? String.format("%.0f Mbps (%d ms)", estimatedMbps, latencyMs)
                    : "Net: offline/untested";
            return String.format("%s (%d Cores, %.1f GB RAM, AES %.0f MB/s, Disk %.0f MB/s, %s) -> %d DL / %d Slots / %d KB Buf",
                    tier.getLabel(),
                    cpuCores,
                    totalRamMb / 1024.0,
                    aesThroughputMbPerSec,
                    diskWriteMbPerSec,
                    netPart,
                    recommendedConcurrentDownloads,
                    recommendedDownloadSlots,
                    recommendedBufferBytes / 1024);
        }
    }

    private static volatile AutoTuningProfile LAST_PROFILE = null;

    private SystemBenchmark() {
    }

    public static AutoTuningProfile getLastProfile() {
        return LAST_PROFILE;
    }

    public static AutoTuningProfile run(Path targetDir, boolean runNetworkProbe) {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long totalRamMb = detectTotalSystemRamMb();
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);

        double aesMbPerSec = benchmarkAesCtr();
        double diskMbPerSec = benchmarkDiskWrite(targetDir);

        double estMbps = -1;
        long latencyMs = -1;
        if (runNetworkProbe) {
            double[] net = probeNetworkSpeed();
            estMbps = net[0];
            latencyMs = (long) net[1];
        }

        AutoTuningProfile profile = computeProfile(cores, totalRamMb, maxHeapMb, aesMbPerSec, diskMbPerSec, estMbps, latencyMs);
        LAST_PROFILE = profile;
        LOG.log(Level.INFO, "[SystemBenchmark] {0}", profile.toSummaryString());
        return profile;
    }

    static AutoTuningProfile computeProfile(
            int cores,
            long totalRamMb,
            long maxHeapMb,
            double aesMbPerSec,
            double diskMbPerSec,
            double estMbps,
            long latencyMs) {

        int score = 0;
        if (cores >= 12) {
            score += 3;
        } else if (cores >= 8) {
            score += 2;
        } else if (cores >= 4) {
            score += 1;
        }

        if (totalRamMb >= 16_384) {
            score += 2;
        } else if (totalRamMb >= 8_192) {
            score += 1;
        }

        if (aesMbPerSec >= 800) {
            score += 2;
        } else if (aesMbPerSec >= 300) {
            score += 1;
        }

        if (diskMbPerSec >= 300) {
            score += 2;
        } else if (diskMbPerSec >= 100) {
            score += 1;
        }

        if (estMbps >= 250) {
            score += 2;
        } else if (estMbps >= 60) {
            score += 1;
        }

        HardwareTier tier;
        int concurrentDl;
        int dlSlots;
        int ulSlots;
        int bufSize;

        // Safe MEGA-friendly upper bounds: never exceed 5 concurrent DLs or 10 slots/file
        // so high-end machines saturate bandwidth cleanly without triggering host WAF/509 floods.
        if (score >= 9 && (estMbps < 0 || estMbps >= 150)) {
            tier = HardwareTier.HIGH_END;
            concurrentDl = 4;
            dlSlots = 10;
            ulSlots = 8;
            bufSize = 256 * 1024;
        } else if (score >= 6) {
            tier = HardwareTier.PERFORMANCE;
            concurrentDl = 4;
            dlSlots = 8;
            ulSlots = 6;
            bufSize = 128 * 1024;
        } else if (score >= 3) {
            tier = HardwareTier.BALANCED;
            concurrentDl = 3;
            dlSlots = 6;
            ulSlots = 4;
            bufSize = 64 * 1024;
        } else {
            tier = HardwareTier.ECO;
            concurrentDl = 2;
            dlSlots = 4;
            ulSlots = 3;
            bufSize = 32 * 1024;
        }

        // If network is explicitly measured and slow (< 20 Mbps), avoid opening 10 slots that just compete.
        if (estMbps > 0 && estMbps < 20) {
            dlSlots = Math.min(dlSlots, 3);
            concurrentDl = Math.min(concurrentDl, 2);
        } else if (estMbps > 0 && estMbps < 60) {
            dlSlots = Math.min(dlSlots, 5);
        }

        // Respect heap ceiling if user launched with tiny -Xmx.
        if (maxHeapMb > 0 && maxHeapMb < 512) {
            bufSize = Math.min(bufSize, 64 * 1024);
            dlSlots = Math.min(dlSlots, 6);
        }

        return new AutoTuningProfile(
                tier, cores, totalRamMb, maxHeapMb, aesMbPerSec, diskMbPerSec,
                estMbps, latencyMs, concurrentDl, dlSlots, ulSlots, bufSize);
    }

    private static long detectTotalSystemRamMb() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize() / (1024L * 1024L);
            }
        } catch (Throwable ignore) {
        }
        return 4096L;
    }

    private static double benchmarkAesCtr() {
        try {
            byte[] key = new byte[16];
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(key);
            byte[] in = new byte[2 * 1024 * 1024];
            byte[] out = new byte[in.length];
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            long t0 = System.nanoTime();
            cipher.update(in, 0, in.length, out, 0);
            long dt = Math.max(1L, System.nanoTime() - t0);
            return (in.length / (1024.0 * 1024.0)) / (dt / 1_000_000_000.0);
        } catch (Exception ex) {
            return 100.0;
        }
    }

    private static double benchmarkDiskWrite(Path targetDir) {
        Path probe = null;
        try {
            Path dir = targetDir;
            while (dir != null && !Files.exists(dir)) {
                dir = dir.getParent();
            }
            if (dir == null || !Files.isWritable(dir)) {
                dir = Path.of(System.getProperty("java.io.tmpdir"));
            }
            probe = Files.createTempFile(dir, ".mb_bench_", ".tmp");
            byte[] data = new byte[512 * 1024];
            ByteBuffer buf = ByteBuffer.wrap(data);
            long t0 = System.nanoTime();
            try (FileChannel fc = FileChannel.open(probe, StandardOpenOption.WRITE)) {
                while (buf.hasRemaining()) {
                    fc.write(buf);
                }
            }
            long dt = Math.max(1L, System.nanoTime() - t0);
            return (data.length / (1024.0 * 1024.0)) / (dt / 1_000_000_000.0);
        } catch (Exception ex) {
            return 100.0;
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Tiny 256 KB timed burst probe (zero MEGA quota impact).
     * Returns {estimatedMbps, latencyMs} or {-1, -1} on failure/offline.
     */
    private static double[] probeNetworkSpeed() {
        String[] urls = {
            "https://speed.cloudflare.com/__down?bytes=262144",
            "https://g.api.mega.co.nz/"
        };
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(2500))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        for (String u : urls) {
            try {
                long t0 = System.nanoTime();
                HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                        .timeout(Duration.ofMillis(3000))
                        .header("User-Agent", MainPanel.DEFAULT_USER_AGENT)
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                long dtNs = Math.max(1L, System.nanoTime() - t0);
                long latencyMs = Math.max(1L, dtNs / 1_000_000L);
                int bytes = resp.body() != null ? resp.body().length : 0;
                if (resp.statusCode() >= 200 && resp.statusCode() < 400 && bytes >= 64 * 1024) {
                    double mbps = (bytes * 8.0 / 1_000_000.0) / (dtNs / 1_000_000_000.0);
                    return new double[]{mbps, latencyMs};
                }
                if (resp.statusCode() > 0) {
                    // Latency-only fallback if payload was small.
                    return new double[]{latencyMs < 60 ? 100.0 : 40.0, latencyMs};
                }
            } catch (Exception ignore) {
            }
        }
        return new double[]{-1.0, -1.0};
    }
}
