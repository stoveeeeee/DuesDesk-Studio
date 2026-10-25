package com.fraternity.dues;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Shrinks receipt photos before they are saved: anything wider than {@link #MAX_WIDTH} is scaled down (keeping its
 * proportions) and re-encoded as a JPEG at {@link #JPEG_QUALITY}. Text stays legible while a phone photo drops from
 * several megabytes to a few hundred kilobytes. Pure Java (ImageIO / Java2D), so it behaves the same on every OS.
 */
public final class ReceiptCompressor {
    private ReceiptCompressor() {}

    public static final int MAX_WIDTH = 1600;
    public static final float JPEG_QUALITY = 0.75f;

    /** The bytes to store and the file extension that matches them (".jpg" or the original ".png"). */
    public record Result(byte[] bytes, String extension, int width, int height) {}

    /**
     * @param original  the uploaded file
     * @param image     its decoded pixels
     * @param extension ".png" or ".jpg", matching {@code original}
     * @return the smaller of the original and the resized JPEG; a resized image is always used when the photo was too wide
     */
    public static Result compress(byte[] original, BufferedImage image, String extension) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        boolean tooWide = width > MAX_WIDTH;
        int targetWidth = tooWide ? MAX_WIDTH : width;
        int targetHeight = tooWide ? Math.max(1, Math.round(height * (MAX_WIDTH / (float) width))) : height;

        // JPEG has no transparency, so transparent areas are painted white (receipts are on paper anyway).
        BufferedImage rgb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.drawImage(image, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        byte[] jpeg = jpeg(rgb);
        if (tooWide || jpeg.length < original.length) return new Result(jpeg, ".jpg", targetWidth, targetHeight);
        return new Result(original, extension, width, height);
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
