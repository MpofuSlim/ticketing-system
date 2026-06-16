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
    void toPngBytes_isAtLeast8BitDepth_soWhatsAppAcceptsTheMedia() {
        // WhatsApp's media validator rejects sub-8-bit PNGs with Twilio error
        // 63021 ("channel invalid content"). ZXing's MatrixToImageWriter would
        // emit a 1-bit TYPE_BYTE_BINARY PNG for default black/white; we render
        // TYPE_INT_RGB instead. Guard the bit depth so this can't regress.
        //
        // PNG layout: 8-byte signature, then the IHDR chunk
        // (4 len + 4 "IHDR" + 4 width + 4 height + 1 BIT-DEPTH + 1 COLOUR-TYPE).
        // So bit depth is at byte offset 24, colour type at 25.
        byte[] png = generator.toPngBytes("20260502-12345A");
        assertNotNull(png);
        int bitDepth = png[24] & 0xFF;
        int colourType = png[25] & 0xFF;
        assertTrue(bitDepth >= 8,
                "PNG bit depth must be >= 8 (WhatsApp rejects 1-bit with 63021); was " + bitDepth);
        // Colour type 2 = truecolour RGB (what TYPE_INT_RGB produces); never 0
        // (greyscale) at 1-bit, which was the failing case.
        assertEquals(2, colourType, "expected truecolour RGB PNG (colour type 2)");
    }

    @Test
    void toPngBytes_isStillScannableAfterTheRgbChange() throws Exception {
        // The colour-depth fix must not break scannability.
        String payload = "20260616-96464V";
        byte[] png = generator.toPngBytes(payload);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
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
