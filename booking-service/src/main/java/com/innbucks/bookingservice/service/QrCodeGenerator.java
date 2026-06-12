package com.innbucks.bookingservice.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Renders short strings (ticket numbers, confirmation numbers, …) into
 * base64-encoded PNG QR codes wrapped as a data URI so the frontend can
 * drop the value straight into {@code <img src={…} />}.
 *
 * <p>QR settings: 256×256, error-correction level M (~15% damage tolerance,
 * fine for tickets that may be screen-shared / printed at low DPI), UTF-8
 * payload encoding, 1-module quiet zone (vs. the spec's 4) to keep the
 * image tight in mobile UIs.
 */
@Component
@Slf4j
public class QrCodeGenerator {

    private static final int DEFAULT_SIZE = 256;
    private static final String DATA_URI_PREFIX = "data:image/png;base64,";

    public String toDataUri(String payload) {
        return toDataUri(payload, DEFAULT_SIZE);
    }

    public String toDataUri(String payload, int sizePx) {
        byte[] png = toPngBytes(payload, sizePx);
        return png == null ? null : DATA_URI_PREFIX + Base64.getEncoder().encodeToString(png);
    }

    /** Raw PNG bytes for the payload, or null on failure. Used by the hosted
     *  ticket-QR endpoint so email/WhatsApp can reference a real image URL
     *  (data-URIs are stripped by Gmail/Outlook). */
    public byte[] toPngBytes(String payload) {
        return toPngBytes(payload, DEFAULT_SIZE);
    }

    public byte[] toPngBytes(String payload, int sizePx) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1
            );
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            // QR generation failing for a string short enough to fit a ticket
            // number is a programmer error, but degrade gracefully — the
            // booking is still valid, just without a scan code.
            log.warn("QR generation failed payload={} cause={}", payload, e.toString());
            return null;
        }
    }
}
