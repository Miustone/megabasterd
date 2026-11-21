package com.tonikelope.megabasterd;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Atomic, crash-safe chunk completion & sub-chunk CBC-MAC state persisted next
 * to {@code .mbpart} so downloads resume from the exact completed chunks across
 * pauses, network drops, crashes, and application restarts without re-fetching
 * completed chunks from MEGA.
 */
public final class DownloadStateStore {

    private static final Logger LOG = Logger.getLogger(DownloadStateStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path statePath;
    private final String url;
    private final long fileSize;
    private final int chunkSizeMulti;
    private final BitSet completedChunks = new BitSet();
    private final Map<Long, byte[]> subChunkMacs = new TreeMap<>();
    private long completedBytes = 0L;
    private long lastPersistMs = 0L;

    public DownloadStateStore(Path statePath, String url, long fileSize, int chunkSizeMulti) {
        this.statePath = statePath;
        this.url = url;
        this.fileSize = fileSize;
        this.chunkSizeMulti = chunkSizeMulti;
    }

    public Path getStatePath() {
        return statePath;
    }

    public synchronized boolean isChunkCompleted(long chunkId) {
        return chunkId > 0 && chunkId <= Integer.MAX_VALUE && completedChunks.get((int) chunkId);
    }

    public synchronized long getCompletedBytes() {
        return completedBytes;
    }

    public synchronized void markChunkCompleted(long chunkId, long chunkBytes, Map<Long, byte[]> macs) {
        if (chunkId <= 0 || chunkId > Integer.MAX_VALUE) {
            return;
        }
        int idx = (int) chunkId;
        if (!completedChunks.get(idx)) {
            completedChunks.set(idx);
            completedBytes += chunkBytes;
        }
        if (macs != null) {
            subChunkMacs.putAll(macs);
        }
        long now = System.currentTimeMillis();
        if (now - lastPersistMs >= 1500L) {
            persistLocked();
        }
    }

    public synchronized void markPrefixCompleted(long prefixBytes, int sizeMulti) {
        long offset = 0L;
        long chunkId = 1L;
        while (offset < prefixBytes && offset < fileSize) {
            long cSize = ChunkWriterManager.calculateChunkSize(chunkId, fileSize, offset, sizeMulti);
            if (offset + cSize <= prefixBytes) {
                if (!completedChunks.get((int) chunkId)) {
                    completedChunks.set((int) chunkId);
                    completedBytes += cSize;
                }
                offset += cSize;
                chunkId++;
            } else {
                break;
            }
        }
        persistLocked();
    }

    public synchronized long nextUncompletedChunkId(long startFrom) {
        long candidate = Math.max(1L, startFrom);
        while (candidate <= Integer.MAX_VALUE) {
            long offset = ChunkWriterManager.calculateChunkOffset(candidate, chunkSizeMulti);
            if (offset >= fileSize) {
                return -1L;
            }
            if (!completedChunks.get((int) candidate)) {
                return candidate;
            }
            candidate++;
        }
        return -1L;
    }

    public synchronized boolean hasAllSubChunkMacs(long totalMacChunks) {
        if (totalMacChunks <= 0) {
            return true;
        }
        for (long i = 1L; i <= totalMacChunks; i++) {
            if (!subChunkMacs.containsKey(i)) {
                return false;
            }
        }
        return true;
    }

    public synchronized byte[] getSubChunkMac(long macChunkId) {
        return subChunkMacs.get(macChunkId);
    }

    public synchronized void loadIfCompatible(Path partFile) {
        if (!Files.exists(statePath) || !Files.exists(partFile)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = MAPPER.readValue(statePath.toFile(), HashMap.class);
            String savedUrl = (String) raw.get("url");
            Number savedSize = (Number) raw.get("fileSize");
            Number savedMulti = (Number) raw.get("chunkSizeMulti");
            if (savedUrl == null || !savedUrl.equals(url)
                    || savedSize == null || savedSize.longValue() != fileSize
                    || savedMulti == null || savedMulti.intValue() != chunkSizeMulti) {
                return;
            }

            long partLen = Files.size(partFile);
            completedChunks.clear();
            subChunkMacs.clear();
            completedBytes = 0L;

            String bitsetB64 = (String) raw.get("completedBitset");
            if (bitsetB64 != null && !bitsetB64.isEmpty()) {
                BitSet loaded = BitSet.valueOf(Base64.getDecoder().decode(bitsetB64));
                for (int cid = loaded.nextSetBit(1); cid >= 0; cid = loaded.nextSetBit(cid + 1)) {
                    long offset = ChunkWriterManager.calculateChunkOffset(cid, chunkSizeMulti);
                    if (offset < fileSize) {
                        long cSize = ChunkWriterManager.calculateChunkSize(cid, fileSize, offset, chunkSizeMulti);
                        if (offset + cSize <= partLen) {
                            completedChunks.set(cid);
                            completedBytes += cSize;
                        }
                    }
                }
            }

            Object rawMacs = raw.get("subChunkMacs");
            if (rawMacs instanceof Map<?, ?> macMap) {
                for (Map.Entry<?, ?> e : macMap.entrySet()) {
                    try {
                        long id = Long.parseLong(String.valueOf(e.getKey()));
                        byte[] bytes = Base64.getDecoder().decode(String.valueOf(e.getValue()));
                        if (bytes.length == 16) {
                            subChunkMacs.put(id, bytes);
                        }
                    } catch (Exception ignore) {
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Ignoring corrupt download state file {0}: {1}", new Object[]{statePath, ex.getMessage()});
            completedChunks.clear();
            subChunkMacs.clear();
            completedBytes = 0L;
        }
    }

    public synchronized void persistNow() {
        persistLocked();
    }

    public synchronized void deleteStateFile() {
        try {
            Files.deleteIfExists(statePath);
        } catch (IOException ex) {
            LOG.log(Level.FINE, "Could not delete state file {0}: {1}", new Object[]{statePath, ex.getMessage()});
        }
    }

    private void persistLocked() {
        try {
            if (statePath.getParent() != null) {
                Files.createDirectories(statePath.getParent());
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("url", url);
            payload.put("fileSize", fileSize);
            payload.put("chunkSizeMulti", chunkSizeMulti);
            payload.put("completedBitset", Base64.getEncoder().encodeToString(completedChunks.toByteArray()));
            Map<String, String> macStrings = new HashMap<>();
            for (Map.Entry<Long, byte[]> e : subChunkMacs.entrySet()) {
                macStrings.put(String.valueOf(e.getKey()), Base64.getEncoder().encodeToString(e.getValue()));
            }
            payload.put("subChunkMacs", macStrings);

            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), payload);
            try {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
            lastPersistMs = System.currentTimeMillis();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to persist download state {0}: {1}", new Object[]{statePath, ex.getMessage()});
        }
    }
}
