package com.example.camerasample;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1;

    //最大プレビューサイズ
    private static final int MAX_PREVIEW_WIDTH = 1920;
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    //画像回転調整テーブル
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //ステータス
    private State _state = State.Preview;

    //バックグラウンドスレッド
    private HandlerThread _backgroundThread;
    private Handler _backgroundHandler;

    //カメラ
    private final Semaphore _cameraOpenCloseLock = new Semaphore(1);

    private CameraDevice _cameraDevice;
    private ImageReader _imageReader;
    private CameraCaptureSession _captureSession;

    private String _cameraId;
    private int _sensorOrientation;
    private Size _previewSize;
    private boolean _flashSupported;
    private CaptureRequest.Builder _previewRequestBuilder;
    private CaptureRequest _previewRequest;

    //キャプチャコールバック
    private CameraCaptureSession.CaptureCallback _captureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(@NonNull CaptureResult result) {
            switch (_state) {
                case Preview: {
                }
                break;

                case WaitingLock: {
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    }
                    else if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {

                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {

                            _state = State.PictureTaken;
                            captureStillPicture();
                        }
                        else {
                            runPrecaptureSequence();
                        }
                    }
                }
                break;

                case WaitingPreCapture: {

                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        _state = State.WaitingNonPreCapture;
                    }
                }
                break;

                case WaitingNonPreCapture: {
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {

                        _state = State.PictureTaken;
                        captureStillPicture();
                    }
                }
                break;
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            process(result);
        }
    };

    //ハンドラ
    private final Handler _handler = new Handler();

    //撮影写真
    private Bitmap _picture;

    //ビュー
    AutoFitTextureView _previewTextureView;
    ImageView _pictureImageView;
    Button _takePictureButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //ビュー取得
        _previewTextureView = findViewById(R.id.preview);
        _pictureImageView = findViewById(R.id.picture);
        _takePictureButton = findViewById(R.id.takePicture);

        //撮影ボタン
        _takePictureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                takePicture();
            }
        });

        //撮影写真クリア
        _pictureImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (_picture != null) {
                    _pictureImageView.setImageBitmap(null);
                    _picture.recycle();
                    _picture = null;
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        //権限要求
        requestPermission();

        //バックグラウンドスレッド開始
        startBackgroundThread();

        //テクスチャビューの準備ができたらカメラを開く
        if (_previewTextureView.isAvailable()) {
            //カメラを開く
            openCamera(_previewTextureView.getWidth(), _previewTextureView.getHeight());
        }
        else {
            _previewTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    //カメラを開く
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) { }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
            });
        }
    }

    @Override
    protected void onPause() {
        //カメラを閉じる
        closeCamera();

        //バックグラウンドスレッド停止
        stopBackgroundThread();

        //ハンドラ処理をキャンセル
        _handler.removeCallbacksAndMessages(null);

        super.onPause();
    }

    private void requestPermission() {

        //カメラは許可されていないか
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            //許可しない決定がされているか
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                //サンプルなので問答無用で終了
                finishAndRemoveTask();
            }
            else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            //不許可か
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                //サンプルなので問答無用で終了
                finishAndRemoveTask();
            }
        }
        else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void startBackgroundThread() {
        _backgroundThread = new HandlerThread("CameraBackgroundThread");
        _backgroundThread.start();
        _backgroundHandler = new Handler(_backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        _backgroundThread.quitSafely();

        try {
            _backgroundThread.join();
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        _backgroundThread = null;
        _backgroundHandler = null;
    }

    private void openCamera(int width, int height) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;

        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        setupCameraOutputs(width, height);
        configureTransform(width, height);

        try {
            if (!_cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }

            manager.openCamera(_cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    _cameraOpenCloseLock.release();
                    _cameraDevice = cameraDevice;

                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    _cameraOpenCloseLock.release();
                    cameraDevice.close();
                    _cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int error) {
                    _cameraOpenCloseLock.release();
                    cameraDevice.close();
                    _cameraDevice = null;

                    //エラーにより終了
                    finishAndRemoveTask();
                }
            }, _backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private void closeCamera() {
        try {
            _cameraOpenCloseLock.acquire();

            if (_captureSession != null) {
                _captureSession.close();
                _captureSession = null;
            }
            if (_cameraDevice != null) {
                _cameraDevice.close();
                _cameraDevice = null;
            }
            if (_imageReader != null) {
                _imageReader.close();
                _imageReader = null;
            }

            _cameraId = null;
            _sensorOrientation = 0;
            _previewSize = null;
            _flashSupported = false;
            _previewRequestBuilder = null;
            _previewRequest = null;
        }
        catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
        finally {
            _cameraOpenCloseLock.release();
        }
    }

    private void setupCameraOutputs(int width, int height) {
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) return;

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                //前面カメラ以外は使用しない
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                //ストリームの情報が取得できないカメラは使用しない
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                //センサの向きが取得できないカメラは使用しない
                Integer sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                if (sensorOrientation == null) {
                    continue;
                }
                _sensorOrientation = sensorOrientation;

                //フラッシュは使用可能か
                Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                _flashSupported = flashAvailable != null ? flashAvailable : false;

                //JPEGのサイズを取得
                Size jpegSize = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());

                //イメージリーダの生成
                _imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG,1);
                _imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader reader) {

                        //
                        // 撮影完了時に呼び出される
                        //

                        try(Image image = reader.acquireLatestImage()) {

                            ByteBuffer imageBuf = image.getPlanes()[0].getBuffer();
                            final byte[] imageBytes = new byte[imageBuf.remaining()];
                            imageBuf.get(imageBytes);
                            image.close();

                            final Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                            _handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (_picture != null) {
                                        _pictureImageView.setImageBitmap(null);
                                        _picture.recycle();
                                        _picture = null;
                                    }

                                    _pictureImageView.setImageBitmap(bitmap);
                                    _picture = bitmap;
                                }
                            });
                        }
                    }
                }, _backgroundHandler);

                //センサと画面の向きにより縦横回転が必要か判定
                int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (_sensorOrientation == 90 || _sensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (_sensorOrientation == 0 || _sensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        Log.e(getClass().getSimpleName(), "Display rotation is invalid: " + displayRotation);
                }

                //画面サイズを取得
                Point displaySize = new Point();
                getWindowManager().getDefaultDisplay().getSize(displaySize);

                //プレビューサイズを計算
                int viewWidth = width;
                int viewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;
                if (swappedDimensions) {
                    viewWidth = height;
                    viewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }
                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }
                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }
                _previewSize = getPreviewSize(
                        map.getOutputSizes(SurfaceTexture.class),
                        viewWidth,
                        viewHeight,
                        maxPreviewWidth,
                        maxPreviewHeight,
                        jpegSize);

                //テクスチャビューにアスペクト比を設定
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    _previewTextureView.setAspectRatio(_previewSize.getWidth(), _previewSize.getHeight());
                }
                else {
                    _previewTextureView.setAspectRatio(_previewSize.getHeight(), _previewSize.getWidth());
                }

                //カメラIDを保持
                _cameraId = cameraId;

                return;
            }
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (_previewTextureView == null || _previewSize == null ) return;

        RectF bufferRect;
        if (_sensorOrientation == 0 || _sensorOrientation == 180) {
            bufferRect = new RectF(0, 0, _previewSize.getWidth(), _previewSize.getHeight());
        }
        else {
            bufferRect = new RectF(0, 0, _previewSize.getHeight(), _previewSize.getWidth());
        }

        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        Matrix matrix = new Matrix();

        //鏡面反転
        matrix.preScale(-1.0f, 1.0f, centerX, centerY);

        //画面の向きに応じた表示調整
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {

            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

            float viewLongEdge = viewWidth > viewHeight ? viewWidth : viewHeight;
            float viewShortEdge = viewWidth <= viewHeight ? viewWidth : viewHeight;
            float scale = Math.max(viewShortEdge / _previewSize.getHeight(), viewLongEdge / _previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);

            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }

        _previewTextureView.setTransform(matrix);
    }

    @NonNull
    private Size getPreviewSize(@NonNull Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, @NonNull Size aspectRatio) {
        if (choices.length == 0) return new Size(textureViewWidth, textureViewHeight);

        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();

        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        for (Size option : choices) {
            if (option.getWidth() <= maxWidth &&
                    option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {

                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                }
                else {
                    notBigEnough.add(option);
                }
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        }
        else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        }
        else {
            return choices[0];
        }
    }

    private void createCameraPreviewSession() {
        if (_cameraDevice == null || _previewSize == null) return;

        SurfaceTexture texture = _previewTextureView.getSurfaceTexture();
        if (texture == null) return;

        try {
            _previewRequestBuilder = _cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            texture.setDefaultBufferSize(_previewSize.getWidth(), _previewSize.getHeight());
            Surface surface = new Surface(texture);
            _previewRequestBuilder.addTarget(surface);

            _cameraDevice.createCaptureSession(Arrays.asList(surface, _imageReader.getSurface()), new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            if (_cameraDevice == null) return;

                            _captureSession = cameraCaptureSession;
                            try {
                                _previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                setAutoFlash(_previewRequestBuilder);

                                _previewRequest = _previewRequestBuilder.build();
                                _captureSession.setRepeatingRequest(_previewRequest, _captureCallback, _backgroundHandler);
                            }
                            catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                            Log.e(getClass().getSimpleName(), "Capture session configure failed");
                        }
                    }, null);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setAutoFlash(@NonNull CaptureRequest.Builder requestBuilder) {
        if (_flashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + _sensorOrientation + 270) % 360;
    }

    private void takePicture() {
        lockFocus();
    }

    private void lockFocus() {
        if (_previewRequestBuilder == null || _captureSession == null) return;

        try {
            _previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            _state = State.WaitingLock;
            _captureSession.capture(_previewRequestBuilder.build(), _captureCallback, _backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void unlockFocus() {
        if (_previewRequestBuilder == null || _captureSession == null) return;

        try {
            _previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(_previewRequestBuilder);
            _captureSession.capture(_previewRequestBuilder.build(), _captureCallback, _backgroundHandler);

            _state = State.Preview;
            _captureSession.setRepeatingRequest(_previewRequest, _captureCallback, _backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void runPrecaptureSequence() {
        if (_previewRequestBuilder == null || _captureSession == null) return;

        try {
            _previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            _state = State.WaitingPreCapture;
            _captureSession.capture(
                    _previewRequestBuilder.build(),
                    _captureCallback,
                    _backgroundHandler);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void captureStillPicture() {
        if (_captureSession == null) return;

        try {
            final CaptureRequest.Builder captureBuilder = _cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(_imageReader.getSurface());

            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));

            _captureSession.stopRepeating();
            _captureSession.abortCaptures();
            _captureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    unlockFocus();
                }
            }, null);
        }
        catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * ステータスを表す列挙型
     */
    private enum State {
        Preview,
        WaitingLock,
        WaitingPreCapture,
        WaitingNonPreCapture,
        PictureTaken
    };

    /**
     * 面積によって大小比較を行うコンパレータ
     */
    private class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
