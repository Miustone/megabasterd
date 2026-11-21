package com.tonikelope.megabasterd;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * Generates thumbnails for uploaded images and (when {@code ffmpeg} is on PATH)
 * video files, without relying on the obsolete native Xuggler binary.
 */
public class Thumbnailer {

    private static final Logger LOG = Logger.getLogger(Thumbnailer.class.getName());

    public static final int IMAGE_THUMB_SIZE = 250;

    public Thumbnailer() {
    }

    public String createThumbnail(String filename) {
        try {
            if (MiscTools.isVideoFile(filename)) {
                return createVideoThumbnail(filename);
            } else if (MiscTools.isImageFile(filename)) {
                return createImageThumbnail(filename);
            }
        } catch (Exception ex) {
            LOG.log(Level.FINE, "Thumbnail generation skipped for {0}: {1}", new Object[]{filename, ex.getMessage()});
        }
        return null;
    }

    private String createImageThumbnail(String filename) {
        try {
            BufferedImage original = ImageIO.read(new File(filename));
            if (original == null) {
                return null;
            }
            if (original.getHeight() <= IMAGE_THUMB_SIZE) {
                return filename;
            }
            int h = IMAGE_THUMB_SIZE;
            int w = Math.max(1, Math.round((((float) original.getWidth()) * h) / original.getHeight()));
            int targetType = original.getType();
            if (targetType == BufferedImage.TYPE_CUSTOM) {
                targetType = BufferedImage.TYPE_INT_ARGB;
            }

            BufferedImage resized = new BufferedImage(w, h, targetType);
            Graphics2D g = resized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(original, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }

            File outFile = File.createTempFile("megabasterd_thumbnail_" + MiscTools.genID(20), ".png");
            outFile.deleteOnExit();
            ImageIO.write(resized, "png", outFile);
            return outFile.getAbsolutePath();
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "Failed to create image thumbnail for {0}: {1}", new Object[]{filename, ex.getMessage()});
            return null;
        }
    }

    private String createVideoThumbnail(String filename) {
        File outFile = null;
        try {
            outFile = File.createTempFile("megabasterd_thumbnail_" + MiscTools.genID(20), ".jpg");
            outFile.deleteOnExit();

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-ss", "00:00:03", "-i", filename,
                    "-frames:v", "1", "-vf", "scale=-1:" + IMAGE_THUMB_SIZE,
                    outFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            try (var in = proc.getInputStream()) {
                in.readAllBytes();
            }
            if (proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0 && outFile.length() > 0) {
                return outFile.getAbsolutePath();
            }
            outFile.delete();
        } catch (Exception ex) {
            if (outFile != null) {
                outFile.delete();
            }
            LOG.log(Level.FINE, "Video thumbnail skipped (ffmpeg not available or failed): {0}", ex.getMessage());
        }
        return null;
    }
}
