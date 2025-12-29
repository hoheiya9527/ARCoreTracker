package com.urovo.scanner.scanner.ar.scanarcore.decoder.mlkit;

/**
 * MLKit条码解码器实现
 */
//public class MlkitBarcodeDecoder implements BarcodeDecoder {
//
//    private final BarcodeScanner scanner;
//
//    public MlkitBarcodeDecoder() {
//        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
//                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
//                .build();
//        this.scanner = BarcodeScanning.getClient(options);
//    }
//
//    @Override
//    public void decode(Bitmap bitmap, int rotationDegrees, DecodeCallback callback) {
//        if (bitmap == null) {
//            callback.onFailure(new IllegalArgumentException("Bitmap is null"));
//            return;
//        }
//
//        InputImage image = InputImage.fromBitmap(bitmap, rotationDegrees);
//        processImage(image, callback);
//    }
//
//    @Override
//    public void decode(Image image, int rotationDegrees, DecodeCallback callback) {
//        if (image == null) {
//            callback.onFailure(new IllegalArgumentException("Image is null"));
//            return;
//        }
//
//        // InputImage.fromMediaImage 会在内部复制必要的数据
//        // 所以创建 InputImage 后，原始 Image 可以被关闭
//        InputImage inputImage;
//        try {
//            inputImage = InputImage.fromMediaImage(image, rotationDegrees);
//        } catch (Exception e) {
//            callback.onFailure(e);
//            return;
//        }
//
//        processImage(inputImage, callback);
//    }
//
//    @Override
//    public void decode(byte[] jpegData, int rotationDegrees, DecodeCallback callback) {
//        if (jpegData == null || jpegData.length == 0) {
//            callback.onFailure(new IllegalArgumentException("JPEG data is null or empty"));
//            return;
//        }
//
//        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
//        if (bitmap == null) {
//            callback.onFailure(new RuntimeException("Failed to decode JPEG data"));
//            return;
//        }
//
//        decode(bitmap, rotationDegrees, callback);
//    }
//
//    private void processImage(InputImage image, DecodeCallback callback) {
//        scanner.process(image)
//                .addOnSuccessListener(barcodes -> {
//                    List<BarcodeResult> results = new ArrayList<>();
//                    for (Barcode barcode : barcodes) {
//                        BarcodeResult result = convertToResult(barcode);
//                        if (result != null) {
//                            results.add(result);
//                        }
//                    }
//                    callback.onSuccess(results);
//                })
//                .addOnFailureListener(callback::onFailure);
//    }
//
//    private BarcodeResult convertToResult(Barcode barcode) {
//        String content = barcode.getRawValue();
//        if (content == null || content.isEmpty()) {
//            content = barcode.getDisplayValue();
//        }
//        if (content == null) {
//            return null;
//        }
//
//        String format = getFormatName(barcode.getFormat());
//
//        // 边界框
//        Rect boundingBox = barcode.getBoundingBox();
//        RectF rectF = null;
//        PointF centerPoint = null;
//
//        if (boundingBox != null) {
//            rectF = new RectF(boundingBox);
//            centerPoint = new PointF(rectF.centerX(), rectF.centerY());
//        }
//
//        // 角点
//        Point[] corners = barcode.getCornerPoints();
//        PointF[] cornerPoints = null;
//        if (corners != null && corners.length > 0) {
//            cornerPoints = new PointF[corners.length];
//            float sumX = 0, sumY = 0;
//            for (int i = 0; i < corners.length; i++) {
//                cornerPoints[i] = new PointF(corners[i].x, corners[i].y);
//                sumX += corners[i].x;
//                sumY += corners[i].y;
//            }
//            // 使用角点中心作为更精确的中心点
//            centerPoint = new PointF(sumX / corners.length, sumY / corners.length);
//        }
//
//        return new BarcodeResult(content, format, centerPoint, rectF, cornerPoints);
//    }
//
//    private String getFormatName(int format) {
//        switch (format) {
//            case Barcode.FORMAT_QR_CODE:
//                return "QR_CODE";
//            case Barcode.FORMAT_EAN_13:
//                return "EAN_13";
//            case Barcode.FORMAT_EAN_8:
//                return "EAN_8";
//            case Barcode.FORMAT_UPC_A:
//                return "UPC_A";
//            case Barcode.FORMAT_UPC_E:
//                return "UPC_E";
//            case Barcode.FORMAT_CODE_128:
//                return "CODE_128";
//            case Barcode.FORMAT_CODE_39:
//                return "CODE_39";
//            case Barcode.FORMAT_CODE_93:
//                return "CODE_93";
//            case Barcode.FORMAT_CODABAR:
//                return "CODABAR";
//            case Barcode.FORMAT_ITF:
//                return "ITF";
//            case Barcode.FORMAT_DATA_MATRIX:
//                return "DATA_MATRIX";
//            case Barcode.FORMAT_PDF417:
//                return "PDF417";
//            case Barcode.FORMAT_AZTEC:
//                return "AZTEC";
//            default:
//                return "UNKNOWN";
//        }
//    }
//
//    @Override
//    public void release() {
//        scanner.close();
//    }
//}
