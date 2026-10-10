package com.ncmbreaker.ui.account;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.util.Map;

final class QrCodeImage {
    private QrCodeImage() {
    }

    static BufferedImage create(String content, int size) throws WriterException {
        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, Map.of(
                EncodeHintType.CHARACTER_SET, "UTF-8",
                EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN, 4));
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }
}
