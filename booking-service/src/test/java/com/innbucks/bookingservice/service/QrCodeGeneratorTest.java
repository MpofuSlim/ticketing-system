package com.innbucks.bookingservice.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeGeneratorTest {

    private final QrCodeGenerator generator = new QrCodeGenerator();

    @Test
    void toDataUri_returnsBase64PngDataUriThatDecodesBackToPayload() throws Exception {
        String payload = "20260502-12345A";
        String dataUri = generator.toDataUri(payload);

        assertNotNull(dataUri);
        assertTrue(dataUri.startsWith("data:image/png;base64,"),
                "expected data URI prefix, got: " + dataUri.substring(0, Math.min(40, dataUri.length())));

        // Decode the base64 → PNG bytes → BufferedImage → QR scan, and check
        // the payload round-trips. This guarantees we're actually writing a
        // valid scannable QR, not just any bytes.
        String base64 = dataUri.substring("data:image/png;base64,".length());
        byte[] png = Base64.getDecoder().decode(base64);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image, "decoded PNG should produce a BufferedImage");
        assertEquals(256, image.getWidth(), "default size should be 256px");
        assertEquals(256, image.getHeight(), "default size should be 256px");

        Result decoded = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))));
        assertEquals(payload, decoded.getText());
    }

    @Test
    void toDataUri_returnsNullForBlankPayloads() {
        assertNull(generator.toDataUri(null));
        assertNull(generator.toDataUri(""));
        assertNull(generator.toDataUri("   "));
    }

    @Test
    void toDataUri_respectsCustomSize() throws Exception {
        String dataUri = generator.toDataUri("hello", 128);
        byte[] png = Base64.getDecoder().decode(
                dataUri.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(128, image.getWidth());
        assertEquals(128, image.getHeight());
    }

    @Test
    void toDataUri_handlesUnicodePayloads() throws Exception {
        String payload = "ÄÖÜ-ñ-é-✓"; // ensures UTF-8 encoding hint is honoured
        String dataUri = generator.toDataUri(payload);
        byte[] png = Base64.getDecoder().decode(
                dataUri.substring("data:image/png;base64,".length()));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        Result decoded = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))));
        assertEquals(payload, decoded.getText());
    }
}
