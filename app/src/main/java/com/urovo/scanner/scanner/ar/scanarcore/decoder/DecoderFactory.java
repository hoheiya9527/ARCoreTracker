package com.urovo.scanner.scanner.ar.scanarcore.decoder;

import android.content.Context;

import com.urovo.scanner.scanner.ar.scanarcore.decoder.kyd.KydBarcodeDecoder;


/**
 * 解码器工厂
 */
public class DecoderFactory {

    public enum DecoderType {
        MLKIT,
        KYD
    }

    private DecoderFactory() {
    }

    /**
     * 创建解码器
     *
     * @param type    解码器类型
     * @param context 应用上下文
     * @return 解码器实例
     */
    public static BarcodeDecoder create(DecoderType type, Context context) {
        return new KydBarcodeDecoder(context);
    }
}
