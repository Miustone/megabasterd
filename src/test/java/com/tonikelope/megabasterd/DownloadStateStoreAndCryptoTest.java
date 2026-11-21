package com.tonikelope.megabasterd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DownloadStateStoreAndCryptoTest {

    @Test
    void streamingSubChunkMacMatchesLegacyCbcMac(@TempDir Path tmp) throws Exception {
        byte[] key = new byte[16];
        byte[] ivNonce = new byte[8];
        new SecureRandom().nextBytes(key);
        new SecureRandom().nextBytes(ivNonce);

        // Build a synthetic file spanning chunks 1..8 + partial EOF (total ~4.2 MiB)
        int totalSize = (128 + 256 + 384 + 512 + 640 + 768 + 896) * 1024 + 500_123;
        byte[] plain = new byte[totalSize];
        new SecureRandom().nextBytes(plain);

        // Compute legacy chunk-by-chunk meta_mac reference
        byte[] iv16 = new byte[16];
        System.arraycopy(ivNonce, 0, iv16, 0, 8);
        System.arraycopy(ivNonce, 0, iv16, 8, 8);

        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] fileMac = new byte[16];

        long cid = 1L;
        while (ChunkWriterManager.calculateChunkOffset(cid, 1) < totalSize) {
            long off = ChunkWriterManager.calculateChunkOffset(cid, 1);
            long sz = ChunkWriterManager.calculateChunkSize(cid, totalSize, off, 1);
            Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
            cbc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv16));
            byte[] block = new byte[16];
            byte[] cMac = new byte[16];
            long pos = 0;
            while (pos < sz) {
                int n = (int) Math.min(16, sz - pos);
                java.util.Arrays.fill(block, (byte) 0);
                System.arraycopy(plain, (int) (off + pos), block, 0, n);
                cMac = cbc.update(block, 0, 16);
                pos += n;
            }
            for (int i = 0; i < 16; i++) {
                fileMac[i] ^= cMac[i];
            }
            fileMac = ecb.doFinal(fileMac);
            cid++;
        }
        int[] fmInt = MiscTools.bin2i32a(fileMac);
        int[] expectedMeta = new int[]{fmInt[0] ^ fmInt[1], fmInt[2] ^ fmInt[3]};

        // Encode key||ivNonce||metaMac into MEGA fileKey format
        int[] k32 = MiscTools.bin2i32a(key);
        int[] iv32 = MiscTools.bin2i32a(ivNonce);
        int[] fullKey32 = new int[]{
            k32[0] ^ iv32[0], k32[1] ^ iv32[1], k32[2] ^ expectedMeta[0], k32[3] ^ expectedMeta[1],
            iv32[0], iv32[1], expectedMeta[0], expectedMeta[1]
        };
        String megaFileKey = MiscTools.Bin2UrlBASE64(MiscTools.i32a2bin(fullKey32));

        // Now run streaming SubChunkMacAccumulator across CHUNK_SIZE_MULTI=20 chunks
        Path partFile = tmp.resolve("test.mbpart");
        Path stateFile = tmp.resolve("test.mbstate");
        Files.write(partFile, plain);
        DownloadStateStore store = new DownloadStateStore(stateFile, "https://mega.nz/file/test", totalSize, 20);

        byte[] fullIv16 = CryptTools.initMEGALinkKeyIV(megaFileKey);
        byte[] decodedKey = CryptTools.initMEGALinkKey(megaFileKey);

        long dlChunkId = 1L;
        while (ChunkWriterManager.calculateChunkOffset(dlChunkId, 20) < totalSize) {
            long off = ChunkWriterManager.calculateChunkOffset(dlChunkId, 20);
            long sz = ChunkWriterManager.calculateChunkSize(dlChunkId, totalSize, off, 20);
            CryptTools.SubChunkMacAccumulator acc = new CryptTools.SubChunkMacAccumulator(
                    dlChunkId, 20, totalSize, decodedKey, fullIv16);
            // Feed in irregular 17,777-byte slices to test carry logic
            int cursor = 0;
            while (cursor < sz) {
                int step = (int) Math.min(17_777, sz - cursor);
                acc.update(plain, (int) off + cursor, step);
                cursor += step;
            }
            Map<Long, byte[]> macs = acc.finish();
            store.markChunkCompleted(dlChunkId, sz, macs);
            dlChunkId++;
        }
        store.persistNow();

        // Reload from disk and verify fast meta-MAC check
        DownloadStateStore reloaded = new DownloadStateStore(stateFile, "https://mega.nz/file/test", totalSize, 20);
        reloaded.loadIfCompatible(partFile);
        assertEquals(totalSize, reloaded.getCompletedBytes());
        assertEquals(-1L, reloaded.nextUncompletedChunkId(1L));
        assertEquals(Boolean.TRUE, CryptTools.verifyMetaMacFromStore(reloaded, megaFileKey, totalSize));
    }

    @Test
    void stateStoreResumesOnlyMissingChunksAcrossRestart(@TempDir Path tmp) throws Exception {
        long fileSize = 5_000_000L;
        Path partFile = tmp.resolve("resume.mbpart");
        Path stateFile = tmp.resolve("resume.mbstate");
        Files.write(partFile, new byte[(int) fileSize]);

        DownloadStateStore store = new DownloadStateStore(stateFile, "https://mega.nz/file/abc", fileSize, 20);
        // Mark chunks 1 and 3 completed (skip chunk 2)
        long c1 = ChunkWriterManager.calculateChunkSize(1, fileSize, ChunkWriterManager.calculateChunkOffset(1, 20), 20);
        long c3 = ChunkWriterManager.calculateChunkSize(3, fileSize, ChunkWriterManager.calculateChunkOffset(3, 20), 20);
        store.markChunkCompleted(1, c1, Map.of());
        store.markChunkCompleted(3, c3, Map.of());
        store.persistNow();

        DownloadStateStore reloaded = new DownloadStateStore(stateFile, "https://mega.nz/file/abc", fileSize, 20);
        reloaded.loadIfCompatible(partFile);

        assertTrue(reloaded.isChunkCompleted(1));
        assertFalse(reloaded.isChunkCompleted(2));
        assertTrue(reloaded.isChunkCompleted(3));
        assertEquals(c1 + c3, reloaded.getCompletedBytes());
        assertEquals(2L, reloaded.nextUncompletedChunkId(1L));
        assertEquals(4L, reloaded.nextUncompletedChunkId(3L));
    }

    @Test
    void diskSpaceWorksForNonExistentNestedDirectories(@TempDir Path tmp) {
        Path nested = tmp.resolve("a/b/c/not_created_yet");
        long free = MiscTools.getAvailableDiskSpace(nested);
        assertTrue(free > 0L);
    }

    @Test
    void megaProxyConnectPatternRejectsLookalikeHosts() {
        assertTrue(MegaProxyServer.Handler.CONNECT_PATTERN.matcher("CONNECT gfs270n123.userstorage.mega.co.nz:443 HTTP/1.1").matches());
        assertTrue(MegaProxyServer.Handler.CONNECT_PATTERN.matcher("CONNECT g.api.mega.nz:443 HTTP/1.1").matches());
        assertFalse(MegaProxyServer.Handler.CONNECT_PATTERN.matcher("CONNECT evilmega.nz:443 HTTP/1.1").matches());
        assertFalse(MegaProxyServer.Handler.CONNECT_PATTERN.matcher("CONNECT notmega.co.nz:443 HTTP/1.1").matches());
    }
}
