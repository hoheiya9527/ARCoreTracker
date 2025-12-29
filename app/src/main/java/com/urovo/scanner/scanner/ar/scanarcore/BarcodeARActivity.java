package com.urovo.scanner.scanner.ar.scanarcore;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.InstantPlacementPoint;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.urovo.scanner.scanner.ar.scanarcore.decoder.BarcodeDecoder;
import com.urovo.scanner.scanner.ar.scanarcore.decoder.BarcodeResult;
import com.urovo.scanner.scanner.ar.scanarcore.decoder.DecoderFactory;
import com.urovo.scanner.scanner.ar.scanarcore.renderer.BackgroundRenderer;
import com.urovo.scanner.scanner.ar.scanarcore.renderer.PointRenderer;
import com.urovo.scanner.scanner.ar.scanarcore.util.ImageEnhancer;
import com.urovo.scanner.scanner.ar.scanarcore.util.PicUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 纯ARCore实现的条码AR追踪Activity
 */
public class BarcodeARActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "BarcodeARActivity";
    private static final int CAMERA_PERMISSION_REQUEST = 100;

    // SharedPreferences 配置
    private static final String PREFS_NAME = "ar_barcode_prefs";
    private static final String KEY_PLANE_DETECTION = "plane_detection_enabled";
    private static final double BLUR_THRESHOLD = 100;//图片清晰度要求

    private GLSurfaceView glSurfaceView;
    private TextView statusTextView;  // 状态提示
    private Switch planeDetectionSwitch;  // 平面检测开关
    private Session session;
    private boolean userRequestedInstall = true;
    private boolean planeDetected = false;  // 平面是否已检测到
    private volatile boolean usePlaneDetection = false;  // 是否使用平面检测模式

    // 背景渲染
    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final PointRenderer pointRenderer = new PointRenderer();

    private long lastScanTime = 0;
    private static final long SCAN_INTERVAL_MS = 200; // 扫描间隔

    // 锚点管理 - 使用同步锁保证原子操作
    private final CopyOnWriteArrayList<AnchorData> anchors = new CopyOnWriteArrayList<>();
    private final Set<String> registeredBarcodes = new HashSet<>(); // 已注册的条码（包括pending和已创建）
    private final Object barcodeLock = new Object();
    private static final int MAX_ANCHORS = 50;
    // 锚点创建间隔（毫秒）- 避免短时间内创建过多锚点导致 ARCore 崩溃
//    private long lastAnchorCreateTime = 0;
//    private static final long ANCHOR_CREATE_INTERVAL_MS = 300;
    // 锚点颜色
    private static final float[] ANCHOR_COLOR_TRACKING = {0.0f, 1.0f, 0.0f, 1.0f}; // 绿色 - 正常追踪
    private static final float[] ANCHOR_COLOR_STOPPED = {1.0f, 1.0f, 0.0f, 0.8f}; // 黄色 - 失效
    private static final float ANCHOR_SIZE = 80.0f;

    // 投影矩阵
    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];

    // 当前帧（在 onDrawFrame 中更新，供其他方法使用）
    private Frame currentFrame;

    // 图像尺寸（用于坐标转换）
    private int imageWidth = 0;
    private int imageHeight = 0;

    // 条码解码器
    private BarcodeDecoder barcodeDecoder;

    /**
     * 锚点数据
     */
    private static class AnchorData {
        final Anchor anchor;
        final String barcodeValue;
        final long createTime;
        // 当前帧的屏幕坐标（每帧更新）
        float screenX;
        float screenY;
        // 最后一次有效追踪的位置
        float lastValidScreenX;
        float lastValidScreenY;
        // 是否来自 InstantPlacementPoint
        boolean isInstantPlacement;
        // 是否已升级到 FULL_TRACKING
        boolean isFullTracking;

        AnchorData(Anchor anchor, String barcodeValue, boolean isInstantPlacement) {
            this.anchor = anchor;
            this.barcodeValue = barcodeValue;
            this.createTime = System.currentTimeMillis();
            this.isInstantPlacement = isInstantPlacement;
            this.isFullTracking = !isInstantPlacement; // 平面锚点默认就是 FULL_TRACKING
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 创建根布局
        FrameLayout rootLayout = new FrameLayout(this);

        // 初始化GLSurfaceView
        glSurfaceView = new GLSurfaceView(this);
        glSurfaceView.setPreserveEGLContextOnPause(true);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glSurfaceView.setRenderer(this);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        rootLayout.addView(glSurfaceView);

        // 添加状态提示文本
        statusTextView = new TextView(this);
        statusTextView.setText(R.string.ar_detecting_plane);
        statusTextView.setTextSize(18);
        statusTextView.setTextColor(0xFFFFFFFF);
        statusTextView.setBackgroundColor(0xAA000000);
        statusTextView.setPadding(40, 20, 40, 20);
        statusTextView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        statusParams.topMargin = 100;
        rootLayout.addView(statusTextView, statusParams);

        // 添加清除按钮
        Button clearButton = new Button(this);
        clearButton.setText("清除");
        clearButton.setTextSize(16);
        clearButton.setTextColor(0xFFFFFFFF);
        clearButton.setAllCaps(false);

        // 圆角背景
        android.graphics.drawable.GradientDrawable buttonBg = new android.graphics.drawable.GradientDrawable();
        buttonBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        buttonBg.setCornerRadius(50);
        buttonBg.setColor(0xAAE53935); // 半透明红色
        clearButton.setBackground(buttonBg);
        clearButton.setPadding(60, 30, 60, 30);
        clearButton.setElevation(8);

        clearButton.setOnClickListener(v -> glSurfaceView.queueEvent(this::clearAllAnchors));
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        buttonParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        buttonParams.bottomMargin = 200;
        rootLayout.addView(clearButton, buttonParams);

        // 添加平面检测开关（右上角）
        planeDetectionSwitch = new Switch(this);
        planeDetectionSwitch.setText(R.string.ar_plane_detection);
        planeDetectionSwitch.setTextColor(0xFFFFFFFF);
        planeDetectionSwitch.setPadding(20, 10, 20, 10);
        android.graphics.drawable.GradientDrawable switchBg = new android.graphics.drawable.GradientDrawable();
        switchBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        switchBg.setCornerRadius(10);
        switchBg.setColor(0x88000000);
        planeDetectionSwitch.setBackground(switchBg);

        // 从 SharedPreferences 读取持久化的开关状态
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean savedPlaneDetection = prefs.getBoolean(KEY_PLANE_DETECTION, false);
        usePlaneDetection = savedPlaneDetection;
        planeDetectionSwitch.setChecked(savedPlaneDetection);

        // 根据初始状态设置 UI
        if (savedPlaneDetection) {
            planeDetected = false;
            statusTextView.setVisibility(android.view.View.VISIBLE);
            statusTextView.setText(R.string.ar_detecting_plane);
        } else {
            planeDetected = true;
            statusTextView.setVisibility(android.view.View.GONE);
        }

        planeDetectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            usePlaneDetection = isChecked;
            // 持久化开关状态
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PLANE_DETECTION, isChecked)
                    .apply();

            if (isChecked) {
                // 开启平面检测模式
                planeDetected = false;
                statusTextView.setVisibility(android.view.View.VISIBLE);
                statusTextView.setText(R.string.ar_detecting_plane);
                reconfigureSession();
            } else {
                // 关闭平面检测模式，使用即时放置
                planeDetected = true;  // 跳过平面检测等待
                statusTextView.setVisibility(android.view.View.GONE);
                reconfigureSession();
            }
            // 清除现有锚点
            glSurfaceView.queueEvent(this::clearAllAnchors);
        });
        FrameLayout.LayoutParams switchParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        switchParams.gravity = Gravity.TOP | Gravity.END;
        switchParams.topMargin = 50;
        switchParams.rightMargin = 30;
        rootLayout.addView(planeDetectionSwitch, switchParams);

        // 状态提示可见性已在上面根据持久化状态设置

        setContentView(rootLayout);

        // 初始化解码器
        barcodeDecoder = DecoderFactory.create(DecoderFactory.DecoderType.KYD, this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "onResume called, session=" + session);

        if (!checkCameraPermission()) {
            Log.i(TAG, "Requesting camera permission");
            requestCameraPermission();
            return;
        }

        if (session == null) {
            try {
                // 只在第一次请求安装
                if (userRequestedInstall) {
                    Log.i(TAG, "Checking ARCore install status...");
                    ArCoreApk.InstallStatus installStatus = ArCoreApk.getInstance().requestInstall(this, true);
                    Log.i(TAG, "ARCore install status: " + installStatus);

                    if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                        Log.i(TAG, "ARCore install requested, waiting...");
                        userRequestedInstall = false;
                        return;
                    }
                }

                // 直接尝试创建Session
                Log.i(TAG, "Creating ARCore session...");
                session = new Session(this);
                Log.i(TAG, "Session created: " + session);

                // 选择最高分辨率的相机配置
                CameraConfigFilter filter = new CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK);
                List<CameraConfig> cameraConfigs = session.getSupportedCameraConfigs(filter);

                // 按CPU图像分辨率降序排序，选择最高的
                CameraConfig bestConfig = null;
                int maxPixels = 0;
                for (CameraConfig config : cameraConfigs) {
                    int pixels = config.getImageSize().getWidth() * config.getImageSize().getHeight();
                    Log.i(TAG, "CameraConfig: " + config.getImageSize().getWidth() + "x" + config.getImageSize().getHeight());
                    if (pixels > maxPixels) {
                        maxPixels = pixels;
                        bestConfig = config;
                    }
                }
                if (bestConfig != null) {
                    session.setCameraConfig(bestConfig);
                    Log.i(TAG, "Selected camera config: " + bestConfig.getImageSize().getWidth() + "x" + bestConfig.getImageSize().getHeight());
                }

                Config config = new Config(session);
                config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                // 默认使用即时放置模式
                config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);
                session.configure(config);

                Log.i(TAG, "ARCore session configured successfully");

            } catch (UnavailableUserDeclinedInstallationException e) {
                Log.e(TAG, "User declined ARCore installation", e);
                Toast.makeText(this, "需要安装ARCore", Toast.LENGTH_LONG).show();
                finish();
                return;
            } catch (Exception e) {
                Log.e(TAG, "ARCore init error: " + e.getClass().getName() + " - " + e.getMessage(), e);
                Toast.makeText(this, "ARCore错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        }

        try {
            Log.i(TAG, "Resuming session...");
            session.resume();
            Log.i(TAG, "Session resumed");
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
            Toast.makeText(this, "相机不可用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        glSurfaceView.onResume();
        Log.i(TAG, "GLSurfaceView resumed");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (session != null) {
            glSurfaceView.onPause();
            session.pause();
        }
    }

    /**
     * 重新配置 ARCore Session
     * 根据 usePlaneDetection 切换即时放置/平面检测模式
     */
    private void reconfigureSession() {
        if (session == null) return;

        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
        // 始终启用即时放置作为回退
        config.setInstantPlacementMode(Config.InstantPlacementMode.LOCAL_Y_UP);

        if (usePlaneDetection) {
            // 平面检测模式：开启平面检测
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
            Log.i(TAG, "Switched to Plane Detection mode (with fallback)");
        } else {
            // 即时放置模式：关闭平面检测以节省性能
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            Log.i(TAG, "Switched to Instant Placement mode");
        }

        session.configure(config);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 清理所有锚点
        for (AnchorData data : anchors) {
            data.anchor.detach();
        }
        anchors.clear();
        synchronized (barcodeLock) {
            registeredBarcodes.clear();
        }

        // 释放解码器
        if (barcodeDecoder != null) {
            barcodeDecoder.release();
            barcodeDecoder = null;
        }

        if (session != null) {
            session.close();
            session = null;
        }
    }

    // GLSurfaceView.Renderer 实现

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        backgroundRenderer.createOnGlThread(this);
        pointRenderer.createOnGlThread(this);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        if (session != null) {
            session.setDisplayGeometry(getWindowManager().getDefaultDisplay().getRotation(), width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());
            currentFrame = session.update();
            Camera camera = currentFrame.getCamera();

            // 绘制相机背景
            backgroundRenderer.draw(currentFrame);

            // 检查追踪状态
            if (camera.getTrackingState() == TrackingState.TRACKING) {
                // 获取投影和视图矩阵
                camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);
                camera.getViewMatrix(viewMatrix, 0);

                // 平面检测模式下，需要等待平面检测完成
                if (usePlaneDetection && !planeDetected) {
                    checkPlaneDetection(currentFrame);
                }

                // 即时放置模式或平面检测完成后，开始条码识别
                if (!usePlaneDetection || planeDetected) {
                    long now = System.currentTimeMillis();
                    if (now - lastScanTime > SCAN_INTERVAL_MS) {
                        lastScanTime = now;
                        scanBarcodesFromFrame(currentFrame);
                    }
                }

                // 渲染所有锚点
                renderAnchors();
            }
        } catch (CameraNotAvailableException e) {
            Log.e(TAG, "Camera not available", e);
        }
    }

    /**
     * 检测是否有可用平面
     */
    private void checkPlaneDetection(Frame frame) {
        for (Plane plane : session.getAllTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING) {
                planeDetected = true;
                Log.i(TAG, "Plane detected, barcode scanning enabled");
                runOnUiThread(() -> {
                    statusTextView.setText(R.string.ar_plane_detected);
                    // 2秒后隐藏提示
                    statusTextView.postDelayed(() -> statusTextView.setVisibility(android.view.View.GONE), 2000);
                });
                break;
            }
        }
    }

    /**
     * 渲染所有锚点
     * STOPPED状态显示黄色，TRACKING状态显示绿色，不自动删除
     */
    private void renderAnchors() {
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        for (AnchorData data : anchors) {
            Anchor anchor = data.anchor;
            TrackingState state = anchor.getTrackingState();

            try {
                Pose pose = anchor.getPose();
                float[] position = new float[]{pose.tx(), pose.ty(), pose.tz()};

                // 根据状态选择颜色：TRACKING绿色，其他黄色
                float[] color = (state == TrackingState.TRACKING) ? ANCHOR_COLOR_TRACKING : ANCHOR_COLOR_STOPPED;
                pointRenderer.draw(position, viewMatrix, projectionMatrix, color, ANCHOR_SIZE);

                // 更新屏幕坐标
                float[] screenPos = worldToScreen(position, viewWidth, viewHeight);
                data.screenX = screenPos[0];
                data.screenY = screenPos[1];
                if (state == TrackingState.TRACKING) {
                    data.lastValidScreenX = screenPos[0];
                    data.lastValidScreenY = screenPos[1];
                }
            } catch (Exception e) {
                // 锚点获取位置失败，跳过渲染但不删除
            }
        }
    }

    /**
     * 清除所有锚点 - 由用户手动触发
     */
    private void clearAllAnchors() {
        for (AnchorData data : anchors) {
            data.anchor.detach();
        }
        anchors.clear();
        synchronized (barcodeLock) {
            registeredBarcodes.clear();
        }
        Log.i(TAG, "All anchors cleared by user");
        runOnUiThread(() -> Toast.makeText(this, "已清除所有锚点", Toast.LENGTH_SHORT).show());
    }

    /**
     * 将3D世界坐标投影到2D屏幕坐标
     */
    private float[] worldToScreen(float[] worldPos, int viewWidth, int viewHeight) {
        float[] viewPos = new float[4];
        float[] projPos = new float[4];

        // 应用视图矩阵
        viewPos[0] = viewMatrix[0] * worldPos[0] + viewMatrix[4] * worldPos[1] + viewMatrix[8] * worldPos[2] + viewMatrix[12];
        viewPos[1] = viewMatrix[1] * worldPos[0] + viewMatrix[5] * worldPos[1] + viewMatrix[9] * worldPos[2] + viewMatrix[13];
        viewPos[2] = viewMatrix[2] * worldPos[0] + viewMatrix[6] * worldPos[1] + viewMatrix[10] * worldPos[2] + viewMatrix[14];
        viewPos[3] = 1.0f;

        // 应用投影矩阵
        projPos[0] = projectionMatrix[0] * viewPos[0] + projectionMatrix[4] * viewPos[1] + projectionMatrix[8] * viewPos[2] + projectionMatrix[12] * viewPos[3];
        projPos[1] = projectionMatrix[1] * viewPos[0] + projectionMatrix[5] * viewPos[1] + projectionMatrix[9] * viewPos[2] + projectionMatrix[13] * viewPos[3];
        projPos[2] = projectionMatrix[2] * viewPos[0] + projectionMatrix[6] * viewPos[1] + projectionMatrix[10] * viewPos[2] + projectionMatrix[14] * viewPos[3];
        projPos[3] = projectionMatrix[3] * viewPos[0] + projectionMatrix[7] * viewPos[1] + projectionMatrix[11] * viewPos[2] + projectionMatrix[15] * viewPos[3];

        // 透视除法，转换到NDC
        if (projPos[3] != 0) {
            projPos[0] /= projPos[3];
            projPos[1] /= projPos[3];
        }

        // NDC到屏幕坐标
        float screenX = (projPos[0] + 1.0f) * 0.5f * viewWidth;
        float screenY = (1.0f - projPos[1]) * 0.5f * viewHeight;

        return new float[]{screenX, screenY};
    }

    /**
     * 检查指定屏幕坐标是否与已有锚点位置重叠
     *
     * @param screenX   屏幕X坐标
     * @param screenY   屏幕Y坐标
     * @param threshold 距离阈值（像素）
     * @return true表示位置已被占用
     */
    private boolean isScreenPositionOccupied(float screenX, float screenY, float threshold) {
        for (AnchorData data : anchors) {
            // 使用最后有效位置进行比较（即使锚点暂停/停止也参与去重）
            float existingX = data.lastValidScreenX != 0 ? data.lastValidScreenX : data.screenX;
            float existingY = data.lastValidScreenY != 0 ? data.lastValidScreenY : data.screenY;

            float dx = existingX - screenX;
            float dy = existingY - screenY;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            Log.d(TAG, "Position check: new(" + String.format("%.1f", screenX) + "," + String.format("%.1f", screenY) + ") vs existing[" + data.barcodeValue + "](" + String.format("%.1f", existingX) + "," + String.format("%.1f", existingY) + ") distance=" + String.format("%.1f", distance) + " threshold=" + String.format("%.1f", threshold));
            if (distance < threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从ARCore Frame中扫描条码
     * 关键：在 GL 线程中同步提取 YUV 数据并立即关闭 Image，避免 native 内存竞态
     */
    private void scanBarcodesFromFrame(Frame frame) {
        Image image = null;
        try {
            image = frame.acquireCameraImage();
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                image.close();
                return;
            }

            // 保存图像尺寸
            final int width = image.getWidth();
            final int height = image.getHeight();
            imageWidth = width;
            imageHeight = height;

            // 关键：在 GL 线程中同步提取 YUV 数据
            // 这样可以确保 Image 在同一帧内被关闭，避免 native 内存被 ARCore 重用导致数据损坏
            final byte[] yuvData;
            try {
                yuvData = PicUtil.imageToYUV(image, null);
            } finally {
                // 立即关闭 Image，不管提取是否成功
                image.close();
                image = null;
            }

            if (yuvData == null) {
                Log.w(TAG, "Failed to extract YUV data from image");
                return;
            }

            // 模糊检测：使用 ImageEnhancer 的方法检测图像清晰度
            if (!ImageEnhancer.isSharp(yuvData, width, height, BLUR_THRESHOLD)) {
                return; // 图像模糊，跳过解码
            }

            // 异步解码 YUV 数据（此时 Image 已关闭，使用的是 Java 堆内存中的数据）
            barcodeDecoder.decodeYuv(yuvData, width, height, 0, new BarcodeDecoder.DecodeCallback() {
                @Override
                public void onSuccess(List<BarcodeResult> results) {
                    if (!results.isEmpty()) {
                        onBarcodesDetected(results);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Barcode scan failed", e);
                }
            });

        } catch (NotYetAvailableException e) {
            // 图像还没准备好，忽略
            if (image != null) {
                image.close();
            }
        } catch (Exception e) {
            // 其他异常，确保 Image 被关闭
            Log.e(TAG, "scanBarcodesFromFrame error", e);
            if (image != null) {
                image.close();
            }
        }
    }

    /**
     * 条码检测回调
     * 每帧只为一个新条码创建锚点，避免瞬间创建过多锚点导致 ARCore 崩溃
     */
    private void onBarcodesDetected(List<BarcodeResult> results) {
        if (results.isEmpty() || currentFrame == null) {
            return;
        }

        Log.i(TAG, "Detected " + results.size() + " barcodes in this frame");

        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();

        // 找出第一个需要创建锚点的新条码
        BarcodeResult targetResult = null;
        for (BarcodeResult result : results) {
            String value = result.getContent();
            if (value == null || value.isEmpty()) continue;

            // 检查是否已有锚点
            if (findAnchorByValue(value) != null) {
                continue; // 已有锚点，跳过
            }

            // 检查是否已注册（正在创建中）
            synchronized (barcodeLock) {
                if (registeredBarcodes.contains(value)) {
                    continue; // 已注册，跳过
                }
            }

            // 找到第一个需要创建锚点的条码
            targetResult = result;
            break;
        }

        if (targetResult == null) {
            return; // 所有条码都已有锚点
        }

        // 获取条码边界框（原始图像坐标）
        android.graphics.RectF bounds = targetResult.getBoundingBox();
        if (bounds == null) {
            return;
        }

        String value = targetResult.getContent();
        float centerX = bounds.centerX();
        float centerY = bounds.centerY();
        float barcodeWidth = bounds.width();
        float barcodeHeight = bounds.height();

        // 立即进行坐标转换（使用当前帧）
        float normalizedX = centerX / imageWidth;
        float normalizedY = centerY / imageHeight;
        float[] normalizedCoords = new float[]{normalizedX, normalizedY};
        float[] viewCoords = new float[2];
        try {
            currentFrame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED,
                    normalizedCoords,
                    Coordinates2d.VIEW,
                    viewCoords
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to transform coordinates", e);
            return;
        }
        float screenX = viewCoords[0];
        float screenY = viewCoords[1];

        Log.i(TAG, "Creating anchor for: " + value + " at pixel(" + centerX + ", " + centerY + ")");

        // 在GL线程中创建锚点
        final String fv = value;
        final float fx = centerX;
        final float fy = centerY;
        final float fw = barcodeWidth;
        final float fh = barcodeHeight;

        glSurfaceView.queueEvent(() -> createAnchorDirect(fx, fy, fw, fh, fv));
    }

    /**
     * 直接创建锚点 - 必须在 GL 线程调用
     */
    private void createAnchorDirect(float pixelX, float pixelY, float barcodeWidth, float barcodeHeight, String barcodeValue) {
        if (session == null || currentFrame == null || imageWidth == 0 || imageHeight == 0) {
            return;
        }

//        // 检查锚点创建间隔，避免短时间内创建过多锚点
//        long now = System.currentTimeMillis();
//        if (now - lastAnchorCreateTime < ANCHOR_CREATE_INTERVAL_MS) {
//            Log.d(TAG, "Anchor creation throttled for: " + barcodeValue);
//            // 移除注册，允许下次重试
//            synchronized (barcodeLock) {
//                registeredBarcodes.remove(barcodeValue);
//            }
//            return;
//        }

        try {
            Camera camera = currentFrame.getCamera();
            if (camera.getTrackingState() != TrackingState.TRACKING) {
                return;
            }

            int viewWidth = glSurfaceView.getWidth();
            int viewHeight = glSurfaceView.getHeight();

            // 坐标转换
            float normalizedX = pixelX / imageWidth;
            float normalizedY = pixelY / imageHeight;
            float[] normalizedCoords = new float[]{normalizedX, normalizedY};
            float[] viewCoords = new float[2];
            currentFrame.transformCoordinates2d(
                    Coordinates2d.IMAGE_NORMALIZED,
                    normalizedCoords,
                    Coordinates2d.VIEW,
                    viewCoords
            );
            float screenX = viewCoords[0];
            float screenY = viewCoords[1];

            // 查找已有锚点
            AnchorData existingAnchor = findAnchorByValue(barcodeValue);

            if (existingAnchor != null) {
                if (existingAnchor.anchor.getTrackingState() == TrackingState.STOPPED) {
                    existingAnchor.anchor.detach();
                    anchors.remove(existingAnchor);
                    Log.i(TAG, "Replacing STOPPED anchor for: " + barcodeValue);
                } else {
                    return;
                }
            } else {
                float minBarcodeDim = Math.min(barcodeWidth, barcodeHeight);
                float avgScale = ((float) viewWidth / imageWidth + (float) viewHeight / imageHeight) / 2.0f;
                float threshold = Math.max(minBarcodeDim * avgScale * 0.5f, 20.0f);

                if (isScreenPositionOccupied(screenX, screenY, threshold)) {
                    Log.i(TAG, "Position occupied, skip: " + barcodeValue);
                    return;
                }

                synchronized (barcodeLock) {
                    registeredBarcodes.add(barcodeValue);
                }
            }

            // 创建锚点
            Anchor anchor = null;
            boolean isInstantPlacement = false;

            if (usePlaneDetection) {
                List<HitResult> hitResults = currentFrame.hitTest(screenX, screenY);
                for (HitResult hit : hitResults) {
                    if (hit.getTrackable() instanceof Plane) {
                        Plane plane = (Plane) hit.getTrackable();
                        if (plane.getTrackingState() == TrackingState.TRACKING) {
                            anchor = hit.createAnchor();
                            isInstantPlacement = false;
                            Log.i(TAG, "Anchor created on plane for: " + barcodeValue);
                            break;
                        }
                    }
                }
                if (anchor == null) {
                    float approximateDistanceMeters = 0.5f;
                    List<HitResult> instantHits = currentFrame.hitTestInstantPlacement(screenX, screenY, approximateDistanceMeters);
                    if (!instantHits.isEmpty()) {
                        InstantPlacementPoint point = (InstantPlacementPoint) instantHits.get(0).getTrackable();
                        anchor = point.createAnchor(point.getPose());
                        isInstantPlacement = true;
                        Log.i(TAG, "Anchor created with InstantPlacement for: " + barcodeValue);
                    } else {
                        Log.w(TAG, "Failed to create anchor for: " + barcodeValue);
                        return;
                    }
                }
            } else {
                float approximateDistanceMeters = 0.5f;
                List<HitResult> hitResults = currentFrame.hitTestInstantPlacement(screenX, screenY, approximateDistanceMeters);
                if (hitResults.isEmpty()) {
                    Log.w(TAG, "hitTestInstantPlacement failed for: " + barcodeValue);
                    return;
                }
                InstantPlacementPoint point = (InstantPlacementPoint) hitResults.get(0).getTrackable();
                anchor = point.createAnchor(point.getPose());
                isInstantPlacement = true;
            }

            if (anchor != null) {
                if (anchors.size() >= MAX_ANCHORS) {
                    Log.w(TAG, "Max anchors reached, skip: " + barcodeValue);
                    anchor.detach();
                    return;
                }
                AnchorData anchorData = new AnchorData(anchor, barcodeValue, isInstantPlacement);
                anchorData.screenX = screenX;
                anchorData.screenY = screenY;
                anchorData.lastValidScreenX = screenX;
                anchorData.lastValidScreenY = screenY;
                anchors.add(anchorData);
                // 更新最后创建时间
//                lastAnchorCreateTime = System.currentTimeMillis();
                Log.i(TAG, "Anchor created for: " + barcodeValue + ", isInstantPlacement: " + isInstantPlacement + ", total: " + anchors.size());
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to create anchor: " + e.getMessage(), e);
        }
    }

    /**
     * 根据条码值查找已有锚点
     */
    private AnchorData findAnchorByValue(String barcodeValue) {
        for (AnchorData data : anchors) {
            if (data.barcodeValue.equals(barcodeValue)) {
                return data;
            }
        }
        return null;
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }
}
