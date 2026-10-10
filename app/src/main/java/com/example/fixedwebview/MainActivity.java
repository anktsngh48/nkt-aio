package com.example.fixedwebview;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // The one address the app opens. Change this line to point somewhere else.
    private static final String START_URL = "https://www.vedantu.com";

    // Camera and microphone are only given to START_URL's own website (and its subdomains).
    // To also allow another website, add its name here, for example { "example.com" }.
    private static final String[] EXTRA_TRUSTED_HOSTS = {};

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_MEDIA_PERMISSIONS = 1002;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingMediaResources;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Let the page use the area around a camera notch instead of leaving a black strip
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        web = new WebView(this);
        setContentView(web);
        hideStatusBar();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Keep logins working
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {

            // File and photo upload: opens Android's own picker
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }

            // Camera and microphone for pages (video calls, photo capture, voice)
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleMediaRequest(request));
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMediaRequest == request) {
                    pendingMediaRequest = null;
                    pendingMediaResources = null;
                }
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return false; // stay inside the app
                }
                // tel:, mailto:, whatsapp:, upi: and similar open in the matching app
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    // no app can handle it
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    // ---------- Camera and microphone ----------

    private boolean isTrustedOrigin(Uri origin) {
        if (origin == null || !"https".equals(origin.getScheme())) {
            return false;
        }
        String host = origin.getHost();
        if (host == null) {
            return false;
        }
        String base = Uri.parse(START_URL).getHost();
        if (base != null) {
            if (base.startsWith("www.")) {
                base = base.substring(4);
            }
            if (host.equals(base) || host.endsWith("." + base)) {
                return true;
            }
        }
        for (String extra : EXTRA_TRUSTED_HOSTS) {
            if (host.equals(extra) || host.endsWith("." + extra)) {
                return true;
            }
        }
        return false;
    }

    private static String androidPermissionFor(String webResource) {
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(webResource)) {
            return Manifest.permission.CAMERA;
        }
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(webResource)) {
            return Manifest.permission.RECORD_AUDIO;
        }
        return null;
    }

    private void handleMediaRequest(PermissionRequest request) {
        if (!isTrustedOrigin(request.getOrigin())) {
            request.deny();
            return;
        }
        List<String> resources = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String res : request.getResources()) {
            String perm = androidPermissionFor(res);
            if (perm == null) {
                continue;
            }
            resources.add(res);
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
                    && !missing.contains(perm)) {
                missing.add(perm);
            }
        }
        if (resources.isEmpty()) {
            request.deny();
            return;
        }
        String[] resourceArray = resources.toArray(new String[0]);
        if (missing.isEmpty()) {
            request.grant(resourceArray);
            return;
        }
        // Ask the phone's user first, then answer the page in onRequestPermissionsResult
        if (pendingMediaRequest != null) {
            pendingMediaRequest.deny();
        }
        pendingMediaRequest = request;
        pendingMediaResources = resourceArray;
        requestPermissions(missing.toArray(new String[0]), REQ_MEDIA_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MEDIA_PERMISSIONS || pendingMediaRequest == null) {
            return;
        }
        PermissionRequest request = pendingMediaRequest;
        String[] resources = pendingMediaResources;
        pendingMediaRequest = null;
        pendingMediaResources = null;

        List<String> allowed = new ArrayList<>();
        for (String res : resources) {
            String perm = androidPermissionFor(res);
            if (perm != null && checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) {
                allowed.add(res);
            }
        }
        if (allowed.isEmpty()) {
            request.deny();
        } else {
            request.grant(allowed.toArray(new String[0]));
        }
    }

    // ---------- File picker result ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback != null) {
                // The page must always get an answer, even when nothing was picked
                filePathCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------- Status bar, lifecycle, back button ----------

    // Hides the status bar (time, battery, signal). Swiping down from the top edge
    // shows it for a moment, then it hides again.
    private void hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideStatusBar(); // hide it again after coming back to the app
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
