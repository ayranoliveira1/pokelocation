package com.pokelocation.app.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import com.pokelocation.app.Io;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class PrivacyModeManager {
    private static final String TAG = "PrivacyMode";
    private static final String APP_PACKAGE_NAME = "com.pokelocation.app";
    private static final String CLONE_PACKAGE_PREFIX = "com.pokelocation.app.privacy_";

    private PrivacyModeManager() {}

    public static void generateCloneApp(Context context, Io.Task task) {
        Io.run(() -> {
            generateCloneInternal(context);
            return null;
        }, new Io.Callback<Void>() {
            @Override
            public void onResult(Void value) {
                if (task != null && !task.isCancelled()) {
                    task.cancel();
                }
            }

            @Override
            public void onError(Throwable t) {
                if (task != null && !task.isCancelled()) {
                    task.cancel();
                }
            }
        });
    }

    private static void generateCloneInternal(Context context) throws Exception {
        String originalApkPath = getApkPath(context);
        if (originalApkPath == null) {
            throw new RuntimeException("Could not find APK path");
        }

        String clonePackageName = generateClonePackageName();
        File tempDir = new File(context.getCacheDir(), "privacy_mode_" + System.currentTimeMillis());
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new RuntimeException("Failed to create temp directory");
        }

        File cloneApk = null;
        try {
            cloneApk = new File(tempDir, "privacy_mode.apk");
            
            // Copia o APK original
            Files.copy(Paths.get(originalApkPath), cloneApk.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Desempacota o APK
            File extractedDir = new File(tempDir, "extracted");
            if (!extractedDir.mkdirs()) {
                throw new RuntimeException("Failed to create extracted directory");
            }
            extractApk(cloneApk, extractedDir);

            // Descompila e atualiza o manifest
            File manifestFile = new File(extractedDir, "AndroidManifest.xml");
            if (!manifestFile.exists()) {
                throw new RuntimeException("AndroidManifest.xml not found after extraction");
            }

            String newManifest = updateManifestPackage(manifestFile, clonePackageName);
            if (newManifest != null) {
                writeStringToFile(manifestFile, newManifest);
                
                // Reempacota o APK
                repackApk(cloneApk, extractedDir);
                
                // Reassina o APK (necessário para instalação)
                signApk(cloneApk, tempDir);
            }

            // Instala o APK clonado
            installClone(context, cloneApk, clonePackageName);

        } finally {
            if (tempDir != null) {
                deleteRecursive(tempDir);
            }
        }
    }

    private static void extractApk(File apk, File extractedDir) throws Exception {
        try (ZipFile zipFile = new ZipFile(apk)) {
            zipFile.stream().forEach(entry -> {
                String entryName = entry.getName();
                File outputFile = new File(extractedDir, entryName);
                
                try {
                    if (entry.isDirectory()) {
                        outputFile.mkdirs();
                        return;
                    }
                    
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        parentDir.mkdirs();
                    }
                    
                    try (InputStream in = zipFile.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to extract: " + entryName, e);
                }
            });
        }
    }

    private static String getApkPath(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.applicationInfo.sourceDir;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static String generateClonePackageName() {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuilder suffix = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            suffix.append(chars.charAt(random.nextInt(chars.length())));
        }
        return CLONE_PACKAGE_PREFIX + suffix.toString();
    }

    private static String readFileToString(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }

    private static void writeStringToFile(File file, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String updateManifestPackage(File manifestFile, String newPackageName) throws Exception {
        String content = readFileToString(manifestFile);
        
        int manifestStart = content.indexOf("<manifest ");
        int manifestEnd = content.indexOf(">", manifestStart);
        if (manifestStart == -1 || manifestEnd == -1) {
            return null;
        }

        String manifestTag = content.substring(manifestStart, manifestEnd + 1);
        String newManifestTag = manifestTag.replaceFirst("package=\"[^\"]+\"", "package=\"" + newPackageName + "\"");
        
        return content.replace(manifestTag, newManifestTag);
    }

    private static void repackApk(File apk, File extractedDir) throws Exception {
        File tempApk = new File(extractedDir.getParentFile(), "temp.apk");
        
        try (ZipFile sourceZip = new ZipFile(apk);
             ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tempApk))) {
            
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(sourceZip.entries())) {
                String entryName = entry.getName();
                
                try {
                    // Skip meta files
                    if (entryName.startsWith("META-INF/")) {
                        continue;
                    }
                    
                    ZipEntry newEntry = new ZipEntry(entryName);
                    zipOut.putNextEntry(newEntry);
                    
                    try (InputStream in = sourceZip.getInputStream(entry)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = in.read(buffer)) != -1) {
                            zipOut.write(buffer, 0, len);
                        }
                    }
                    zipOut.closeEntry();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to repack: " + entryName, e);
                }
            }
        }
        
        // Substitui o APK original pelo repackado
        if (tempApk.exists()) {
            Files.move(tempApk.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void signApk(File apk, File tempDir) throws Exception {
        // Primeiro tenta usar apksigner
        signWithApksigner(apk, tempDir);
        
        // Se não conseguiu, tenta jarsigner como fallback
        if (!verifySignedApk(apk)) {
            Log.w(TAG, "Verificação de assinatura falhou, tentando jarsigner...");
            signWithJarsigner(apk, tempDir);
        }
    }

    private static boolean verifySignedApk(File apk) {
        try {
            String apksignerPath = findApksigner();
            ProcessBuilder pb = new ProcessBuilder(
                apksignerPath, "verify",
                "--verbose",
                apk.getAbsolutePath()
            );
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify APK signature", e);
            return false;
        }
    }

    private static void signWithApksigner(File apk, File tempDir) throws Exception {
        String apksignerPath = findApksigner();
        
        ProcessBuilder pb = new ProcessBuilder(
            apksignerPath, "sign",
            "--ks", getDebugKeystorePath(),
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--ks-key-alias", "androiddebugkey",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "false",
            apk.getAbsolutePath()
        );
        pb.directory(tempDir);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new RuntimeException("APK signing with apksigner failed, exit code: " + exitCode);
        }
    }
    
    private static String findApksigner() {
        String[] paths = {
            "/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/apksigner",
            "/opt/homebrew/share/android-commandlinetools/build-tools/35.0.0/apksigner",
            System.getProperty("user.home") + "/Library/Android/sdk/build-tools/*/apksigner"
        };
        
        for (String path : paths) {
            if (path.contains("*")) {
                String base = path.substring(0, path.indexOf("*") - 1);
                File dir = new File(base);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles((d, name) -> name.startsWith("3"));
                    if (files != null && files.length > 0) {
                        File latest = files[files.length - 1];
                        File apksigner = new File(latest, "apksigner");
                        if (apksigner.exists()) {
                            return apksigner.getAbsolutePath();
                        }
                    }
                }
            } else {
                File file = new File(path);
                if (file.exists()) {
                    return file.getAbsolutePath();
                }
            }
        }
        
        return "apksigner"; // Assume que está no PATH
    }

    private static void signWithJarsigner(File apk, File tempDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "jarsigner",
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
            "-keystore", getDebugKeystorePath(),
            "-storepass", "android",
            "-keypass", "android",
            apk.getAbsolutePath(),
            "androiddebugkey"
        );
        pb.directory(tempDir);
        pb.redirectErrorStream(true);
        
        Process process = pb.start();
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new RuntimeException("APK signing failed");
        }
    }

    private static String getDebugKeystorePath() {
        String userHome = System.getProperty("user.home");
        if (userHome == null) {
            userHome = System.getenv("HOME");
        }
        return userHome + "/.android/debug.keystore";
    }

    private static void installClone(Context context, File apk, String packageName) throws Exception {
        PackageManager pm = context.getPackageManager();
        
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            // Se já existe, desinstala usando adb (requer root)
            Log.w(TAG, "Package " + packageName + " already exists. Please uninstall manually or use root.");
        } catch (PackageManager.NameNotFoundException e) {
            // Package doesn't exist, ok to install
        }

        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", apk);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(uri, "application/vnd.android.package-archive");
        installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (installIntent.resolveActivity(pm) != null) {
            context.startActivity(installIntent);
        } else {
            throw new RuntimeException("No activity to handle package installation");
        }
    }

    private static boolean deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursive(f);
                }
            }
        }
        return file.delete();
    }

    public static boolean isCloneApp(Context context) {
        String packageName = context.getPackageName();
        return packageName != null && packageName.startsWith(CLONE_PACKAGE_PREFIX);
    }

    public static void setPrivacyModeEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("privacy_mode_enabled", enabled)
            .apply();
    }

    public static boolean isPrivacyModeEnabled(Context context) {
        return context.getSharedPreferences("privacy_prefs", Context.MODE_PRIVATE)
            .getBoolean("privacy_mode_enabled", false);
    }

    public static boolean shouldAutoResetAgps(Context context) {
        return isCloneApp(context) && isPrivacyModeEnabled(context);
    }
}
