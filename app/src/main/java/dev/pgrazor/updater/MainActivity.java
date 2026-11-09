package dev.pgrazor.updater;

import android.app.*;
import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.content.pm.PackageInfo;
import androidx.core.content.FileProvider;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.View;
import android.view.Gravity;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;
import android.security.keystore.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import com.android.apksig.ApkVerifier;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
  private static final String RELEASE_API = "https://api.github.com/repos/jpitty03/PGRazor/releases/latest";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private DownloadManager downloads;
  private TextView releaseText, statusText, keyText, progressLabel;
  private ProgressBar taskProgress;
  private LinearLayout homeContent, settingsContent;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final Runnable downloadProgressPoll = new Runnable() {
    @Override public void run() {
      if (activeDownload == -1 || downloads == null) return;
      try (Cursor c = downloads.query(new DownloadManager.Query().setFilterById(activeDownload))) {
        if (c != null && c.moveToFirst()) {
          long done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
          long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
          int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
          if (total > 0) {
            taskProgress.setIndeterminate(false);
            taskProgress.setMax(100);
            taskProgress.setProgress((int)Math.min(100, done * 100 / total));
            progressLabel.setText("Downloading · " + android.text.format.Formatter.formatFileSize(MainActivity.this, done)
              + " / " + android.text.format.Formatter.formatFileSize(MainActivity.this, total));
          } else {
            taskProgress.setIndeterminate(true);
            progressLabel.setText("Downloading PGRazor…");
          }
          if (state == DownloadManager.STATUS_RUNNING || state == DownloadManager.STATUS_PENDING)
            mainHandler.postDelayed(this, 500);
        }
      } catch (Exception ignored) { }
    }
  };
  private EditText packageInput, aliasInput, passwordInput, privateKeyPasswordInput;
  private Uri keystoreUri;
  private String apkUrl, tag, expectedDigest;
  private long expectedSize = -1;
  private long activeDownload = -1;
  private boolean verificationInProgress = false;
  private boolean cloneInProgress = false;
  private boolean releaseCheckInProgress = false;
  private BroadcastReceiver receiver;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    downloads = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
    createUi();
    restoreSettings();
    receiver = new BroadcastReceiver() {
      @Override public void onReceive(Context context, Intent intent) {
        if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == activeDownload) downloadComplete();
      }
    };
    activeDownload = getPreferences(0).getLong("downloadId", -1);
    if (Build.VERSION.SDK_INT >= 33)
      registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
    else
      registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    if (activeDownload != -1) inspectPendingDownload();
    checkRelease();
  }

  private void createUi() {
    ScrollView outer = new ScrollView(this);
    outer.setFillViewport(true);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(20), dp(26), dp(20), dp(24));
    root.setBackgroundColor(0xff101018);
    outer.addView(root);

    TextView title = label("PGRazor", 30, 0xffffffff);
    title.setTypeface(null, 1);
    root.addView(title);
    TextView subtitle = label("APK updater & package cloner", 14, 0xffaaa6b8);
    subtitle.setPadding(0, dp(3), 0, dp(20));
    root.addView(subtitle);

    LinearLayout tabs = new LinearLayout(this);
    tabs.setOrientation(LinearLayout.HORIZONTAL);
    tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
    tabs.setBackground(roundRect(0xff1d1b27, 18));
    Button homeTab = tabButton("Updater", true);
    Button settingsTab = tabButton("Settings", false);
    tabs.addView(homeTab, new LinearLayout.LayoutParams(0, dp(44), 1));
    tabs.addView(settingsTab, new LinearLayout.LayoutParams(0, dp(44), 1));
    root.addView(tabs);

    ScrollView pageScroll = new ScrollView(this);
    LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    scrollParams.topMargin = dp(18);
    root.addView(pageScroll, scrollParams);
    LinearLayout pages = new LinearLayout(this);
    pages.setOrientation(LinearLayout.VERTICAL);
    pageScroll.addView(pages);

    homeContent = new LinearLayout(this);
    homeContent.setOrientation(LinearLayout.VERTICAL);
    settingsContent = new LinearLayout(this);
    settingsContent.setOrientation(LinearLayout.VERTICAL);
    pages.addView(homeContent);
    pages.addView(settingsContent);
    settingsContent.setVisibility(View.GONE);

    LinearLayout releaseCard = card();
    homeContent.addView(releaseCard);
    TextView releaseHeading = label("Latest release", 17, 0xffffffff);
    releaseHeading.setTypeface(null, 1);
    releaseCard.addView(releaseHeading);
    releaseText = label("Checking GitHub…", 14, 0xffc5c1d0);
    releaseText.setPadding(0, dp(10), 0, dp(8));
    releaseCard.addView(releaseText);
    addButton(releaseCard, "Check for updates", v -> checkRelease());
    addButton(homeContent, "Download latest APK", v -> downloadLatest());
    addButton(homeContent, "Clone & install downloaded APK", v -> cloneDownloaded());
    addButton(homeContent, "Install original APK", v -> installDownloaded());

    LinearLayout activityCard = card();
    LinearLayout.LayoutParams activityParams = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    activityParams.topMargin = dp(14);
    homeContent.addView(activityCard, activityParams);
    TextView activityHeading = label("Activity", 16, 0xffffffff);
    activityHeading.setTypeface(null, 1);
    activityCard.addView(activityHeading);
    statusText = label("Ready", 13, 0xffc5c1d0);
    statusText.setPadding(0, dp(8), 0, dp(10));
    activityCard.addView(statusText);
    progressLabel = label("", 12, 0xffbcb6cc);
    progressLabel.setVisibility(View.GONE);
    activityCard.addView(progressLabel);
    taskProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    taskProgress.setMax(100);
    taskProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xffa78bfa));
    taskProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff373343));
    taskProgress.setVisibility(View.GONE);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
    progressParams.topMargin = dp(9);
    activityCard.addView(taskProgress, progressParams);

    LinearLayout configCard = card();
    settingsContent.addView(configCard);
    TextView configTitle = label("Clone configuration", 18, 0xffffffff);
    configTitle.setTypeface(null, 1);
    configCard.addView(configTitle);
    TextView configHint = label("Choose a different package ID for the cloned app.", 12, 0xffaaa6b8);
    configHint.setPadding(0, dp(5), 0, dp(12));
    configCard.addView(configHint);
    packageInput = edit("New package name");
    packageInput.setText("com.nianticlabs.pokemongo");
    configCard.addView(packageInput);
    aliasInput = edit("Keystore alias");
    configCard.addView(aliasInput);
    passwordInput = edit("Keystore password");
    passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    configCard.addView(passwordInput);
    privateKeyPasswordInput = edit("Private-key password (blank = same as keystore)");
    privateKeyPasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    configCard.addView(privateKeyPasswordInput);
    addButton(configCard, "Save configuration", v -> saveSettings());

    LinearLayout signingCard = card();
    LinearLayout.LayoutParams signingParams = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    signingParams.topMargin = dp(14);
    settingsContent.addView(signingCard, signingParams);
    TextView signingTitle = label("Signing key", 18, 0xffffffff);
    signingTitle.setTypeface(null, 1);
    signingCard.addView(signingTitle);
    TextView signingHint = label("Import the JKS or keystore used to sign the cloned APK.", 12, 0xffaaa6b8);
    signingHint.setPadding(0, dp(5), 0, dp(10));
    signingCard.addView(signingHint);
    addButton(signingCard, "Choose keystore file", v -> chooseKeystore());
    keyText = label("No keystore selected", 12, 0xffc5c1d0);
    keyText.setPadding(0, dp(8), 0, 0);
    signingCard.addView(keyText);

    TextView warning = label("Cloning changes the manifest package, custom permissions and provider authorities, then rebuilds and signs a separate APK. Native code may still depend on the original package identity.", 12, 0xffffc27a);
    warning.setPadding(dp(2), dp(16), dp(2), 0);
    settingsContent.addView(warning);

    homeTab.setOnClickListener(v -> showTab(true, homeTab, settingsTab));
    settingsTab.setOnClickListener(v -> showTab(false, homeTab, settingsTab));
    setContentView(outer);
  }

  private void showTab(boolean home, Button homeTab, Button settingsTab) {
    homeContent.setVisibility(home ? View.VISIBLE : View.GONE);
    settingsContent.setVisibility(home ? View.GONE : View.VISIBLE);
    styleTab(homeTab, home);
    styleTab(settingsTab, !home);
  }

  private Button tabButton(String text, boolean selected) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setElevation(0);
    styleTab(b, selected);
    return b;
  }

  private void styleTab(Button b, boolean selected) {
    b.setTextColor(selected ? 0xffffffff : 0xffaaa6b8);
    b.setBackground(roundRect(selected ? 0xff6d55c7 : 0x001d1b27, 14));
  }

  private LinearLayout card() {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(16), dp(16), dp(16));
    box.setBackground(roundRect(0xff1b1924, 18));
    return box;
  }

  private android.graphics.drawable.Drawable roundRect(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private void showProgress(String message, boolean indeterminate) {
    progressLabel.setText(message);
    progressLabel.setVisibility(View.VISIBLE);
    taskProgress.setVisibility(View.VISIBLE);
    taskProgress.setIndeterminate(indeterminate);
    if (!indeterminate) taskProgress.setProgress(0);
  }

  private void hideProgress() {
    progressLabel.setVisibility(View.GONE);
    taskProgress.setVisibility(View.GONE);
    taskProgress.setIndeterminate(false);
    taskProgress.setProgress(0);
  }

  private TextView label(String s, int size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    return t;
  }

  private EditText edit(String hint) {
    EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setTextSize(14);
    e.setHint(hint);
    e.setTextColor(0xffffffff);
    e.setHintTextColor(0xffaaa4b5);
    e.setPadding(dp(12), dp(7), dp(12), dp(7));
    e.setBackground(roundRect(0xff292634, 12));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    p.topMargin = dp(9);
    e.setLayoutParams(p);
    return e;
  }

  private void addButton(LinearLayout root, String text, android.view.View.OnClickListener click) {
    Button b = new Button(this);
    b.setText(text);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setTextColor(0xffffffff);
    b.setBackground(roundRect(0xff51418e, 13));
    b.setPadding(dp(12), dp(4), dp(12), dp(4));
    b.setMinHeight(dp(48));
    b.setOnClickListener(click);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    p.topMargin = dp(9);
    root.addView(b, p);
  }

  private int dp(int n) {
    return (int)(n * getResources().getDisplayMetrics().density + .5f);
  }

  private void checkRelease() {
    if (releaseCheckInProgress) return;
    releaseCheckInProgress = true;
    statusText.setText("Checking for the latest release…");
    showProgress("Fetching release information from GitHub…", true);
    worker.execute(() -> {
      try {
        JSONObject release = requestJson(RELEASE_API);
        String foundTag = release.optString("tag_name", "");
        if (foundTag.trim().isEmpty()) throw new IOException("GitHub returned a release without a tag");
        JSONArray assets = release.optJSONArray("assets");
        String foundUrl = null, digest = "";
        long assetSize = 0;
        if (assets != null) for (int i=0; i<assets.length(); i++) {
          JSONObject asset = assets.getJSONObject(i);
          if ("PGRazor.apk".equalsIgnoreCase(asset.optString("name"))) {
            foundUrl = asset.optString("browser_download_url", "");
            assetSize = asset.optLong("size", 0);
            String d = asset.optString("digest", "");
            if (d.startsWith("sha256:")) digest = d.substring(7);
            break;
          }
        }
        if (foundUrl == null || foundUrl.isEmpty()) throw new IOException("Latest release has no PGRazor.apk asset");
        Uri parsedUrl = Uri.parse(foundUrl);
        if (!"https".equalsIgnoreCase(parsedUrl.getScheme()) ||
            parsedUrl.getHost() == null ||
            !(parsedUrl.getHost().equalsIgnoreCase("github.com") ||
              parsedUrl.getHost().endsWith(".githubusercontent.com")))
          throw new SecurityException("GitHub returned an unexpected APK download URL");
        if (assetSize <= 0) throw new IOException("GitHub reports an empty or invalid APK asset");
        if (!digest.isEmpty() && !digest.matches("(?i)[0-9a-f]{64}"))
          throw new IOException("GitHub returned an invalid SHA-256 digest");
        final String u=foundUrl, t=foundTag, d=digest;
        runOnUiThread(() -> {
          releaseCheckInProgress = false;
          apkUrl=u; tag=t; expectedDigest=d;
          expectedSize=assetSize;
          releaseText.setText("Latest: " + t + "\nPGRazor.apk found\nSHA-256: " + (d.isEmpty() ? "not published" : "available"));
          statusText.setText("Release information is up to date");
          hideProgress();
          if (hasCloneConfig()) {
            String cloneKey = t + "|" + packageInput.getText().toString().trim() + "|" + aliasInput.getText().toString().trim() + "|" + keystoreUri;
            String last = getPreferences(0).getString("lastClonedKey", "");
            if (!cloneKey.equals(last)) {
              android.content.SharedPreferences prefs = getPreferences(0);
              String cachedTag = prefs.getString("apkTag", "");
              String cachedUri = prefs.getString("apkUri", null);
              if (t.equals(cachedTag) && cachedUri != null) {
                if (prefs.getBoolean("apkVerified", false)) cloneDownloaded();
                else if (!verificationInProgress) verifyApk(Uri.parse(cachedUri));
              } else if (activeDownload == -1) {
                downloadLatest();
              }
            }
          }
        });
      } catch (Exception e) { runOnUiThread(() -> {
        releaseCheckInProgress = false;
        hideProgress();
        statusText.setText("Release check failed: " + safeMessage(e));
      }); }
    });
  }

  private JSONObject requestJson(String address) throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(15000); c.setReadTimeout(20000);
    c.setRequestProperty("Accept","application/vnd.github+json");
    c.setRequestProperty("User-Agent","PGRazor-Updater");
    try {
      int code = c.getResponseCode();
      InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
      String body = stream == null ? "" : readAll(stream);
      if (code < 200 || code >= 300) {
        String detail = body;
        try { detail = new JSONObject(body).optString("message", body); } catch (Exception ignored) {}
        throw new IOException("GitHub API HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
      }
      return new JSONObject(body);
    } finally { c.disconnect(); }
  }

  private String safeMessage(Throwable error) {
    String message = error == null ? null : error.getMessage();
    if (message == null || message.trim().isEmpty()) return error == null ? "Unknown error" : error.getClass().getSimpleName();
    return message;
  }
  private String readAll(InputStream in) throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
    while ((n=in.read(b))!=-1) out.write(b,0,n);
    return out.toString("UTF-8");
  }

  private void downloadLatest() {
    if (downloads == null) { statusText.setText("Android Download Manager is unavailable"); return; }
    if (apkUrl == null) { checkRelease(); statusText.setText("Release URL not ready; checking again"); return; }
    if (activeDownload != -1) { statusText.setText("A download is already running"); return; }
    try {
    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
    request.setTitle("PGRazor " + tag); request.setDescription("Downloading official release APK");
    request.setMimeType("application/vnd.android.package-archive");
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "PGRazor-" + tag + ".apk");
    activeDownload=downloads.enqueue(request);
    getPreferences(0).edit().putLong("downloadId", activeDownload).putBoolean("apkVerified", false)
      .putString("apkTag", tag).putString("expectedDigest", expectedDigest)
      .putLong("expectedSize", expectedSize).remove("apkUri").apply();
    statusText.setText("Download started");
    showProgress("Starting download…", true);
    mainHandler.removeCallbacks(downloadProgressPoll);
    mainHandler.post(downloadProgressPoll);
    } catch (Exception e) {
      activeDownload = -1;
      getPreferences(0).edit().remove("downloadId").putBoolean("apkVerified", false).apply();
      hideProgress();
      statusText.setText("Could not start download: " + safeMessage(e));
    }
  }

  private void inspectPendingDownload() {
    try (Cursor c = downloads.query(new DownloadManager.Query().setFilterById(activeDownload))) {
      if (c == null || !c.moveToFirst()) {
        activeDownload = -1;
        getPreferences(0).edit().remove("downloadId").putBoolean("apkVerified", false).apply();
        statusText.setText("Previous download record is no longer available. Download again.");
        return;
      }
      int state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      if (state == DownloadManager.STATUS_SUCCESSFUL || state == DownloadManager.STATUS_FAILED) downloadComplete();
      else { showProgress("Resuming download…", true); mainHandler.post(downloadProgressPoll); }
    } catch (Exception e) { statusText.setText("Could not resume download: " + safeMessage(e)); }
  }

  private void downloadComplete() {
    long completedId = activeDownload;
    activeDownload = -1;
    getPreferences(0).edit().remove("downloadId").apply();
    DownloadManager.Query query=new DownloadManager.Query().setFilterById(completedId);
    mainHandler.removeCallbacks(downloadProgressPoll);
    try (Cursor c=downloads.query(query)) {
      if (c==null || !c.moveToFirst()) throw new Exception("Download record missing");
      int state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      if (state!=DownloadManager.STATUS_SUCCESSFUL) {
        int reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
        throw new IOException(downloadFailureReason(state, reason));
      }
      Uri uri=downloads.getUriForDownloadedFile(completedId);
      if (uri == null) throw new IOException("Download completed but Android did not provide an APK file");
      getPreferences(0).edit().putString("apkUri",uri.toString()).putBoolean("apkVerified",false).apply();
      showProgress("Download complete · verifying APK integrity…", true);
      verifyApk(uri);
    } catch(Exception e) {
      getPreferences(0).edit().putBoolean("apkVerified", false).remove("apkUri").apply();
      hideProgress();
      statusText.setText("Download error: " + safeMessage(e));
    }
  }

  private String downloadFailureReason(int state, int reason) {
    if (state == DownloadManager.STATUS_PAUSED) {
      switch (reason) {
        case DownloadManager.PAUSED_WAITING_FOR_NETWORK: return "Download paused: waiting for a network connection";
        case DownloadManager.PAUSED_WAITING_TO_RETRY: return "Download paused: Android will retry shortly";
        case DownloadManager.PAUSED_QUEUED_FOR_WIFI: return "Download paused: waiting for Wi-Fi";
        case DownloadManager.PAUSED_UNKNOWN: return "Download paused for an unknown reason";
      }
    }
    switch (reason) {
      case DownloadManager.ERROR_CANNOT_RESUME: return "Download failed: Android could not resume it";
      case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "Download failed: destination storage is unavailable";
      case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "Download failed: destination file already exists";
      case DownloadManager.ERROR_FILE_ERROR: return "Download failed: storage/file-system error";
      case DownloadManager.ERROR_HTTP_DATA_ERROR: return "Download failed: HTTP data error";
      case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "Download failed: not enough storage space";
      case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "Download failed: too many redirects";
      case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "Download failed: HTTP error " + reason;
      case DownloadManager.ERROR_UNKNOWN: return "Download failed: unknown Download Manager error";
      default: return "Download failed (status " + state + ", reason " + reason + ")";
    }
  }

  private void verifyApk(Uri uri) {
    if (verificationInProgress) return;
    verificationInProgress = true;
    showProgress("Verifying APK SHA-256…", true);
    worker.execute(() -> {
      File validationCopy = new File(getCacheDir(), "PGRazor-verify-" + System.currentTimeMillis() + ".apk");
      try (InputStream in=getContentResolver().openInputStream(uri);
           OutputStream out=new FileOutputStream(validationCopy)) {
        if (in==null) throw new IOException("Cannot open downloaded APK");
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        byte[] b=new byte[16384]; int n;
        while((n=in.read(b))!=-1) { md.update(b,0,n); out.write(b,0,n); }
        out.flush();
        StringBuilder hex=new StringBuilder();
        for(byte x:md.digest()) hex.append(String.format(Locale.US,"%02x",x));
        String actual=hex.toString();
        String expected=getPreferences(0).getString("expectedDigest", expectedDigest);
        long publishedSize=getPreferences(0).getLong("expectedSize", expectedSize);
        if(publishedSize > 0 && validationCopy.length() != publishedSize)
          throw new SecurityException("APK size mismatch. Expected " + publishedSize + " bytes but received " + validationCopy.length() + ".");
        if(expected!=null && !expected.isEmpty() && !actual.equalsIgnoreCase(expected))
          throw new SecurityException("SHA-256 mismatch. The downloaded APK does not match GitHub's published digest.");
        if(validationCopy.length() < 1024) throw new IOException("Downloaded file is too small to be a valid APK");
        validateApkArchive(validationCopy);
        ApkVerifier.Result signature = new ApkVerifier.Builder(validationCopy).build().verify();
        if (!signature.isVerified())
          throw new SecurityException("Original APK signature is invalid: " + signature.getErrors());
        PackageInfo parsed = getPackageManager().getPackageArchiveInfo(validationCopy.getAbsolutePath(), 0);
        if (parsed == null || parsed.packageName == null || parsed.packageName.trim().isEmpty())
          throw new SecurityException("Android could not parse the downloaded APK manifest");
        final String verifiedPackage = parsed.packageName;
        runOnUiThread(() -> {
          verificationInProgress = false;
          hideProgress();
          String digestNote = expected == null || expected.isEmpty() ? " (GitHub did not publish a reference digest)" : "";
          statusText.setText("APK verified · " + verifiedPackage + " · SHA-256: " + actual + digestNote);
          getPreferences(0).edit().putBoolean("apkVerified", true).putString("apkSha256", actual)
            .putString("apkPackage", verifiedPackage).apply();
          if (hasCloneConfig()) cloneDownloaded();
        });
      } catch(Exception e) {
        runOnUiThread(() -> {
          verificationInProgress = false;
          getPreferences(0).edit().putBoolean("apkVerified", false).remove("apkUri").apply();
          hideProgress();
          statusText.setText("APK verification failed: " + safeMessage(e));
        });
      } finally { //noinspection ResultOfMethodCallIgnored
        validationCopy.delete();
      }
    });
  }

  private void validateApkArchive(File apk) throws Exception {
    boolean manifest = false, dex = false;
    try (ZipFile zip = new ZipFile(apk)) {
      java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if ("AndroidManifest.xml".equals(name)) manifest = true;
        if (name.matches("classes([0-9]+)?\\.dex")) dex = true;
      }
    } catch (Exception e) { throw new IOException("APK archive is corrupt or unreadable", e); }
    if (!manifest) throw new IOException("APK is missing AndroidManifest.xml");
    if (!dex) throw new IOException("APK contains no DEX bytecode");
  }

  private void cloneDownloaded() {
    if (cloneInProgress) { statusText.setText("A clone operation is already running"); return; }
    String source = getPreferences(0).getString("apkUri", null);
    String pkg = packageInput.getText().toString().trim();
    String keyAlias = aliasInput.getText().toString().trim();
    String storePassword = passwordInput.getText().toString();
    String enteredKeyPassword = privateKeyPasswordInput.getText().toString();
    final String keyPassword = enteredKeyPassword.isEmpty() ? storePassword : enteredKeyPassword;
    if (source == null) { statusText.setText("Download the official APK first"); return; }
    if (!getPreferences(0).getBoolean("apkVerified", false)) {
      statusText.setText("Wait for APK verification to finish"); return;
    }
    if (keystoreUri == null || keyAlias.isEmpty() || storePassword.isEmpty()) {
      statusText.setText("Import your keystore and enter its alias and password"); return;
    }
    if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
      statusText.setText("Enter a valid lowercase package name"); return;
    }
    if (pkg.equals(getPackageName())) {
      statusText.setText("The clone package cannot match PGRazor Updater"); return;
    }
    cloneInProgress = true;
    statusText.setText("Preparing APK clone…");
    showProgress("Building and signing cloned APK…", true);
    worker.execute(() -> {
      try {
        File work = new File(getFilesDir(), "clone-work");
        File output = ApkCloneEngine.cloneAndSign(getContentResolver(), Uri.parse(source),
          keystoreUri, keyAlias, storePassword, keyPassword, pkg, work,
          message -> runOnUiThread(() -> statusText.setText(message)));
        validateApkArchive(output);
        ApkVerifier.Result outputSignature = new ApkVerifier.Builder(output).build().verify();
        if (!outputSignature.isVerified())
          throw new SecurityException("Signed clone failed independent signature verification: " + outputSignature.getErrors());
        PackageInfo info = getPackageManager().getPackageArchiveInfo(output.getAbsolutePath(), 0);
        if (info == null || !pkg.equals(info.packageName))
          throw new SecurityException("Cloned APK package name verification failed");
        if (output.length() < 1024) throw new IOException("Cloned APK output is unexpectedly small");
        runOnUiThread(() -> {
          try {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", output);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setData(contentUri);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String cloneKey = getPreferences(0).getString("apkTag", tag == null ? "" : tag)
              + "|" + pkg + "|" + keyAlias + "|" + keystoreUri;
            getPreferences(0).edit().putString("pendingCloneKey", cloneKey).apply();
            startActivityForResult(install, 45);
          } catch (Exception e) {
            cloneInProgress = false;
            hideProgress();
            statusText.setText("Could not open installer: " + safeMessage(e));
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> {
          cloneInProgress = false;
          hideProgress();
          statusText.setText("Clone failed: " + safeMessage(e));
        });
      }
    });
  }

  private void installDownloaded() {
    String saved=getPreferences(0).getString("apkUri",null);
    if(saved==null) { statusText.setText("Download the release first"); return; }
    if (!getPreferences(0).getBoolean("apkVerified", false)) {
      statusText.setText("APK has not passed integrity and signature verification"); return;
    }
    Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
    intent.setData(Uri.parse(saved)); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try { startActivity(intent); } catch(Exception e) { statusText.setText("Installer error: " + safeMessage(e)); }
  }

  private void chooseKeystore() {
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 44);
  }
  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if (request == 45) {
      cloneInProgress = false;
      String pending = getPreferences(0).getString("pendingCloneKey", null);
      if (result == RESULT_OK && pending != null) {
        getPreferences(0).edit().putString("lastClonedKey", pending).remove("pendingCloneKey").apply();
        statusText.setText("Clone installation completed");
      } else {
        getPreferences(0).edit().remove("pendingCloneKey").apply();
        hideProgress();
        statusText.setText(result == RESULT_CANCELED
          ? "Installation was cancelled. The verified clone is still available."
          : "Installer closed without confirming installation. Check the app list before retrying.");
      }
      return;
    }
    if(request==44 && result==RESULT_OK && data!=null && data.getData()!=null) {
      try {
        Uri selected = data.getData();
        getContentResolver().takePersistableUriPermission(selected,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try (InputStream in = getContentResolver().openInputStream(selected)) {
          if (in == null) throw new IOException("Selected keystore cannot be opened");
          if (in.read() == -1) throw new IOException("Selected keystore file is empty");
        }
        keystoreUri=selected;
        keyText.setText("Selected: " + displayName(keystoreUri));
        getPreferences(0).edit().putString("keyUri",keystoreUri.toString()).apply();
        statusText.setText("Keystore file selected. Alias and password will be checked during signing.");
      } catch (Exception e) {
        keystoreUri = null;
        keyText.setText("Keystore selection failed");
        statusText.setText("Cannot use selected keystore: " + safeMessage(e));
      }
    }
  }
  private String displayName(Uri uri) {
    try(Cursor c=getContentResolver().query(uri,null,null,null,null)) {
      if(c!=null && c.moveToFirst()) { int col=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(col>=0) return c.getString(col); }
    } catch(Exception ignored) {}
    return uri.getLastPathSegment();
  }

  private void restoreSettings() {
    android.content.SharedPreferences p=getPreferences(0);
    tag = p.getString("apkTag", null);
    expectedDigest = p.getString("expectedDigest", null);
    expectedSize = p.getLong("expectedSize", -1);
    packageInput.setText(p.getString("packageName", "com.nianticlabs.pokemongo"));
    aliasInput.setText(p.getString("alias",""));
    String u=p.getString("keyUri",null);
    if(u!=null) { keystoreUri=Uri.parse(u); keyText.setText("Selected: " + displayName(keystoreUri)); }
    try { passwordInput.setText(decrypt(p.getString("secret",null))); }
    catch(Exception e) { p.edit().remove("secret").remove("keySecret").apply(); statusText.setText("Saved passwords could not be decrypted. Please enter them again."); }
    try { privateKeyPasswordInput.setText(decrypt(p.getString("keySecret",null))); } catch(Exception ignored) {}
  }
  private boolean hasCloneConfig() {
    return packageInput != null
      && !packageInput.getText().toString().trim().isEmpty()
      && aliasInput != null && !aliasInput.getText().toString().trim().isEmpty()
      && passwordInput != null && !passwordInput.getText().toString().isEmpty()
      && keystoreUri != null;
  }

  private void saveSettings() {
    String pkg=packageInput.getText().toString().trim();
    if(!pkg.isEmpty() && !pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
      statusText.setText("Invalid Android package name"); return;
    }
    try {
      String encrypted=encrypt(passwordInput.getText().toString());
      String encryptedKey=encrypt(privateKeyPasswordInput.getText().toString());
      getPreferences(0).edit().putString("packageName",pkg)
        .putString("alias",aliasInput.getText().toString().trim()).putString("secret",encrypted)
        .putString("keySecret",encryptedKey).apply();
      statusText.setText("Settings saved on this device");
      checkRelease();
    } catch(Exception e) { statusText.setText("Could not protect password: " + e.getMessage()); }
  }

  private SecretKey configKey() throws Exception {
    KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
    String name="pgrazor-updater-config";
    if(ks.containsAlias(name)) return ((KeyStore.SecretKeyEntry)ks.getEntry(name,null)).getSecretKey();
    KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
    gen.init(new KeyGenParameterSpec.Builder(name,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
    return gen.generateKey();
  }
  private String encrypt(String value) throws Exception {
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,configKey());
    byte[] encrypted=cipher.doFinal(value.getBytes("UTF-8")), iv=cipher.getIV();
    ByteArrayOutputStream out=new ByteArrayOutputStream(); out.write(iv); out.write(encrypted);
    return android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
  }
  private String decrypt(String encoded) throws Exception {
    if(encoded==null) return "";
    byte[] all=android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP);
    byte[] iv=new byte[12], encrypted=new byte[all.length-12];
    System.arraycopy(all,0,iv,0,12); System.arraycopy(all,12,encrypted,0,encrypted.length);
    Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE,configKey(),new GCMParameterSpec(128,iv));
    return new String(cipher.doFinal(encrypted),"UTF-8");
  }

  @Override protected void onDestroy() {
    if(receiver!=null) unregisterReceiver(receiver);
    mainHandler.removeCallbacks(downloadProgressPoll);
    worker.shutdownNow(); super.onDestroy();
  }
}
