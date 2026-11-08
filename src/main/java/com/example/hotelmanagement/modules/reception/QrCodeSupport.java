package com.example.hotelmanagement.modules.reception;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

@Component
public class QrCodeSupport {
    public String image(String payload) {
        try {
            var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 256, 256);
            var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 256; y++)
                for (int x = 0; x < 256; x++)
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("QR rendering failed", e);
        }
    }

    public String read(byte[] input) {
        if (input == null || input.length == 0 || input.length > 5 * 1024 * 1024)
            throw new IllegalArgumentException();
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName();
                if (!Set.of("png", "jpeg", "jpg").contains(format.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16000000)
                    throw new IllegalArgumentException();
                BufferedImage image = reader.read(0);
                int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
                return new MultiFormatReader()
                        .decode(
                                new BinaryBitmap(
                                        new HybridBinarizer(
                                                new RGBLuminanceSource(width, height, pixels))),
                                Map.of(
                                        DecodeHintType.POSSIBLE_FORMATS,
                                        List.of(BarcodeFormat.QR_CODE),
                                        DecodeHintType.TRY_HARDER,
                                        true))
                        .getText();
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid QR image");
        }
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
