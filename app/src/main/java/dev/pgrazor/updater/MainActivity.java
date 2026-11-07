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
import android.widget.*;
import android.security.keystore.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.Locale;
import java.util.concurrent.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
  private static final String RELEASE_API = "https://api.github.com/repos/jpitty03/PGRazor/releases/latest";
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private DownloadManager downloads;
  private TextView releaseText, statusText, keyText;
  private EditText packageInput, aliasInput, passwordInput;
  private Uri keystoreUri;
  private String apkUrl, tag, expectedDigest;
  private long activeDownload = -1;
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
    registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
    checkRelease();
  }

  private void createUi() {
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    int pad = dp(20); root.setPadding(pad, dp(28), pad, pad);
    root.setBackgroundColor(0xff111018); scroll.addView(root);
    TextView title = label("PGRazor Updater", 28, 0xffffffff); title.setTypeface(null, 1); root.addView(title);
    root.addView(label("Official release checker and APK installer", 14, 0xffbdb8c8));
    releaseText = label("Checking GitHub…", 16, 0xffffffff); releaseText.setPadding(0,dp(24),0,dp(8)); root.addView(releaseText);
    addButton(root, "Check for updates", v -> checkRelease());
    addButton(root, "Download latest PGRazor APK", v -> downloadLatest());
    addButton(root, "Clone & install downloaded APK", v -> cloneDownloaded());
    section(root, "Clone configuration");
    packageInput = edit("New package name (e.g. com.example.pgrazor)"); root.addView(packageInput);
    section(root, "Signing key");
    addButton(root, "Import .jks / .keystore", v -> chooseKeystore());
    keyText = label("No keystore selected", 13, 0xffbdb8c8); root.addView(keyText);
    aliasInput = edit("Key alias"); root.addView(aliasInput);
    passwordInput = edit("Keystore password"); passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); root.addView(passwordInput);
    addButton(root, "Save configuration", v -> saveSettings());
    section(root, "Install");
    addButton(root, "Install original APK (optional)", v -> installDownloaded());
    statusText = label("Ready", 14, 0xffbdb8c8); statusText.setPadding(0,dp(18),0,0); root.addView(statusText);
    TextView warning = label("Cloning changes the manifest package, custom permissions and provider authorities, then rebuilds and signs a separate APK. PGRazor native/game code may still depend on its original package identity.", 12, 0xffffc27a);
    warning.setPadding(0,dp(18),0,0); root.addView(warning);
    setContentView(scroll);
  }

  private void section(LinearLayout root, String value) {
    TextView t = label(value, 18, 0xffffffff); t.setTypeface(null,1); t.setPadding(0,dp(24),0,dp(8)); root.addView(t);
  }
  private TextView label(String s, int size, int color) {
    TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t;
  }
  private EditText edit(String hint) {
    EditText e = new EditText(this); e.setSingleLine(true); e.setHint(hint);
    e.setTextColor(0xffffffff); e.setHintTextColor(0xffaaa4b5); return e;
  }
  private void addButton(LinearLayout root, String text, android.view.View.OnClickListener click) {
    Button b = new Button(this); b.setText(text); b.setOnClickListener(click); root.addView(b);
  }
  private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }

  private void checkRelease() {
    statusText.setText("Checking jpitty03/PGRazor…");
    worker.execute(() -> {
      try {
        JSONObject release = requestJson(RELEASE_API);
        String foundTag = release.optString("tag_name", "unknown");
        JSONArray assets = release.optJSONArray("assets");
        String foundUrl = null, digest = "";
        if (assets != null) for (int i=0; i<assets.length(); i++) {
          JSONObject asset = assets.getJSONObject(i);
          if ("PGRazor.apk".equalsIgnoreCase(asset.optString("name"))) {
            foundUrl = asset.optString("browser_download_url");
            String d = asset.optString("digest", "");
            if (d.startsWith("sha256:")) digest = d.substring(7);
            break;
          }
        }
        if (foundUrl == null || foundUrl.isEmpty()) throw new Exception("Latest release has no PGRazor.apk asset");
        final String u=foundUrl, t=foundTag, d=digest;
        runOnUiThread(() -> {
          apkUrl=u; tag=t; expectedDigest=d;
          releaseText.setText("Latest: " + t + "\nPGRazor.apk found\nSHA-256: " + (d.isEmpty() ? "not published" : "available"));
          statusText.setText("Release check complete");
          if (hasCloneConfig()) {
            String last = getPreferences(0).getString("lastClonedTag", "");
            if (!t.equals(last)) downloadLatest();
          }
        });
      } catch (Exception e) { runOnUiThread(() -> statusText.setText("Release check failed: " + e.getMessage())); }
    });
  }

  private JSONObject requestJson(String address) throws Exception {
    HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();
    c.setConnectTimeout(15000); c.setReadTimeout(20000);
    c.setRequestProperty("Accept","application/vnd.github+json"); c.setRequestProperty("User-Agent","PGRazor-Updater");
    try (InputStream in=c.getInputStream()) { return new JSONObject(readAll(in)); }
    finally { c.disconnect(); }
  }
  private String readAll(InputStream in) throws Exception {
    ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
    while ((n=in.read(b))!=-1) out.write(b,0,n);
    return out.toString("UTF-8");
  }

  private void downloadLatest() {
    if (apkUrl == null) { checkRelease(); statusText.setText("Release URL not ready; checking again"); return; }
    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl));
    request.setTitle("PGRazor " + tag); request.setDescription("Downloading official release APK");
    request.setMimeType("application/vnd.android.package-archive");
    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
    request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "PGRazor-" + tag + ".apk");
    if (activeDownload != -1) { statusText.setText("A download is already running"); return; }
    activeDownload=downloads.enqueue(request);
    getPreferences(0).edit().putBoolean("apkVerified", false).putString("apkTag", tag).apply();
    statusText.setText("Downloading " + tag + "…");
  }

  private void downloadComplete() {
    DownloadManager.Query query=new DownloadManager.Query().setFilterById(activeDownload);
    try (Cursor c=downloads.query(query)) {
      if (c==null || !c.moveToFirst()) throw new Exception("Download record missing");
      int state=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
      if (state!=DownloadManager.STATUS_SUCCESSFUL) throw new Exception("Download failed (status " + state + ")");
      Uri uri=downloads.getUriForDownloadedFile(activeDownload);
      getPreferences(0).edit().putString("apkUri",uri.toString()).putBoolean("apkVerified",false).apply();
      verifyApk(uri);
    } catch(Exception e) { statusText.setText(e.getMessage()); }
  }

  private void verifyApk(Uri uri) {
    worker.execute(() -> {
      try (InputStream in=getContentResolver().openInputStream(uri)) {
        if (in==null) throw new Exception("Cannot open downloaded APK");
        MessageDigest md=MessageDigest.getInstance("SHA-256"); byte[] b=new byte[16384]; int n;
        while((n=in.read(b))>0) md.update(b,0,n);
        StringBuilder hex=new StringBuilder();
        for(byte x:md.digest()) hex.append(String.format(Locale.US,"%02x",x));
        String actual=hex.toString();
        if(expectedDigest!=null && !expectedDigest.isEmpty() && !actual.equalsIgnoreCase(expectedDigest))
          throw new Exception("SHA-256 mismatch; refusing this APK");
        runOnUiThread(() -> statusText.setText("Download verified. SHA-256: " + actual));
      } catch(Exception e) { runOnUiThread(() -> statusText.setText("Verification failed: " + e.getMessage())); }
    });
  }

  private void cloneDownloaded() {
    String source = getPreferences(0).getString("apkUri", null);
    String pkg = packageInput.getText().toString().trim();
    String keyAlias = aliasInput.getText().toString().trim();
    String keyPassword = passwordInput.getText().toString();
    if (source == null) { statusText.setText("Download the official APK first"); return; }
    if (!getPreferences(0).getBoolean("apkVerified", false)) {
      statusText.setText("Wait for APK verification to finish"); return;
    }
    if (keystoreUri == null || keyAlias.isEmpty() || keyPassword.isEmpty()) {
      statusText.setText("Import your keystore and enter its alias and password"); return;
    }
    if (!pkg.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")) {
      statusText.setText("Enter a valid lowercase package name"); return;
    }
    statusText.setText("Preparing APK clone…");
    worker.execute(() -> {
      try {
        File work = new File(getFilesDir(), "clone-work");
        File output = ApkCloneEngine.cloneAndSign(getContentResolver(), Uri.parse(source),
          keystoreUri, keyAlias, keyPassword, pkg, work,
          message -> runOnUiThread(() -> statusText.setText(message)));
        PackageInfo info = getPackageManager().getPackageArchiveInfo(output.getAbsolutePath(), 0);
        if (info == null || !pkg.equals(info.packageName))
          throw new SecurityException("Cloned APK package name verification failed");
        runOnUiThread(() -> {
          try {
            Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", output);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setData(contentUri);
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getPreferences(0).edit().putString("lastClonedTag",
              getPreferences(0).getString("apkTag", tag == null ? "" : tag)).apply();
            startActivity(install);
          } catch (Exception e) {
            statusText.setText("Could not open installer: " + e.getMessage());
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> statusText.setText("Clone failed: " + e.getMessage()));
      }
    });
  }

  private void installDownloaded() {
    String saved=getPreferences(0).getString("apkUri",null);
    if(saved==null) { statusText.setText("Download the release first"); return; }
    Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
    intent.setData(Uri.parse(saved)); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try { startActivity(intent); } catch(Exception e) { statusText.setText("Installer error: " + e.getMessage()); }
  }

  private void chooseKeystore() {
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("*/*"); i.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 44);
  }
  @Override protected void onActivityResult(int request,int result,Intent data) {
    super.onActivityResult(request,result,data);
    if(request==44 && result==RESULT_OK && data!=null && data.getData()!=null) {
      keystoreUri=data.getData();
      getContentResolver().takePersistableUriPermission(keystoreUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
      keyText.setText("Selected: " + displayName(keystoreUri));
      getPreferences(0).edit().putString("keyUri",keystoreUri.toString()).apply();
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
    packageInput.setText(p.getString("packageName",""));
    aliasInput.setText(p.getString("alias",""));
    String u=p.getString("keyUri",null);
    if(u!=null) { keystoreUri=Uri.parse(u); keyText.setText("Selected: " + displayName(keystoreUri)); }
    try { passwordInput.setText(decrypt(p.getString("secret",null))); } catch(Exception ignored) {}
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
    if(!pkg.isEmpty() && !pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
      statusText.setText("Invalid Android package name"); return;
    }
    try {
      String encrypted=encrypt(passwordInput.getText().toString());
      getPreferences(0).edit().putString("packageName",pkg)
        .putString("alias",aliasInput.getText().toString().trim()).putString("secret",encrypted).apply();
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
    worker.shutdownNow(); super.onDestroy();
  }
}
