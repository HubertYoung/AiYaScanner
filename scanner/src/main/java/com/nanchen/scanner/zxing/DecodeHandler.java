/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nanchen.scanner.zxing;

import android.graphics.Bitmap;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.nanchen.scanner.R;
import com.yanzhenjie.zbar.Image;
import com.yanzhenjie.zbar.ImageScanner;
import com.yanzhenjie.zbar.Symbol;
import com.yanzhenjie.zbar.SymbolSet;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

final class DecodeHandler extends Handler {

    private static final String TAG = DecodeHandler.class.getSimpleName();

    private final BaseCaptureActivity activity;
    private QRCodeReader qrCodeReader;
    private ViewfinderResultPointCallback callback;
    private boolean running = true;

    DecodeHandler(BaseCaptureActivity activity) {
        qrCodeReader = new QRCodeReader();
        this.activity = activity;
        this.callback = new ViewfinderResultPointCallback(activity.getViewfinderView());
    }

    @Override
    public void handleMessage(Message message) {
        if (message == null || !running) {
            return;
        }
        if (message.what == R.id.decode) {
            decode((byte[]) message.obj, message.arg1, message.arg2);

        } else if (message.what == R.id.quit) {
            running = false;
            Looper.myLooper().quit();

        }
    }

    /**
     * Decode the data within the viewfinder rectangle, and time how long it took. For efficiency,
     * reuse the same reader objects from one decode to the next.
     *
     * @param data   The YUV preview frame.
     * @param width  The width of the preview frame.
     * @param height The height of the preview frame.
     */
    private void decode(byte[] data, int width, int height) {
        Map<DecodeHintType, Object> hints = new HashMap<>();
        hints.put(DecodeHintType.POSSIBLE_FORMATS, BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.CHARACTER_SET, "utf-8");
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, callback);

        long start = System.currentTimeMillis();
        Result rawResult = null;

        String strResult = null;

        // zxing
        PlanarYUVLuminanceSource source = activity.getCameraManager().buildLuminanceSource(data, width, height);
        Log.d(TAG, "width:" + width + ",height:" + height + ",newWidth:" + source.getWidth() + ",newHeight:" + source.getHeight());
        BinaryBitmap globalBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
        try {
            rawResult = qrCodeReader.decode(globalBitmap, hints);
            if (rawResult != null)
                strResult = rawResult.getText();
        } catch (ReaderException re) {
            // continue
        } finally {
            qrCodeReader.reset();
        }
        // hybrid 解码
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        try {
            rawResult = qrCodeReader.decode(bitmap, hints);
            if (rawResult != null)
                strResult = rawResult.getText();
        } catch (ReaderException re) {
            // continue
        } finally {
            qrCodeReader.reset();
        }

        // zbar 解码
        Image barcode = new Image(width, height, "Y800");
        barcode.setData(data);
        Rect rect = activity.getCameraManager().getFramingRectInPreview();
        if (rect != null) {
            /* zbar 解码库,不需要将数据进行旋转,因此设置裁剪区域是的x为 top, y为left 设置了裁剪区域,解码速度快了近5倍左右 */
            barcode.setCrop(rect.top, rect.left, rect.width(), rect.height()); // 设置截取区域，也就是你的扫描框在图片上的区域.
        }
        ImageScanner mImageScanner = new ImageScanner();
        int result = mImageScanner.scanImage(barcode);
        if (result != 0) {
            SymbolSet symSet = mImageScanner.getResults();
            for (Symbol sym : symSet) {
                // 未能识别的格式继续遍历
                if (sym.getType() == Symbol.NONE) {
                    continue;
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                    strResult = new String(sym.getDataBytes(), StandardCharsets.UTF_8);
                } else {
                    strResult = sym.getData();
                }
            }
        }

        Handler handler = activity.getHandler();
        if (strResult != null) {
            // Don't log the barcode contents for security.
            long end = System.currentTimeMillis();
            Log.d(TAG, "Found barcode in " + (end - start) + " ms");
            if (handler != null) {
                Message message = Message.obtain(handler, R.id.decode_succeeded, strResult);
                if (rawResult != null) {
                    Bundle bundle = new Bundle();
                    bundleThumbnail(source, bundle);
                    // 绘制点点
                    for (ResultPoint points : rawResult.getResultPoints()) {
                        callback.foundPossibleResultPoint(points);
                    }
                    message.setData(bundle);
                }
                message.sendToTarget();
            }
        } else {
            if (handler != null) {
                Message message = Message.obtain(handler, R.id.decode_failed);
                message.sendToTarget();
            }
        }
    }


    private static void bundleThumbnail(PlanarYUVLuminanceSource source, Bundle bundle) {
        int[] pixels = source.renderThumbnail();
        int width = source.getThumbnailWidth();
        int height = source.getThumbnailHeight();
        Bitmap bitmap = Bitmap.createBitmap(pixels, 0, width, width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
        bundle.putByteArray(DecodeThread.BARCODE_BITMAP, out.toByteArray());
        bundle.putFloat(DecodeThread.BARCODE_SCALED_FACTOR, (float) width / source.getWidth());
    }

}
