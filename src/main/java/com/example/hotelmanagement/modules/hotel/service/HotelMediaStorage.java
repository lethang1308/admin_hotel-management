package com.example.hotelmanagement.modules.hotel.service;

import com.example.hotelmanagement.common.util.CmsMessages;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;

@Service
public class HotelMediaStorage {
    private final Path root;
    private final CmsMessages messages;

    public HotelMediaStorage(
            @Value("${app.hotel.media-directory:uploads/hotel}") String configured,
            CmsMessages messages) {
        this.root =
                Path.of(configured == null || configured.isBlank() ? "uploads/hotel" : configured)
                        .toAbsolutePath()
                        .normalize();
        this.messages = messages;
    }

    public Path root() {
        return root;
    }

    public String validateUrl(String input) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        if (value.length() > 500) throw messages.error("hotel.error.imageUrl");
        if (value.matches("/media/hotel/[a-f0-9]{32}\\.(?:png|jpg)")) return value;
        try {
            URI uri = URI.create(value);
            if (("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null) return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
        }
        throw messages.error("hotel.error.imageUrl");
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() > 5 * 1024 * 1024) throw messages.error("hotel.error.imageFile");
        Path output = null;
        try (InputStream source = file.getInputStream();
                ImageInputStream stream = ImageIO.createImageInputStream(source)) {
            if (stream == null) throw messages.error("hotel.error.imageFile");
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw messages.error("hotel.error.imageFile");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("jpeg", "jpg", "png").contains(format))
                    throw messages.error("hotel.error.imageFile");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000)
                    throw messages.error("hotel.error.imageFile");
                var image = reader.read(0);
                String extension = format.equals("png") ? "png" : "jpg";
                Files.createDirectories(root);
                String name = UUID.randomUUID().toString().replace("-", "") + "." + extension;
                output = root.resolve(name);
                if (!ImageIO.write(image, extension, output.toFile()))
                    throw messages.error("hotel.error.imageFile");
                Path owned = output;
                if (TransactionSynchronizationManager.isSynchronizationActive())
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    if (status != STATUS_COMMITTED)
                                        try {
                                            Files.deleteIfExists(owned);
                                        } catch (IOException ignored) {
                                        }
                                }
                            });
                return "/media/hotel/" + name;
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            if (output != null)
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            throw messages.error("hotel.error.imageFile");
        }
    }
}
