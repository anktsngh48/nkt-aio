package com.example.fixedwebview;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SafeBrowsingResponse;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // The one address the app opens. Change this line to point somewhere else.
    private static final String START_URL = "https://anktsngh48.github.io/nkt-sankra/";

    // Camera and microphone are only given to START_URL's own website (and its subdomains).
    // To also allow another website, add its name here, for example { "example.com" }.
    private static final String[] EXTRA_TRUSTED_HOSTS = {};

    // Android does not tell us the file name a page gave to a file it built itself,
    // so this small script remembers the name from the page's download link.
    private static final String DOWNLOAD_HOOK_JS = "(function(){"
            + "if(window.__dlHook){return;}window.__dlHook=true;"
            + "function note(a){try{if(a&&a.hasAttribute&&a.hasAttribute('download')){"
            + "window.__lastDownloadName=a.getAttribute('download')||'';}}catch(e){}}"
            + "var oc=HTMLAnchorElement.prototype.click;"
            + "HTMLAnchorElement.prototype.click=function(){note(this);return oc.apply(this,arguments);};"
            + "var od=EventTarget.prototype.dispatchEvent;"
            + "EventTarget.prototype.dispatchEvent=function(ev){"
            + "try{if(ev&&ev.type==='click'){note(this);}}catch(e){}"
            + "return od.apply(this,arguments);};"
            + "document.addEventListener('click',function(e){var t=e.target;"
            + "while(t&&t.tagName!=='A'){t=t.parentElement;}note(t);},true);"
            + "})();";

    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_MEDIA_PERMISSIONS = 1002;
    private static final int REQ_STORAGE_PERMISSION = 1003;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private PermissionRequest pendingMediaRequest;
    private String[] pendingMediaResources;
    private Runnable pendingAfterStoragePermission;

    // The only name ever shown to the person in pop-ups and error pages (never a web address)
    private static final String BRAND = "NKT";

    private FrameLayout root;
    private String failedUrl;            // kept only so "Try again" works, never shown
    private boolean clearHistoryAfterRetry;
    private boolean skipStateSave;

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
        root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideStatusBar();

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Keep logins working
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        // Lets files that a page builds itself (blob: links) be saved
        web.addJavascriptInterface(new DownloadBridge(), "AndroidDownload");

        // File downloads: saved into the phone's Downloads folder
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                handleDownload(url, userAgent, contentDisposition, mimeType));

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

            // Page pop-ups. Android's own versions print the page address in the title,
            // so they are replaced by our own, titled with the brand name and shown at the top.
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(BRAND)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, which) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .create();
                showAtTop(dialog);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(BRAND)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, which) -> result.confirm())
                        .setNegativeButton("Cancel", (d, which) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .create();
                showAtTop(dialog);
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setSingleLine(true);
                input.setText(defaultValue);
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(BRAND)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("OK",
                                (d, which) -> result.confirm(input.getText().toString()))
                        .setNegativeButton("Cancel", (d, which) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .create();
                showAtTop(dialog);
                return true;
            }

            @Override
            public boolean onJsBeforeUnload(WebView view, String url, String message,
                                            JsResult result) {
                String text = (message == null || message.isEmpty())
                        ? "Changes you made may not be saved."
                        : message;
                AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(BRAND)
                        .setMessage(text)
                        .setPositiveButton("Leave", (d, which) -> result.confirm())
                        .setNegativeButton("Stay", (d, which) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .create();
                showAtTop(dialog);
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
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                view.evaluateJavascript(DOWNLOAD_HOOK_JS, null);
                // After "Try again", forget the error page so Back does not return to it
                if (clearHistoryAfterRetry && url != null && !url.startsWith("data:")
                        && !"about:blank".equals(url)) {
                    clearHistoryAfterRetry = false;
                    view.clearHistory();
                }
            }

            // No internet, server not found, timeouts and similar
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (!request.isForMainFrame()) {
                    return;
                }
                String scheme = request.getUrl().getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    return;
                }
                failedUrl = request.getUrl().toString();
                int code = error.getErrorCode();
                boolean network = code == ERROR_HOST_LOOKUP || code == ERROR_CONNECT
                        || code == ERROR_TIMEOUT || code == ERROR_IO;
                showErrorPage(network
                        ? "No internet connection, or " + BRAND
                                + " can't be reached right now. Check your connection and try again."
                        : BRAND + " couldn't load this page. Please try again.");
            }

            // The website answered with an error such as 404 or 500
            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                super.onReceivedHttpError(view, request, errorResponse);
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    failedUrl = request.getUrl().toString();
                    showErrorPage(BRAND + " can't show this page right now (error "
                            + errorResponse.getStatusCode() + "). Please try again.");
                }
            }

            // The built-in "dangerous site" warning prints the address, so it is replaced
            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request,
                                          int threatType, SafeBrowsingResponse callback) {
                callback.backToSafety(true);
                view.post(() -> showErrorPage(
                        BRAND + " blocked this page because it may not be safe."));
            }

            // The page's engine crashed: restart cleanly instead of closing the app
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                skipStateSave = true;
                recreate();
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("nkt".equals(scheme)) { // the "Try again" button on our error page
                    retryAfterError();
                    return true;
                }
                if ("http".equals(scheme) || "https".equals(scheme)
                        || "blob".equals(scheme) || "data".equals(scheme)
                        || "about".equals(scheme)) {
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

    // ---------- Error page, top pop-ups ----------

    private void showErrorPage(final String message) {
        final String html = "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;min-height:100vh;display:flex;flex-direction:column;"
                + "align-items:center;justify-content:center;font-family:sans-serif;"
                + "background:#fff;color:#14283b;text-align:center;padding:24px;"
                + "box-sizing:border-box}"
                + "h1{font-size:30px;margin:0 0 12px}"
                + "p{font-size:16px;line-height:1.5;margin:0 0 24px;color:#64768a;max-width:420px}"
                + "a{display:inline-block;padding:12px 28px;border-radius:10px;"
                + "background:#0a8a96;color:#fff;text-decoration:none;font-weight:600}"
                + "</style></head><body><h1>" + BRAND + "</h1><p>" + message
                + "</p><a href='nkt://retry'>Try again</a></body></html>";
        web.post(() -> web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null));
    }

    private void retryAfterError() {
        clearHistoryAfterRetry = true;
        web.loadUrl(failedUrl != null ? failedUrl : START_URL);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // Shows a dialog near the top of the screen instead of the middle
    private void showAtTop(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.y = dp(24);
            window.setAttributes(lp);
        }
        dialog.show();
    }

    // A short message bar at the top of the screen (used instead of bottom pop-ups)
    private void showTopMessage(final String message) {
        runOnUiThread(() -> {
            final TextView bar = new TextView(this);
            bar.setText(message);
            bar.setTextColor(Color.WHITE);
            bar.setTextSize(14);
            bar.setPadding(dp(16), dp(10), dp(16), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xE6222222);
            background.setCornerRadius(dp(12));
            bar.setBackground(background);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(24);
            lp.leftMargin = dp(16);
            lp.rightMargin = dp(16);
            root.addView(bar, lp);
            bar.postDelayed(() -> root.removeView(bar), 3000);
        });
    }

    // ---------- File downloads ----------

    private void handleDownload(final String url, final String userAgent,
                                String contentDisposition, String mimeType) {
        boolean normalLink = url.startsWith("http://") || url.startsWith("https://");
        final String filename = URLUtil.guessFileName(
                normalLink ? url : "download", contentDisposition, mimeType);

        if (url.startsWith("blob:")) {
            fetchBlob(url, mimeType, filename);
        } else if (url.startsWith("data:")) {
            saveDataUrl(url, filename);
        } else {
            final String type = mimeType;
            withStoragePermission(() -> enqueueDownload(url, userAgent, filename, type));
        }
    }

    // Normal links: the phone's download manager does the work and shows a notification
    private void enqueueDownload(String url, String userAgent, String filename, String mimeType) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            if (mimeType != null && !mimeType.isEmpty()) {
                req.setMimeType(mimeType);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) {
                req.addRequestHeader("Cookie", cookie); // keeps logged-in downloads working
            }
            if (userAgent != null) {
                req.addRequestHeader("User-Agent", userAgent);
            }
            req.setTitle(filename);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            manager.enqueue(req);
            showTopMessage("Downloading " + filename);
        } catch (Exception e) {
            showTopMessage("Could not download this file");
        }
    }

    // blob: links only exist inside the page, so the page itself hands the bytes over
    private void fetchBlob(String blobUrl, String mime, String filename) {
        String safeUrl = blobUrl.replace("\\", "").replace("'", "%27");
        String safeMime = (mime == null || mime.isEmpty())
                ? "application/octet-stream"
                : mime.replaceAll("[^A-Za-z0-9./+;=_-]", "");
        String safeName = filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        String js = "(function(){"
                + "var x=new XMLHttpRequest();"
                + "x.open('GET','" + safeUrl + "',true);"
                + "x.responseType='blob';"
                + "x.onload=function(){"
                + "var r=new FileReader();"
                + "r.onloadend=function(){"
                + "var d=r.result;"
                + "var n=window.__lastDownloadName||'" + safeName + "';"
                + "window.__lastDownloadName='';"
                + "AndroidDownload.save(d.substring(d.indexOf(',')+1),"
                + "x.response.type||'" + safeMime + "',n);"
                + "};"
                + "r.readAsDataURL(x.response);"
                + "};"
                + "x.send();"
                + "})();";
        web.evaluateJavascript(js, null);
    }

    private void saveDataUrl(String url, String filename) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) {
                return;
            }
            String meta = url.substring(5, comma);
            String payload = url.substring(comma + 1);
            boolean isBase64 = meta.endsWith(";base64");
            String mime = meta.replace(";base64", "");
            int semi = mime.indexOf(';');
            if (semi >= 0) {
                mime = mime.substring(0, semi);
            }
            byte[] bytes = isBase64
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
            saveFile(filename, mime, bytes);
        } catch (Exception e) {
            showTopMessage("Could not save this file");
        }
    }

    // The page calls this (through DownloadBridge) with a file it built itself
    public class DownloadBridge {
        @JavascriptInterface
        public void save(String base64, String mimeType, String filename) {
            byte[] decoded = null;
            try {
                decoded = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception ignored) {
                // bad data
            }
            if (decoded == null) {
                return;
            }
            final byte[] bytes = decoded;
            runOnUiThread(() -> {
                // Only the app's own website may save files this way
                if (!isTrustedOrigin(Uri.parse(String.valueOf(web.getUrl())))) {
                    return;
                }
                saveFile(filename, mimeType, bytes);
            });
        }
    }

    private void saveFile(String rawName, String mime, final byte[] data) {
        String cleanName = (rawName == null || rawName.isEmpty())
                ? "download"
                : rawName.replaceAll("[\\\\/:*?\"<>|]", "_");
        cleanName = fixExtension(cleanName, data); // e.g. .bin becomes .xlsx when the file is Excel
        final String name = cleanName;
        String guessedType = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        if (guessedType.startsWith("application/octet-stream")) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String fromName = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
                if (fromName != null) {
                    guessedType = fromName;
                }
            }
        }
        final String type = guessedType;
        withStoragePermission(() -> new Thread(() -> {
            final boolean ok = saveToDownloads(name, type, data);
            showTopMessage(ok ? "Saved to Downloads: " + name : "Could not save " + name);
        }).start());
    }

    // If the file has no real ending (or only .bin), look inside it to find out what it is
    private static String fixExtension(String name, byte[] data) {
        int dot = name.lastIndexOf('.');
        boolean noExtension = dot <= 0;
        if (!noExtension && !name.toLowerCase().endsWith(".bin")) {
            return name;
        }
        String ext = sniffExtension(data);
        if (ext == null) {
            return name;
        }
        String base = noExtension ? name : name.substring(0, dot);
        return base + ext;
    }

    private static String sniffExtension(byte[] d) {
        if (d == null || d.length < 4) {
            return null;
        }
        if (d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F') {
            return ".pdf";
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return ".jpg";
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return ".png";
        }
        if (d[0] == 'P' && d[1] == 'K') {
            // Excel, Word and PowerPoint files are zip files; the names inside tell them apart
            int n = Math.min(d.length, 8192);
            String head = new String(d, 0, n, StandardCharsets.ISO_8859_1);
            String tail = new String(d, d.length - n, n, StandardCharsets.ISO_8859_1);
            String both = head + tail;
            if (both.contains("xl/")) {
                return ".xlsx";
            }
            if (both.contains("word/")) {
                return ".docx";
            }
            if (both.contains("ppt/")) {
                return ".pptx";
            }
            return ".zip";
        }
        return null;
    }

    private boolean saveToDownloads(String filename, String mime, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return false;
                }
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        return false;
                    }
                    out.write(data);
                }
                return true;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                File file = uniqueFile(dir, filename);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                MediaScannerConnection.scanFile(
                        this, new String[]{file.getAbsolutePath()}, null, null);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static File uniqueFile(File dir, String filename) {
        File file = new File(dir, filename);
        if (!file.exists()) {
            return file;
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        int n = 1;
        while (file.exists()) {
            file = new File(dir, base + " (" + n + ")" + ext);
            n++;
        }
        return file;
    }

    // Android 9 and older need the phone's storage permission to save into Downloads.
    // Android 10 and newer do not.
    private void withStoragePermission(Runnable action) {
        if (Build.VERSION.SDK_INT >= 29
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingAfterStoragePermission = action;
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                REQ_STORAGE_PERMISSION);
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

        if (requestCode == REQ_STORAGE_PERMISSION) {
            Runnable action = pendingAfterStoragePermission;
            pendingAfterStoragePermission = null;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (action != null && granted) {
                action.run();
            } else if (action != null) {
                showTopMessage("Allow storage access to save files");
            }
            return;
        }

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
        if (!skipStateSave) {
            web.saveState(outState);
        }
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
