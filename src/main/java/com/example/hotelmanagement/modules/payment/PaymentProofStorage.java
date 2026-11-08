package com.example.hotelmanagement.modules.payment;

import static com.example.hotelmanagement.modules.booking.OperationsStore.*;

import com.example.hotelmanagement.modules.booking.OperationsStore;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

@Component
@RequiredArgsConstructor
public class PaymentProofStorage {
    private final OperationsStore db;

    @Value("${app.payment.proof-directory:uploads/payments-private}")
    private String directory;

    public record Download(String type, byte[] bytes) {}

    public void attach(long payment, MultipartFile file) {
        if (file == null || file.isEmpty()) return;
        db.require(file.getSize() <= 5 * 1024 * 1024, "proof");
        try {
            byte[] input;
            try (var source = file.getInputStream()) {
                input = source.readNBytes(5 * 1024 * 1024 + 1);
            }
            db.require(input.length <= 5 * 1024 * 1024, "proof");
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(input));
            var prior =
                    db.one("SELECT receipt_sha256 FROM payment_transactions WHERE id=?", payment);
            if (prior.s("receipt_sha256") != null) {
                db.require(hash.equals(prior.s("receipt_sha256")), "idempotency");
                return;
            }
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
                var readers = ImageIO.getImageReaders(stream);
                db.require(readers.hasNext(), "proof");
                var reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    db.require(Set.of("png", "jpeg", "jpg").contains(format), "proof");
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    db.require(w > 0 && h > 0 && (long) w * h <= 16000000, "proof");
                    var image = reader.read(0);
                    String extension = format.equals("png") ? "png" : "jpg",
                            key = UUID.randomUUID().toString().replace("-", "") + "." + extension;
                    var output = new ByteArrayOutputStream();
                    db.require(ImageIO.write(image, extension, output), "proof");
                    db.require(output.size() <= 8 * 1024 * 1024, "proof");
                    Path path = path(key);
                    Files.createDirectories(path.getParent());
                    Files.write(path, output.toByteArray(), StandardOpenOption.CREATE_NEW);
                    TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                                @Override
                                public void afterCompletion(int status) {
                                    if (status != STATUS_COMMITTED)
                                        try {
                                            Files.deleteIfExists(path);
                                        } catch (IOException ignored) {
                                        }
                                }
                            });
                    db.update(
                            "UPDATE payment_transactions SET receipt_storage_key=?,receipt_sha256=?"
                                + " WHERE id=?",
                            key,
                            hash,
                            payment);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException | java.security.NoSuchAlgorithmException ex) {
            db.require(false, "proof");
        }
    }

    public Download read(String key) {
        try {
            return new Download(
                    key.endsWith(".png") ? "image/png" : "image/jpeg",
                    Files.readAllBytes(path(key)));
        } catch (IOException e) {
            db.require(false, "proof");
            throw new IllegalStateException();
        }
    }

    private Path path(String key) {
        db.require(key != null && key.matches("[a-f0-9]{32}\\.(png|jpg)"), "proof");
        return Path.of(directory).toAbsolutePath().normalize().resolve(key);
    }
}
