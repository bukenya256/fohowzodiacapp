package ug.fohowzodiac.admin;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {

    // ---- Security settings (change these numbers if you want) ----
    /** If the app was away longer than this, the admin is also signed out (must type the password again). */
    private static final long AUTO_SIGNOUT_MS = 5 * 60 * 1000L;
    /** Minimum time the logo screen stays visible when the app opens. */
    private static final long MIN_SPLASH_MS = 1700L;

    private static final int FILE_REQUEST = 1001;
    private static final int AUTH_REQUEST = 1002;
    private static final int WINE = 0xFF440000;
    private static final int GOLD = 0xFFE0A100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView webView;
    private ProgressBar progress;
    private View lockLayer;
    private TextView lockMsg;
    private Button unlockBtn;

    private ValueCallback<Uri[]> filePathCallback;
    private String adminHost;

    private boolean locked = true;          // the app always starts locked
    private boolean authenticating = false; // phone-unlock screen is showing
    private boolean awaitTap = false;       // user cancelled; wait for the Unlock button
    private boolean externalTrip = false;   // photo picker / external link is open (do not lock)
    private boolean forceLock = false;      // screen was switched off
    private long startedAt;
    private long leftAt = 0;

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { forceLock = true; }
    };

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Blocks screenshots, screen recording and the preview in the recent-apps list.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        startedAt = SystemClock.elapsedRealtime();
        adminHost = Uri.parse(BuildConfig.ADMIN_URL).getHost();

        root = new FrameLayout(this);
        root.setBackgroundColor(WINE);

        webView = new WebView(this);
        webView.setVisibility(View.INVISIBLE);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3)));

        buildLockLayer();
        root.addView(lockLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        setupWebView();
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(BuildConfig.ADMIN_URL);
    }

    /* ------------------------------------------------------------ lock / splash screen */

    /** Draws the two thin gold circles seen behind the logo in the website hero. */
    private static class Circles extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Circles(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(Color.argb(46, 224, 161, 0));
            p.setStrokeWidth(2f);
        }
        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            cv.drawCircle(-w * 0.05f, -h * 0.02f, w * 0.95f, p);
            cv.drawCircle(w * 1.05f, h * 1.02f, w * 0.80f, p);
        }
    }

    private void buildLockLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(WINE);
        layer.setClickable(true);   // swallow every touch while locked
        layer.setFocusable(true);
        layer.addView(new Circles(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setPadding(dp(24), dp(24), dp(24), dp(24));

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int logoW = Math.min((int) (dm.widthPixels * 0.90f), dp(560));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.hero_logo);   // same hero logo as the website
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        col.addView(logo, new LinearLayout.LayoutParams(logoW, LinearLayout.LayoutParams.WRAP_CONTENT));

        lockMsg = new TextView(this);
        lockMsg.setTextColor(Color.argb(190, 255, 255, 255));
        lockMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        lockMsg.setGravity(Gravity.CENTER);
        lockMsg.setPadding(dp(8), dp(10), dp(8), dp(18));
        lockMsg.setText("Administrator dashboard");
        col.addView(lockMsg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        unlockBtn = new Button(this);
        unlockBtn.setText("UNLOCK");
        unlockBtn.setAllCaps(true);
        unlockBtn.setTypeface(Typeface.DEFAULT_BOLD);
        unlockBtn.setLetterSpacing(0.12f);
        unlockBtn.setTextColor(WINE);
        unlockBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GOLD);
        bg.setCornerRadius(dp(6));
        unlockBtn.setBackground(bg);
        unlockBtn.setPadding(dp(44), dp(14), dp(44), dp(14));
        unlockBtn.setVisibility(View.INVISIBLE);
        unlockBtn.setOnClickListener(v -> { awaitTap = false; requestUnlock(); });
        col.addView(unlockBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)));

        layer.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        lockLayer = layer;
    }

    private void lockNow() {
        if (locked) return;
        locked = true;
        awaitTap = false;
        leftAt = SystemClock.elapsedRealtime();
        lockLayer.setVisibility(View.VISIBLE);
        webView.setVisibility(View.INVISIBLE);
        webView.onPause();
        lockMsg.setText("Locked for your security");
        unlockBtn.setVisibility(View.INVISIBLE);
    }

    private void unlock() {
        locked = false;
        awaitTap = false;
        forceLock = false;
        // Away for a long time? Sign the admin out too, so the password is needed again.
        if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > AUTO_SIGNOUT_MS) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            webView.loadUrl(BuildConfig.ADMIN_URL);
        }
        webView.onResume();
        webView.setVisibility(View.VISIBLE);
        lockLayer.setVisibility(View.GONE);
    }

    private void showLockedMessage(String msg) {
        lockMsg.setText(msg);
        unlockBtn.setVisibility(View.VISIBLE);
    }

    /** Asks for the phone's own PIN / pattern / password / fingerprint. */
    private void requestUnlock() {
        if (!locked || authenticating) return;
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        Intent i = null;
        if (km != null && km.isKeyguardSecure()) {
            i = km.createConfirmDeviceCredentialIntent("Zodiac Admin", "Confirm it's you to open the admin dashboard");
        }
        if (i == null) {
            showLockedMessage("For your security, set a screen lock (PIN, pattern or fingerprint) in your phone Settings, then tap Unlock.");
            return;
        }
        authenticating = true;
        try {
            startActivityForResult(i, AUTH_REQUEST);
        } catch (Exception e) {
            authenticating = false;
            showLockedMessage("Could not open the phone unlock screen. Tap Unlock to try again.");
        }
    }

    /* ------------------------------------------------------------------- lifecycle */

    @Override
    protected void onResume() {
        super.onResume();
        externalTrip = false;
        if (forceLock) lockNow();
        if (locked && !authenticating && !awaitTap) {
            long wait = Math.max(0, MIN_SPLASH_MS - (SystemClock.elapsedRealtime() - startedAt));
            handler.postDelayed(this::requestUnlock, wait);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The app left the screen (Home, Recents, another app, screen off) -> lock it.
        if (!authenticating && !externalTrip) {
            if (locked) awaitTap = false; // came back later: ask for the phone unlock automatically
            else lockNow();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(screenOff); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == AUTH_REQUEST) {
            authenticating = false;
            if (resultCode == RESULT_OK) {
                unlock();
            } else {
                awaitTap = true;
                showLockedMessage("Locked for your security");
            }
            return;
        }
        if (requestCode == FILE_REQUEST && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (locked) { moveTaskToBack(true); return; }
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /* --------------------------------------------------------------------- webview */

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSaveFormData(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webView.setBackgroundColor(0xFFFAF6F1);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                if (host != null && host.equals(adminHost)) return false; // stay inside the app
                try {
                    externalTrip = true;
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) { externalTrip = false; }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                            + "<body style='font-family:sans-serif;background:#440000;color:#fff;text-align:center;padding:15vh 24px'>"
                            + "<h2>Can't reach the admin page</h2>"
                            + "<p style='opacity:.8'>Check your internet connection and try again.</p>"
                            + "<p><a href='" + BuildConfig.ADMIN_URL + "' style='display:inline-block;background:#e0a100;color:#440000;"
                            + "padding:13px 34px;border-radius:6px;text-decoration:none;font-weight:bold;letter-spacing:.1em'>RETRY</a></p>"
                            + "</body></html>";
                    view.loadDataWithBaseURL(BuildConfig.ADMIN_URL, html, "text/html", "UTF-8", null);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
            }

            // Lets the admin upload product / staff photos from the phone
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    externalTrip = true;
                    startActivityForResult(params.createIntent(), FILE_REQUEST);
                } catch (Exception e) {
                    externalTrip = false;
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }
}
