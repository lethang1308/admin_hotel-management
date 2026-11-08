package com.example.hotelmanagement.modules.user.service;

import com.example.hotelmanagement.common.exception.BusinessException;
import com.example.hotelmanagement.modules.hotel.service.HotelMediaStorage;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;

@Component
@RequiredArgsConstructor
public class AvatarStorage {
    private final HotelMediaStorage media;

    public String save(MultipartFile file) {
        Path output = null;
        try {
            if (file == null || file.isEmpty() || file.getSize() > 5L * 1024 * 1024)
                throw invalid();
            byte[] bytes;
            try (var in = file.getInputStream()) {
                bytes = in.readNBytes(5 * 1024 * 1024 + 1);
            }
            if (bytes.length > 5 * 1024 * 1024) throw invalid();
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("png", "jpeg", "jpg", "webp").contains(format)) throw invalid();
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 1 || h < 1 || (long) w * h > 16000000) throw invalid();
                    BufferedImage image = reader.read(0);
                    // Re-encode and resize; never expose original upload metadata or filename.
                    int max = 1024, nw = w, nh = h;
                    if (Math.max(w, h) > max) {
                        double scale = (double) max / Math.max(w, h);
                        nw = Math.max(1, (int) (w * scale));
                        nh = Math.max(1, (int) (h * scale));
                    }
                    var normalized = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
                    var g = normalized.createGraphics();
                    try {
                        g.setRenderingHint(
                                java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(image, 0, 0, nw, nh, null);
                    } finally {
                        g.dispose();
                    }
                    Path root = media.root().resolve("avatars");
                    Files.createDirectories(root);
                    String name = UUID.randomUUID().toString().replace("-", "") + ".png";
                    output = root.resolve(name);
                    try (var out = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
                        if (!ImageIO.write(normalized, "png", out)) throw invalid();
                    }
                    Path owned = output;
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int state) {
                                    if (state != STATUS_COMMITTED)
                                        try {
                                            Files.deleteIfExists(owned);
                                        } catch (IOException ignored) {
                                        }
                                }
                            });
                    return "/media/hotel/avatars/" + name;
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (output != null)
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(
                "Ảnh phải là PNG, JPG hoặc WebP hợp lệ, tối đa 5 MB và 16 triệu điểm ảnh.");
    }
}
