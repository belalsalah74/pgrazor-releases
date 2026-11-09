package dev.pgrazor.updater;

import android.content.ContentResolver;
import android.net.Uri;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import com.reandroid.apkeditor.Main;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkCloneEngine {
    public interface Progress { void onProgress(String message); }

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private ApkCloneEngine() {}

    public static File cloneAndSign(ContentResolver resolver, Uri sourceApk, Uri keyUri,
                                    String alias, String storePassword, String keyPassword, String newPackage,
                                    File workRoot, Progress progress) throws Exception {
        if (newPackage == null || !newPackage.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("Package name must be lowercase, e.g. com.example.pgrazor");
        if (alias == null || alias.trim().isEmpty()) throw new IllegalArgumentException("Signing alias is required");
        if (keyUri == null) throw new IllegalArgumentException("Import a JKS/keystore first");

        deleteTree(workRoot);
        if (!workRoot.mkdirs() && !workRoot.isDirectory()) throw new IOException("Cannot create work directory");
        File original = new File(workRoot, "original.apk");
        File decoded = new File(workRoot, "decoded");
        File rebuilt = new File(workRoot, "rebuilt-unsigned.apk");
        File signed = new File(workRoot, "PGRazor-clone.apk");

        progress.onProgress("Copying downloaded APK…");
        try (InputStream in = resolver.openInputStream(sourceApk); OutputStream out = new FileOutputStream(original)) {
            if (in == null) throw new IOException("Cannot read downloaded APK");
            copy(in, out);
        }
        validateApkArchive(original);
        if (original.length() < 1024) throw new IOException("Source APK is unexpectedly small");

        progress.onProgress("Decoding manifest and resources…");
        int decodeResult = Main.execute(new String[]{"d", "-i", original.getAbsolutePath(),
                "-o", decoded.getAbsolutePath(), "-t", "xml", "-dex", "-f"});
        if (decodeResult != 0) throw new IOException("APKEditor decode failed (code " + decodeResult + ")");

        File manifest = new File(decoded, "AndroidManifest.xml");
        if (!manifest.isFile()) throw new IOException("Decoded AndroidManifest.xml was not produced");
        progress.onProgress("Updating package name, permissions and providers…");
        Document manifestDoc = parseXml(manifest);
        Element root = manifestDoc.getDocumentElement();
        String oldPackage = root.getAttribute("package");
        if (oldPackage.isEmpty()) throw new IOException("Original manifest has no package name");
        if (!oldPackage.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
            throw new IOException("Original APK contains an invalid package name: " + oldPackage);
        if (oldPackage.equals(newPackage)) throw new IOException("New package name is identical to the original");
        normalizeComponentNames(root, oldPackage);
        root.setAttribute("package", newPackage);

        Set<String> providerStringRefs = new HashSet<>();
        updatePermissions(manifestDoc, oldPackage, newPackage);
        updateProviders(manifestDoc, oldPackage, newPackage, providerStringRefs);
        writeXml(manifestDoc, manifest);
        if (!newPackage.equals(root.getAttribute("package")))
            throw new SecurityException("Manifest package rewrite did not persist");

        if (!providerStringRefs.isEmpty()) {
            File strings = new File(decoded, "res/values/strings.xml");
            if (strings.isFile()) {
                Document stringsDoc = parseXml(strings);
                NodeList nodes = stringsDoc.getElementsByTagName("string");
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element item = (Element) nodes.item(i);
                    if (!providerStringRefs.contains(item.getAttribute("name"))) continue;
                    String value = item.getTextContent();
                    item.setTextContent(rewriteAuthorityList(value, oldPackage, newPackage));
                }
                writeXml(stringsDoc, strings);
            }
        }

        progress.onProgress("Rebuilding APK…");
        int buildResult = Main.execute(new String[]{"b", "-i", decoded.getAbsolutePath(),
                "-o", rebuilt.getAbsolutePath(), "-f"});
        if (buildResult != 0 || !rebuilt.isFile()) throw new IOException("APKEditor rebuild failed (code " + buildResult + ")");
        if (rebuilt.length() < 1024) throw new IOException("APKEditor produced an empty or incomplete APK");
        validateApkArchive(rebuilt);

        progress.onProgress("Signing with your keystore…");
        signApk(rebuilt, signed, resolver, keyUri, alias.trim(), storePassword, keyPassword);
        progress.onProgress("Verifying signature…");
        ApkVerifier.Result verification = new ApkVerifier.Builder(signed).build().verify();
        if (!verification.isVerified()) {
            throw new SecurityException("Output signature verification failed: " + verification.getErrors());
        }
        if (!signed.isFile() || signed.length() < 1024)
            throw new IOException("Signing completed without a valid output APK");
        validateApkArchive(signed);
        progress.onProgress("Clone created and signature verified");
        return signed;
    }

    private static void normalizeComponentNames(Element manifest, String originalPackage) {
        String[] componentTags = {"application", "activity", "activity-alias", "service", "receiver",
                "provider", "instrumentation"};
        String[] classAttributes = {"name", "targetActivity", "backupAgent", "appComponentFactory",
                "manageSpaceActivity", "parentActivityName", "zygotePreloadName"};
        for (String tag : componentTags) {
            NodeList nodes = manifest.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                for (String attribute : classAttributes) {
                    String value = element.getAttributeNS(ANDROID_NS, attribute);
                    if (value.isEmpty() || value.startsWith("@") || value.startsWith("?")) continue;
                    if (value.startsWith(".")) value = originalPackage + value;
                    else if (!value.contains(".")) value = originalPackage + "." + value;
                    element.setAttributeNS(ANDROID_NS, "android:" + attribute, value);
                }
            }
        }
    }

    private static void updatePermissions(Document doc, String oldPkg, String newPkg) {
        NodeList declared = doc.getElementsByTagName("permission");
        Map<String,String> replacements = new HashMap<>();
        for (int i=0; i<declared.getLength(); i++) {
            Element permission = (Element) declared.item(i);
            String oldName = permission.getAttributeNS(ANDROID_NS, "name");
            if (oldName.isEmpty()) continue;
            String fullyQualified = oldName.startsWith(".") ? oldPkg + oldName : oldName;
            if (!fullyQualified.equals(oldPkg) && !fullyQualified.startsWith(oldPkg + ".")) continue;
            String newName = newPkg + fullyQualified.substring(oldPkg.length());
            permission.setAttributeNS(ANDROID_NS, "android:name", newName);
            replacements.put(oldName, newName);
            replacements.put(fullyQualified, newName);
        }
        String[] usesTags = {"uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"};
        for (String tag : usesTags) {
            NodeList uses = doc.getElementsByTagName(tag);
            for (int i=0; i<uses.getLength(); i++) {
                Element item = (Element) uses.item(i);
                String name = item.getAttributeNS(ANDROID_NS, "name");
                if (replacements.containsKey(name))
                    item.setAttributeNS(ANDROID_NS, "android:name", replacements.get(name));
            }
        }
    }

    private static void updateProviders(Document doc, String oldPkg, String newPkg, Set<String> stringRefs) {
        NodeList providers = doc.getElementsByTagName("provider");
        for (int i=0; i<providers.getLength(); i++) {
            Element provider = (Element) providers.item(i);
            String raw = provider.getAttributeNS(ANDROID_NS, "authorities");
            if (raw.isEmpty()) continue;
            String[] values = raw.split(";");
            for (int j=0; j<values.length; j++) {
                String value = values[j].trim();
                if (value.startsWith("@string/")) stringRefs.add(value.substring("@string/".length()));
                values[j] = rewriteAuthority(value, oldPkg, newPkg);
            }
            provider.setAttributeNS(ANDROID_NS, "android:authorities", String.join(";", values));
        }
    }

    private static String rewriteAuthorityList(String value, String oldPkg, String newPkg) {
        String[] authorities = value.split(";");
        for (int i = 0; i < authorities.length; i++)
            authorities[i] = rewriteAuthority(authorities[i].trim(), oldPkg, newPkg);
        return String.join(";", authorities);
    }

    private static String rewriteAuthority(String value, String oldPkg, String newPkg) {
        if (value == null || value.isEmpty() || value.startsWith("@")) return value;
        if (value.equals(oldPkg)) return newPkg;
        if (value.startsWith(oldPkg + ".")) return newPkg + value.substring(oldPkg.length());
        // Authorities owned by other packages must remain untouched.
        return value;
    }

    private static void validateApkArchive(File apk) throws IOException {
        boolean manifest = false, dex = false;
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if ("AndroidManifest.xml".equals(name)) manifest = true;
                if (name.matches("classes([0-9]+)?\\.dex")) dex = true;
            }
        } catch (IOException e) {
            throw new IOException("APK archive is corrupt or unreadable: " + apk.getName(), e);
        }
        if (!manifest) throw new IOException("APK is missing AndroidManifest.xml: " + apk.getName());
        if (!dex) throw new IOException("APK contains no DEX bytecode: " + apk.getName());
    }

    private static Document parseXml(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static void writeXml(Document doc, File file) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.transform(new DOMSource(doc), new StreamResult(file));
    }

    private static void signApk(File input, File output, ContentResolver resolver,
                                Uri keyUri, String alias, String storePassword, String keyPassword) throws Exception {
        KeyStore keyStore = loadKeyStore(resolver, keyUri, storePassword);
        Key key = keyStore.getKey(alias, keyPassword.toCharArray());
        if (!(key instanceof PrivateKey)) throw new GeneralSecurityException("Alias does not contain a private key");
        Certificate[] chain = keyStore.getCertificateChain(alias);
        if (chain == null || chain.length == 0) throw new GeneralSecurityException("Keystore alias has no certificate chain");
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : chain) certificates.add((X509Certificate) certificate);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder(alias, (PrivateKey) key, certificates).build();
        new ApkSigner.Builder(Collections.singletonList(signer))
                .setInputApk(input).setOutputApk(output)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .build().sign();
    }

    private static KeyStore loadKeyStore(ContentResolver resolver, Uri uri, String password) throws Exception {
        Exception first;
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            try (InputStream in = resolver.openInputStream(uri)) { if (in == null) throw new IOException("Cannot read keystore"); ks.load(in, password.toCharArray()); }
            return ks;
        } catch (Exception e) { first = e; }
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = resolver.openInputStream(uri)) { if (in == null) throw new IOException("Cannot read keystore"); ks.load(in, password.toCharArray()); }
            return ks;
        } catch (Exception e) { e.addSuppressed(first); throw e; }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768]; int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        out.flush();
    }
    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) { File[] children = file.listFiles(); if (children != null) for (File child : children) deleteTree(child); }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
