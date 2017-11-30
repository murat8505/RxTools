package com.vondear.rxtools.activity;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.ScaleAnimation;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.tbruyelle.rxpermissions2.Permission;
import com.tbruyelle.rxpermissions2.RxPermissions;
import com.vondear.rxtools.R;
import com.vondear.rxtools.RxBarTool;
import com.vondear.rxtools.RxBeepTool;
import com.vondear.rxtools.RxConstants;
import com.vondear.rxtools.RxDataTool;
import com.vondear.rxtools.RxPhotoTool;
import com.vondear.rxtools.RxSPTool;
import com.vondear.rxtools.module.scaner.CameraManager;
import com.vondear.rxtools.module.scaner.CaptureActivityHandler;
import com.vondear.rxtools.module.scaner.RxQrBarTool;
import com.vondear.rxtools.view.RxToast;
import com.vondear.rxtools.view.dialog.RxDialogSure;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.functions.Consumer;

public class ActivityScanerCode extends ActivityBase {

    private ScanerCodeBinding mBinding;

    private CaptureActivityHandler handler;//扫描处理
    private boolean hasSurface;//是否有预览
    private boolean vibrate = true;//扫描成功后是否震动
    private RxDialogSure rxDialogSure;//扫描结果显示框

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RxBarTool.setNoTitle(this);
        mBinding = ScanerCodeBinding.inflate(getLayoutInflater());
        setContentView(mBinding.getRoot());
        mBinding.setView(this);

        RxBarTool.setTransparentStatusBar(this);
        initScanerAnimation(); // 扫描动画初始化
        CameraManager.init(mContext); // 初始化 CameraManager
        hasSurface = false;
        requestPermissions();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onResume() {
        super.onResume();
        SurfaceHolder surfaceHolder = mBinding.capturePreview.getHolder();
        if (hasSurface) {
            initCamera(surfaceHolder); // Camera初始化
        } else {
            surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

                }

                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    if (!hasSurface) {
                        hasSurface = true;
                        initCamera(holder);
                    }
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    hasSurface = false;

                }
            });
            surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (handler != null) {
            handler.quitSynchronously();
            handler = null;
        }
        CameraManager.get().closeDriver();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void requestPermissions() {
        RxPermissions rxPermission = new RxPermissions(ActivityScanerCode.this);
        rxPermission
                .requestEach(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.CAMERA)
                .subscribe(new Consumer<Permission>() {
                    @Override
                    public void accept(Permission permission) throws Exception {
                        if (permission.granted) {
                            // 用户已经同意该权限
                        } else if (permission.shouldShowRequestPermissionRationale) {
                            // 用户拒绝了该权限，没有选中『不再询问』,那么下次再次启动时，还会提示请求权限的对话框
                        } else {
                            // 用户拒绝了该权限，并且选中『不再询问』
                        }
                    }
                });
    }

    private void initScanerAnimation() {
        ScaleAnimation animation = new ScaleAnimation(1.0f, 1.0f, 0.0f, 1.0f);
        animation.setRepeatCount(-1);
        animation.setRepeatMode(Animation.RESTART);
        animation.setInterpolator(new LinearInterpolator());
        animation.setDuration(1200);
        mBinding.captureScanLine.startAnimation(animation);
    }

    public void onBackClick(View view) {
        finish();
    }

    public void onLightClick(View view) {
        CameraManager.get().light();
    }

    public void onOpenPictureClick(View view) {
        RxPhotoTool.openLocalImage(mContext);
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        try {
            CameraManager.get().openDriver(surfaceHolder);
            Point point = CameraManager.get().getCameraResolution();
            AtomicInteger width = new AtomicInteger(point.y);
            AtomicInteger height = new AtomicInteger(point.x);
            CameraManager.FRAME_WIDTH = mBinding.captureScanerLayout.getWidth() * width.get()
                    / mBinding.captureContainer.getWidth();
            CameraManager.FRAME_HEIGHT = mBinding.captureScanerLayout.getHeight() * height.get()
                    / mBinding.captureContainer.getHeight();
        } catch (IOException | RuntimeException e) {
            return;
        }
        if (handler == null) {
            handler = new CaptureActivityHandler(ActivityScanerCode.this);
        }
    }

    //--------------------------------------打开本地图片识别二维码 start---------------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            ContentResolver resolver = getContentResolver();
            // 照片的原始资源地址
            Uri originalUri = data.getData();
            try {
                // 使用ContentProvider通过URI获取原始图片
                Bitmap photo = MediaStore.Images.Media.getBitmap(resolver, originalUri);

                // 开始对图像资源解码
                Result rawResult = RxQrBarTool.decodeFromPhoto(photo);
                if (rawResult != null) {
                    initDialogResult(rawResult);
                } else {
                    RxToast.error("图片识别失败.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    //========================================打开本地图片识别二维码 end=================================

    //==============================================================================================解析结果 及 后续处理 end

    private void initDialogResult(Result result) {
        BarcodeFormat type = result.getBarcodeFormat();
        String realContent = result.getText();

        if (rxDialogSure == null) {
            rxDialogSure = new RxDialogSure(mContext);//提示弹窗
        }

        if (BarcodeFormat.QR_CODE.equals(type)) {
            rxDialogSure.setTitle("二维码扫描结果");
        } else if (BarcodeFormat.EAN_13.equals(type)) {
            rxDialogSure.setTitle("条形码扫描结果");
        } else {
            rxDialogSure.setTitle("扫描结果");
        }

        rxDialogSure.setContent(realContent);
        rxDialogSure.setSureListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rxDialogSure.cancel();
            }
        });
        rxDialogSure.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (handler != null) {
                    // 连续扫描，不发送此消息扫描一次结束后就不能再次扫描
                    handler.sendEmptyMessage(R.id.restart_preview);
                }
            }
        });

        if (!rxDialogSure.isShowing()) {
            rxDialogSure.show();
        }

        RxSPTool.putContent(mContext, RxConstants.SP_SCAN_CODE, RxDataTool.stringToInt(RxSPTool.getContent(mContext, RxConstants.SP_SCAN_CODE)) + 1 + "");
    }

    public void handleDecode(Result result) {
        RxBeepTool.playBeep(mContext, vibrate);//扫描成功之后的振动与声音提示

        String result1 = result.getText();
        Log.v("二维码/条形码 扫描结果", result1);
        RxToast.success(result1);
        initDialogResult(result);
    }

    public Handler getHandler() {
        return handler;
    }

}