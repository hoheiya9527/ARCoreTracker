package com.urovo.scanner.scanner.ar.scanarcore.detector;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.media.Image;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.urovo.scanner.scanner.ar.scanarcore.decoder.BarcodeDecoder;
import com.urovo.scanner.scanner.ar.scanarcore.decoder.BarcodeResult;
import com.urovo.scanner.scanner.ar.scanarcore.decoder.DecoderFactory;
import com.urovo.scanner.scanner.ar.scanarcore.model.BarcodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 条码检测识别
 */
@androidx.camera.core.ExperimentalGetImage
public class BarcodeDetector {

    private final BarcodeDecoder scanner;
    private DetectionCallback callback;

    public interface DetectionCallback {
        void onBarcodesDetected(@NonNull List<BarcodeInfo> barcodes);

        void onDetectionFailed(@NonNull Exception e);
    }

    public BarcodeDetector(Context context) {
        this.scanner = DecoderFactory.create(DecoderFactory.DecoderType.KYD, context);
    }

    public void setCallback(DetectionCallback callback) {
        this.callback = callback;
    }

    /**
     * 处理相机帧，检测条码
     */
    @androidx.camera.core.ExperimentalGetImage
    public void processImage(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();

        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
        long timestamp = System.currentTimeMillis();

        scanner.decode(mediaImage, rotationDegrees, new BarcodeDecoder.DecodeCallback() {
            @Override
            public void onSuccess(List<BarcodeResult> results) {
                List<BarcodeInfo> barcodeInfoList = convertToBarcodeInfo(results, timestamp);
                if (callback != null) {
                    callback.onBarcodesDetected(barcodeInfoList);
                }
                imageProxy.close();
            }

            @Override
            public void onFailure(Exception e) {
                if (callback != null) {
                    callback.onDetectionFailed(e);
                }
                imageProxy.close();
            }
        });
    }

    /**
     * 转换解码结果为BarcodeInfo列表
     */
    private List<BarcodeInfo> convertToBarcodeInfo(List<BarcodeResult> results, long timestamp) {
        List<BarcodeInfo> barcodeInfoList = new ArrayList<>();

        for (BarcodeResult result : results) {
            String content = result.getContent();
            if (content == null || content.isEmpty()) {
                continue;
            }

            // 转换角点：PointF[] -> Point[]
            Point[] cornerPoints = null;
            PointF[] srcCorners = result.getCornerPoints();
            if (srcCorners != null && srcCorners.length == 4) {
                cornerPoints = new Point[4];
                for (int i = 0; i < 4; i++) {
                    cornerPoints[i] = new Point((int) srcCorners[i].x, (int) srcCorners[i].y);
                }
            }

            // format字符串转换为int（BarcodeInfo使用int类型）
            int format = mapFormatStringToInt(result.getFormat());

            BarcodeInfo info = new BarcodeInfo(content, format, cornerPoints, timestamp);
            barcodeInfoList.add(info);
        }

        return barcodeInfoList;
    }

    /**
     * 将格式字符串映射为整数值
     */
    private int mapFormatStringToInt(String format) {
        if (format == null) {
            return -1;
        }
        switch (format) {
            case "QR_CODE":
                return 256;
            case "DATA_MATRIX":
                return 16;
            case "CODE_39":
                return 2;
            case "CODE_128":
                return 1;
            case "CODE_93":
                return 4;
            case "EAN_13":
                return 32;
            case "EAN_8":
                return 64;
            case "UPC_A":
                return 512;
            case "UPC_E":
                return 1024;
            case "PDF417":
                return 2048;
            case "AZTEC":
                return 4096;
            case "ITF":
                return 128;
            case "CODABAR":
                return 8;
            default:
                return -1;
        }
    }

    /**
     * 转换ML Kit的Barcode对象为BarcodeInfo
     */
//    private List<BarcodeInfo> convertToBarcodeInfo(List<Barcode> barcodes, long timestamp) {
//        List<BarcodeInfo> result = new ArrayList<>();
//
//        for (Barcode barcode : barcodes) {
//            String rawValue = barcode.getRawValue();
//            if (rawValue == null || rawValue.isEmpty()) {
//                continue;
//            }
//
//            BarcodeInfo info = new BarcodeInfo(
//                    rawValue,
//                    barcode.getFormat(),
//                    barcode.getCornerPoints(),
//                    timestamp
//            );
//
//            result.add(info);
//        }
//
//        return result;
//    }

    /**
     * 释放资源
     */
    public void release() {
        scanner.release();
    }
}
