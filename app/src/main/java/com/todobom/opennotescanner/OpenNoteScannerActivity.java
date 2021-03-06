package com.todobom.opennotescanner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;

import com.github.fafaldo.fabtoolbar.widget.FABToolbarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.todobom.opennotescanner.helpers.AppConstants;
import com.todobom.opennotescanner.helpers.CustomOpenCVLoader;
import com.todobom.opennotescanner.helpers.DocumentsManager;
import com.todobom.opennotescanner.helpers.OpenNoteMessage;
import com.todobom.opennotescanner.helpers.PreviewFrame;
import com.todobom.opennotescanner.helpers.ScannedDocument;
import com.todobom.opennotescanner.views.HUDCanvasView;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.parceler.Parcels;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import static com.todobom.opennotescanner.helpers.Utils.decodeSampledBitmapFromUri;

/**
 * An example full-screen activity that shows and hides the system UI (i.e. status bar and
 * navigation/system bar) with user interaction.
 */
public class OpenNoteScannerActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, SurfaceHolder.Callback,
        Camera.PictureCallback, Camera.PreviewCallback {

    /**
     * Some older devices needs a small delay between UI widget updates and a change of the
     * status and
     * navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;

    private static final int CREATE_PERMISSIONS_REQUEST_CAMERA = 1;
    private static final int MY_PERMISSIONS_REQUEST_WRITE = 3;

    private static final int RESUME_PERMISSIONS_REQUEST_CAMERA = 11;
    private static final String TAG = "OpenNoteScannerActivity";
    private final Handler mHideHandler = new Handler();
    private final Runnable mHidePart2Runnable = () -> {
        // Delayed removal of status and navigation bar

        // Note that some of these constants are new as of API 16 (Jelly Bean)
        // and API 19 (KitKat). It is safe to use them, as they are inlined
        // at compile-time and do nothing on earlier devices.

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    };
    private View mControlsView;
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            mControlsView.setVisibility(View.VISIBLE);
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = this::hide;
    private MediaPlayer _shootMP = null;

    private boolean safeToTakePicture;
    private Button scanDocButton;
    private HandlerThread mImageThread;
    private ImageProcessor mImageProcessor;
    private SurfaceHolder mSurfaceHolder;
    private Camera mCamera;
    private OpenNoteScannerActivity mThis;

    private boolean mFocused;
    private HUDCanvasView mHud;
    private View mWaitSpinner;
    private FABToolbarLayout mFabToolbar;

    private SharedPreferences mSharedPref;
    private SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
    private boolean imageProcessorBusy = true;
    private boolean attemptToFocus = false;
    private SurfaceView mSurfaceView;
    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            if (status == LoaderCallbackInterface.SUCCESS) {
                checkResumePermissions();
            } else {
                Log.d(TAG, "opencvstatus: " + status);
                super.onManagerConnected(status);
            }
        }
    };
    private boolean scanClicked = false;
    private boolean colorMode = false;
    private boolean filterMode = true;
    private boolean autoMode = false;
    private boolean mFlashMode = false;
    private ResetShutterColor resetShutterColor = new ResetShutterColor();

    private DocumentsManager documentsManager;
    private Intent intent;
    private boolean doubleBackToExitPressedOnce = false;

    public HUDCanvasView getHUD() {
        return mHud;
    }

    public void setImageProcessorBusy(boolean imageProcessorBusy) {
        this.imageProcessorBusy = imageProcessorBusy;
    }

    public void setAttemptToFocus(boolean attemptToFocus) {
        this.attemptToFocus = attemptToFocus;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mThis = this;

        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        intent = getIntent();
        documentsManager = Parcels.unwrap(intent.getParcelableExtra(AppConstants.DOCUMENTS_EXTRA_KEY));
        if (documentsManager == null) {
            documentsManager = new DocumentsManager(getCacheDir());
        }

        setContentView(R.layout.activity_open_note_scanner);

        mVisible = true;
        mControlsView = findViewById(R.id.fullscreen_content_controls);
        final View contentView = findViewById(R.id.surfaceView);
        mHud = findViewById(R.id.hud);
        mWaitSpinner = findViewById(R.id.wait_spinner);

        // Set up the user interaction to manually show or hide the system UI.
        contentView.setOnClickListener(view -> toggle());

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Display display = getWindowManager().getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);

        scanDocButton = findViewById(R.id.scanDocButton);

        scanDocButton.setOnClickListener(v -> {
            if (scanClicked) {
                requestPicture();
                scanDocButton.setBackgroundTintList(null);
                waitSpinnerVisible();
            } else {
                scanClicked = true;
                Toast.makeText(getApplicationContext(), R.string.scanningToast,
                        Toast.LENGTH_LONG).show();
                v.setBackgroundTintList(ColorStateList.valueOf(0x7F60FF60));
            }
        });

        final ImageView colorModeButton = findViewById(R.id.colorModeButton);

        colorModeButton.setOnClickListener(v -> {
            colorMode = !colorMode;
            ((ImageView) v).setColorFilter(colorMode ? 0xFFFFFFFF : 0xFFA0F0A0);

            sendImageProcessorMessage("colorMode", colorMode);

            Toast.makeText(getApplicationContext(), colorMode ? R.string.colorMode :
                            R.string.bwMode,
                    Toast.LENGTH_SHORT).show();

        });

        final ImageView filterModeButton = findViewById(R.id.filterModeButton);

        filterModeButton.setOnClickListener(v -> {
            filterMode = !filterMode;
            ((ImageView) v).setColorFilter(filterMode ? 0xFFFFFFFF : 0xFFA0F0A0);

            sendImageProcessorMessage("filterMode", filterMode);

            Toast.makeText(getApplicationContext(),
                    filterMode ? R.string.filterModeOn : R.string.filterModeOff,
                    Toast.LENGTH_SHORT).show();

        });

        final ImageView flashModeButton = findViewById(R.id.flashModeButton);

        flashModeButton.setOnClickListener(v -> {
            mFlashMode = setFlash(!mFlashMode);
            ((ImageView) v).setColorFilter(mFlashMode ? 0xFFFFFFFF : 0xFFA0F0A0);

        });

        final ImageView autoModeButton = findViewById(R.id.autoModeButton);

        autoModeButton.setOnClickListener(v -> {
            autoMode = !autoMode;
            ((ImageView) v).setColorFilter(autoMode ? 0xFFFFFFFF : 0xFFA0F0A0);
            Toast.makeText(getApplicationContext(), autoMode ? R.string.autoMode :
                            R.string.manualMode,
                    Toast.LENGTH_SHORT).show();
        });

        final ImageView settingsButton = findViewById(R.id.settingsButton);

        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(v.getContext(), SettingsActivity.class);
            startActivity(intent);
        });

        mFabToolbar = findViewById(R.id.fabtoolbar);

        FloatingActionButton fabToolbarButton = findViewById(R.id.fabtoolbar_fab);
        fabToolbarButton.setOnClickListener(v -> mFabToolbar.show());

        findViewById(R.id.hideToolbarButton).setOnClickListener(v -> mFabToolbar.hide());

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case CREATE_PERMISSIONS_REQUEST_CAMERA: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    turnCameraOn();
                }
                break;
            }

            case RESUME_PERMISSIONS_REQUEST_CAMERA: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    enableCameraView();
                }
                break;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    public void enableCameraView() {
        if (mSurfaceView == null) {
            turnCameraOn();
        }
    }

    public void saveDocument(ScannedDocument scannedDocument) {
        Mat doc = (scannedDocument.processed != null) ? scannedDocument.processed :
                scannedDocument.original;

        String filePath;
        boolean isIntent = false;
        Uri fileUri = null;
        String fileFormat = mSharedPref.getString(getString(R.string.pref_key_file_format),
                AppConstants.FILE_SUFFIX_PDF);
        String imgSuffix = AppConstants.FILE_SUFFIX_JPG;
        if (fileFormat.equals(AppConstants.FILE_SUFFIX_PNG)) {
            imgSuffix = AppConstants.FILE_SUFFIX_PNG;
        }

        if (intent.getAction() != null && intent.getAction().equals("android.media.action" + ".IMAGE_CAPTURE")) {
            fileUri = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
            Log.d(TAG, "intent uri: " + fileUri.toString());
            try {
                filePath = File.createTempFile("onsFile", imgSuffix, this.getCacheDir()).getPath();
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            isIntent = true;
        } else {
            filePath = documentsManager.createNewFile(imgSuffix);
        }
        Mat endDoc = new Mat(Double.valueOf(doc.size().width).intValue(),
                Double.valueOf(doc.size().height).intValue(), CvType.CV_8UC4);

        Core.flip(doc.t(), endDoc, 1);

        Imgcodecs.imwrite(filePath, endDoc);
        endDoc.release();

        if (fileFormat.equals("jpg")) {
            try {
                ExifInterface exif = new ExifInterface(filePath);
                exif.setAttribute("UserComment", "Generated using Open Note Scanner");
                String nowFormatted = mDateFormat.format(new Date().getTime());
                exif.setAttribute(ExifInterface.TAG_DATETIME, nowFormatted);
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowFormatted);
                exif.setAttribute("Software",
                        "OpenNoteScanner " + BuildConfig.VERSION_NAME + " https://goo.gl/2JwEPq");
                exif.saveAttributes();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (isIntent) {
            InputStream inputStream = null;
            OutputStream realOutputStream = null;
            try {
                inputStream = new FileInputStream(filePath);
                realOutputStream = this.getContentResolver().openOutputStream(fileUri);
                // Transfer bytes from in to out
                byte[] buffer = new byte[1024];
                int len;
                while ((len = inputStream.read(buffer)) > 0) {
                    realOutputStream.write(buffer, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                try {
                    inputStream.close();
                    realOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            new File(filePath).delete();
            setResult(RESULT_OK, intent);
            finish();
        } else {
            Intent startPreview = new Intent(this, PreviewActivity.class);
            startPreview.putExtra(AppConstants.DOCUMENTS_EXTRA_KEY, Parcels.wrap(documentsManager));
            startActivity(startPreview);
            finish();
        }
    }

    public boolean setFlash(boolean stateFlash) {
        PackageManager pm = getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            Camera.Parameters par = mCamera.getParameters();
            par.setFlashMode(
                    stateFlash ? Camera.Parameters.FLASH_MODE_TORCH :
                            Camera.Parameters.FLASH_MODE_OFF);
            mCamera.setParameters(par);
            Log.d(TAG, "flash: " + (stateFlash ? "on" : "off"));
            return stateFlash;
        }
        return false;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    public void onBackPressed() {
        // https://stackoverflow.com/questions/8430805/clicking-the-back-button-twice-to-exit-an
        // -activity
        if (doubleBackToExitPressedOnce) {
            super.onBackPressed();
            return;
        }

        this.doubleBackToExitPressedOnce = true;
        Toast.makeText(this, R.string.toast_confirm_back_action, Toast.LENGTH_SHORT).show();

        new Handler().postDelayed(() -> doubleBackToExitPressedOnce = false, 2000);
    }

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            int cameraId = findBestCamera();
            mCamera = Camera.open(cameraId);
        } catch (RuntimeException e) {
            Log.d(TAG, "surfaceCreated: " + e);
            return;
        }

        Camera.Parameters param;
        param = mCamera.getParameters();

        Camera.Size pSize = getMaxPreviewResolution();
        param.setPreviewSize(pSize.width, pSize.height);

        float previewRatio = (float) pSize.width / pSize.height;

        Display display = getWindowManager().getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getRealSize(size);

        int displayWidth = Math.min(size.y, size.x);
        int displayHeight = Math.max(size.y, size.x);

        float displayRatio = (float) displayHeight / displayWidth;

        int previewHeight = displayHeight;

        if (displayRatio > previewRatio) {
            ViewGroup.LayoutParams surfaceParams = mSurfaceView.getLayoutParams();
            previewHeight = (int) ((float) size.y / displayRatio * previewRatio);
            surfaceParams.height = previewHeight;
            mSurfaceView.setLayoutParams(surfaceParams);

            mHud.getLayoutParams().height = previewHeight;
        }

        int hotAreaWidth = displayWidth / 4;
        int hotAreaHeight = previewHeight / 2 - hotAreaWidth;

        ImageView angleNorthWest = findViewById(R.id.nw_angle);
        RelativeLayout.LayoutParams paramsNW = (RelativeLayout.LayoutParams) angleNorthWest
                .getLayoutParams();
        paramsNW.leftMargin = hotAreaWidth - paramsNW.width;
        paramsNW.topMargin = hotAreaHeight - paramsNW.height;
        angleNorthWest.setLayoutParams(paramsNW);
        angleNorthWest.setVisibility(View.VISIBLE);

        ImageView angleNorthEast = findViewById(R.id.ne_angle);
        RelativeLayout.LayoutParams paramsNE = (RelativeLayout.LayoutParams) angleNorthEast
                .getLayoutParams();
        paramsNE.leftMargin = displayWidth - hotAreaWidth;
        paramsNE.topMargin = hotAreaHeight - paramsNE.height;
        angleNorthEast.setLayoutParams(paramsNE);
        angleNorthEast.setVisibility(View.VISIBLE);

        ImageView angleSouthEast = findViewById(R.id.se_angle);
        RelativeLayout.LayoutParams paramsSE = (RelativeLayout.LayoutParams) angleSouthEast
                .getLayoutParams();
        paramsSE.leftMargin = displayWidth - hotAreaWidth;
        paramsSE.topMargin = previewHeight - hotAreaHeight;
        angleSouthEast.setLayoutParams(paramsSE);
        angleSouthEast.setVisibility(View.VISIBLE)
        ;
        ImageView angleSouthWest = findViewById(R.id.sw_angle);
        RelativeLayout.LayoutParams paramsSW = (RelativeLayout.LayoutParams) angleSouthWest
                .getLayoutParams();
        paramsSW.leftMargin = hotAreaWidth - paramsSW.width;
        paramsSW.topMargin = previewHeight - hotAreaHeight;
        angleSouthWest.setLayoutParams(paramsSW);
        angleSouthWest.setVisibility(View.VISIBLE);

        Camera.Size maxRes = getMaxPictureResolution(previewRatio);
        if (maxRes != null) {
            param.setPictureSize(maxRes.width, maxRes.height);
            Log.d(TAG, "max supported picture resolution: " + maxRes.width + "x" + maxRes.height);
        }

        PackageManager pm = getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_AUTOFOCUS)) {
            param.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            Log.d(TAG, "enabling autofocus");
        } else {
            mFocused = true;
            Log.d(TAG, "autofocus not available");
        }
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            param.setFlashMode(
                    mFlashMode ? Camera.Parameters.FLASH_MODE_TORCH :
                            Camera.Parameters.FLASH_MODE_OFF);
        }

        mCamera.setParameters(param);

        final boolean bugRotate = mSharedPref.getBoolean(getString(R.string.pref_key_bug_rotate),
                false);

        if (bugRotate) {
            mCamera.setDisplayOrientation(270);
        } else {
            mCamera.setDisplayOrientation(90);
        }

        if (mImageProcessor != null) {
            mImageProcessor.setBugRotate(bugRotate);
        }

        try {
            mCamera.setAutoFocusMoveCallback((start, camera) -> {
                mFocused = !start;
                Log.d(TAG, "focusMoving: " + mFocused);
            });
        } catch (Exception e) {
            Log.d(TAG, "failed setting AutoFocusMoveCallback");
        }

        // some devices doesn't call the AutoFocusMoveCallback - fake the
        // focus to true at the start
        mFocused = true;

        safeToTakePicture = true;

    }

    @Override
    public void onResume() {
        super.onResume();

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );

        Log.d(TAG, "resuming");

        for (String build : Build.SUPPORTED_ABIS) {
            Log.d(TAG, "myBuild " + build);
        }

        checkCreatePermissions();

        CustomOpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, mLoaderCallback);

        if (mImageThread == null) {
            mImageThread = new HandlerThread("Worker Thread");
            mImageThread.start();
        }

        if (mImageProcessor == null) {
            mImageProcessor = new ImageProcessor(mImageThread.getLooper(), new Handler(), this);
        }
        this.setImageProcessorBusy(false);

    }

    public void waitSpinnerVisible() {
        runOnUiThread(() -> mWaitSpinner.setVisibility(View.VISIBLE));
    }

    public void waitSpinnerInvisible() {
        runOnUiThread(() -> mWaitSpinner.setVisibility(View.GONE));
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public void onDestroy() {
        super.onDestroy();
        // FIXME: check disableView()
    }

    public List<Camera.Size> getResolutionList() {
        return mCamera.getParameters().getSupportedPreviewSizes();
    }

    public Camera.Size getMaxPreviewResolution() {
        int maxWidth = 0;
        Camera.Size curRes = null;

        mCamera.lock();

        for (Camera.Size r : getResolutionList()) {
            if (r.width > maxWidth) {
                Log.d(TAG, "supported preview resolution: " + r.width + "x" + r.height);
                maxWidth = r.width;
                curRes = r;
            }
        }

        return curRes;
    }


    public List<Camera.Size> getPictureResolutionList() {
        return mCamera.getParameters().getSupportedPictureSizes();
    }

    public Camera.Size getMaxPictureResolution(float previewRatio) {
        int maxPixels = 0;
        int ratioMaxPixels = 0;
        Camera.Size currentMaxRes = null;
        Camera.Size ratioCurrentMaxRes = null;
        for (Camera.Size r : getPictureResolutionList()) {
            float pictureRatio = (float) r.width / r.height;
            Log.d(TAG,
                    "supported picture resolution: " + r.width + "x" + r.height + " ratio: " + pictureRatio);
            int resolutionPixels = r.width * r.height;

            if (resolutionPixels > ratioMaxPixels && pictureRatio == previewRatio) {
                ratioMaxPixels = resolutionPixels;
                ratioCurrentMaxRes = r;
            }

            if (resolutionPixels > maxPixels) {
                maxPixels = resolutionPixels;
                currentMaxRes = r;
            }
        }

        boolean matchAspect = mSharedPref.getBoolean("match_aspect", true);

        if (ratioCurrentMaxRes != null && matchAspect) {

            Log.d(TAG, "Max supported picture resolution with preview aspect ratio: "
                    + ratioCurrentMaxRes.width + "x" + ratioCurrentMaxRes.height);
            return ratioCurrentMaxRes;

        }

        return currentMaxRes;
    }


    private int findBestCamera() {
        int cameraId = -1;
        //Search for the back facing camera
        //get the number of cameras
        int numberOfCameras = Camera.getNumberOfCameras();
        //for every camera check
        for (int i = 0; i < numberOfCameras; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                cameraId = i;
                break;
            }
            cameraId = i;
        }
        return cameraId;
    }

    public void turnCameraOn() {
        mSurfaceView = findViewById(R.id.surfaceView);

        mSurfaceHolder = mSurfaceView.getHolder();

        mSurfaceHolder.addCallback(this);
        mSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        mSurfaceView.setVisibility(SurfaceView.VISIBLE);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        refreshCamera();
    }

    private void refreshCamera() {
        try {
            mCamera.stopPreview();
        } catch (Exception e) {
        }

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);

            mCamera.startPreview();
            mCamera.setPreviewCallback(this);
        } catch (Exception e) {
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallback(null);
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {

        android.hardware.Camera.Size pictureSize = camera.getParameters().getPreviewSize();

        Log.d(TAG, "onPreviewFrame - received image " + pictureSize.width + "x" + pictureSize.height
                + " focused: " + mFocused + " imageprocessor: " + (imageProcessorBusy ? "busy"
                : "available"));

        if (mFocused && !imageProcessorBusy) {
            setImageProcessorBusy(true);
            Mat yuv = new Mat(new Size(pictureSize.width, pictureSize.height * 1.5),
                    CvType.CV_8UC1);
            yuv.put(0, 0, data);

            Mat mat = new Mat(new Size(pictureSize.width, pictureSize.height), CvType.CV_8UC4);
            Imgproc.cvtColor(yuv, mat, Imgproc.COLOR_YUV2RGBA_NV21, 4);

            yuv.release();

            sendImageProcessorMessage("previewFrame",
                    new PreviewFrame(mat, autoMode, !(autoMode || scanClicked)));
        }

    }

    public void invalidateHUD() {
        runOnUiThread(() -> mHud.invalidate());
    }

    public boolean requestPicture() {
        if (safeToTakePicture) {
            runOnUiThread(resetShutterColor);
            safeToTakePicture = false;
            mCamera.autoFocus((success, camera) -> {
                if (attemptToFocus) {
                    return;
                } else {
                    attemptToFocus = true;
                }

                camera.takePicture(null, null, mThis);
            });
            return true;
        }
        return false;
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {

        shootSound();

        android.hardware.Camera.Size pictureSize = camera.getParameters().getPictureSize();

        Log.d(TAG,
                "onPictureTaken - received image " + pictureSize.width + "x" + pictureSize.height);

        Mat mat = new Mat(new Size(pictureSize.width, pictureSize.height), CvType.CV_8U);
        mat.put(0, 0, data);

        setImageProcessorBusy(true);
        sendImageProcessorMessage("pictureTaken", mat);

        camera.cancelAutoFocus();
        scanClicked = false;
        safeToTakePicture = true;

    }

    public void sendImageProcessorMessage(String messageText, Object obj) {
        Log.d(TAG, "sending message to ImageProcessor: " + messageText + " - " + obj.toString());
        Message msg = mImageProcessor.obtainMessage();
        msg.obj = new OpenNoteMessage(messageText, obj);
        mImageProcessor.sendMessage(msg);
    }

    private void checkCreatePermissions() {

        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    MY_PERMISSIONS_REQUEST_WRITE);

        }

    }

    private void checkResumePermissions() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    RESUME_PERMISSIONS_REQUEST_CAMERA);

        } else {
            enableCameraView();
        }
    }

    private void shootSound() {
        AudioManager meng = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int volume = meng.getStreamVolume(AudioManager.STREAM_NOTIFICATION);

        if (volume != 0) {
            if (_shootMP == null) {
                _shootMP = MediaPlayer
                        .create(this, Uri.parse("file:///system/media/audio/ui/camera_click.ogg"));
            }
            if (_shootMP != null) {
                _shootMP.start();
            }
        }
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        return false;
    }

    class AnimationRunnable implements Runnable {

        public int height;

        public int width;

        String fileName = null;

        Size previewSize = null;

        private Bitmap bitmap;

        private Size imageSize;

        private Point[] previewPoints = null;

        AnimationRunnable(String filename, ScannedDocument document) {
            this.fileName = filename;
            this.imageSize = document.processed.size();

            if (document.quadrilateral != null) {
                this.previewPoints = document.previewPoints;
                this.previewSize = document.previewSize;
            }
        }

        @Override
        public void run() {
            final ImageView imageView = findViewById(R.id.scannedAnimation);

            Display display = getWindowManager().getDefaultDisplay();
            android.graphics.Point size = new android.graphics.Point();
            display.getRealSize(size);

            int width = Math.min(size.x, size.y);
            int height = Math.max(size.x, size.y);

            // ATENTION: captured images are always in landscape, values should be swapped
            double imageWidth = imageSize.height;
            double imageHeight = imageSize.width;

            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) imageView
                    .getLayoutParams();

            if (previewPoints != null) {
                double documentLeftHeight = hypotenuse(previewPoints[0], previewPoints[1]);
                double documentBottomWidth = hypotenuse(previewPoints[1], previewPoints[2]);
                double documentRightHeight = hypotenuse(previewPoints[2], previewPoints[3]);
                double documentTopWidth = hypotenuse(previewPoints[3], previewPoints[0]);

                double documentWidth = Math.max(documentTopWidth, documentBottomWidth);
                double documentHeight = Math.max(documentLeftHeight, documentRightHeight);

                Log.d(TAG,
                        "device: " + width + "x" + height + " image: " + imageWidth + "x" + imageHeight
                                + " document: " + documentWidth + "x" + documentHeight);

                Log.d(TAG, "previewPoints[0] x=" + previewPoints[0].x + " y=" + previewPoints[0].y);
                Log.d(TAG, "previewPoints[1] x=" + previewPoints[1].x + " y=" + previewPoints[1].y);
                Log.d(TAG, "previewPoints[2] x=" + previewPoints[2].x + " y=" + previewPoints[2].y);
                Log.d(TAG, "previewPoints[3] x=" + previewPoints[3].x + " y=" + previewPoints[3].y);

                // ATENTION: again, swap width and height
                double xRatio = width / previewSize.height;
                double yRatio = height / previewSize.width;

                params.topMargin = (int) (previewPoints[3].x * yRatio);
                params.leftMargin = (int) ((previewSize.height - previewPoints[3].y) * xRatio);
                params.width = (int) (documentWidth * xRatio);
                params.height = (int) (documentHeight * yRatio);
            } else {
                params.topMargin = height / 4;
                params.leftMargin = width / 4;
                params.width = width / 2;
                params.height = height / 2;
            }

            bitmap = decodeSampledBitmapFromUri(fileName, params.width, params.height);

            imageView.setImageBitmap(bitmap);

            imageView.setVisibility(View.VISIBLE);

            TranslateAnimation translateAnimation = new TranslateAnimation(
                    Animation.ABSOLUTE, 0, Animation.ABSOLUTE, -params.leftMargin,
                    Animation.ABSOLUTE, 0, Animation.ABSOLUTE, height - params.topMargin
            );

            ScaleAnimation scaleAnimation = new ScaleAnimation(1, 0, 1, 0);

            AnimationSet animationSet = new AnimationSet(true);

            animationSet.addAnimation(scaleAnimation);
            animationSet.addAnimation(translateAnimation);

            animationSet.setDuration(600);
            animationSet.setInterpolator(new AccelerateInterpolator());

            animationSet.setAnimationListener(new Animation.AnimationListener() {
                @Override
                public void onAnimationEnd(Animation animation) {
                    imageView.setVisibility(View.INVISIBLE);
                    imageView.setImageBitmap(null);
                    AnimationRunnable.this.bitmap.recycle();
                }

                @Override
                public void onAnimationRepeat(Animation animation) {

                }

                @Override
                public void onAnimationStart(Animation animation) {

                }
            });

            imageView.startAnimation(animationSet);
        }

        double hypotenuse(Point a, Point b) {
            return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.y - b.y, 2));
        }
    }

    private class ResetShutterColor implements Runnable {

        @Override
        public void run() {
            scanDocButton.setBackgroundTintList(null);
        }
    }
}