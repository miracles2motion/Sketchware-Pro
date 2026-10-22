package pro.sketchware.ai;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decodes, normalizes, caches, and encodes chat image attachments and
 * offscreen screen-preview renders for Agent Mode.
 *
 * <p>Disk copies in {@link AiPaths#attachmentsDir()} cap the longest edge
 * at {@code 1280px} and preserve alpha transparency as PNG (opaque photos
 * and screenshots are stored as high-quality JPEG), so the same attachment
 * serves both multimodal vision payloads and direct import into Project
 * Image Manager or App Launcher Icons.
 */
public final class AgentMedia {

    /** Maximum images attached to a single user chat message. */
    public static final int MAX_ATTACHMENTS_PER_MESSAGE = 4;

    private static final int MAX_STORED_EDGE_PX = 1280;
    private static final int MAX_VISION_EDGE_PX = 1024;
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static final LruCache<String, String> VISION_DATA_URL_CACHE =
            new LruCache<>(10);

    private static final LruCache<String, Bitmap> THUMB_CACHE =
            new LruCache<String, Bitmap>(24) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return 1;
                }
            };

    private AgentMedia() {
    }

    /**
     * Copies and normalizes one gallery/screenshot {@link Uri} into
     * {@link AiPaths#attachmentsDir()}. Call off the main thread.
     */
    public static ChatMessage.Attachment saveAttachment(Context context,
                                                        Uri uri)
            throws IOException {
        if (context == null || uri == null) {
            throw new IOException("No image selected.");
        }
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not open image stream.");
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Selected file is not a readable image.");
        }

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream exifIn = resolver.openInputStream(uri)) {
            if (exifIn != null) {
                ExifInterface exif = new ExifInterface(exifIn);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
            }
        } catch (Exception ignored) {
            // Non-JPEG formats (PNG/WebP) simply use normal orientation.
        }

        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = computeSampleSize(
                bounds.outWidth, bounds.outHeight, MAX_STORED_EDGE_PX * 2);
        decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Could not read image data.");
            }
            decoded = BitmapFactory.decodeStream(in, null, decodeOpts);
        }
        if (decoded == null) {
            throw new IOException("Could not decode image.");
        }

        Bitmap oriented = applyExifOrientation(decoded, orientation);
        if (oriented != decoded) {
            decoded.recycle();
        }
        Bitmap normalized = scaleDown(oriented, MAX_STORED_EDGE_PX);
        if (normalized != oriented) {
            oriented.recycle();
        }

        boolean keepPng = normalized.hasAlpha();
        String ext = keepPng ? ".png" : ".jpg";
        String mime = keepPng ? "image/png" : "image/jpeg";
        String id = "att_" + System.currentTimeMillis() + "_"
                + SEQ.getAndIncrement();
        File outFile = new File(AiPaths.attachmentsDir(), id + ext);
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            boolean ok = normalized.compress(
                    keepPng ? Bitmap.CompressFormat.PNG
                            : Bitmap.CompressFormat.JPEG,
                    keepPng ? 100 : 88, out);
            out.flush();
            if (!ok) {
                throw new IOException("Failed to write attachment image.");
            }
        }
        int finalW = normalized.getWidth();
        int finalH = normalized.getHeight();
        normalized.recycle();
        return new ChatMessage.Attachment(
                id, outFile.getAbsolutePath(), mime,
                finalW, finalH, outFile.length());
    }

    /**
     * Encodes an image file as an OpenAI-compatible {@code data:image/...;base64,...}
     * URL capped at {@code 1024px}, cached in memory across tool turns.
     */
    public static String toVisionDataUrl(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return "";
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return "";
        }
        String cacheKey = file.getAbsolutePath() + ":" + file.lastModified()
                + ":" + file.length();
        String cached = VISION_DATA_URL_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return "";
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeSampleSize(
                    bounds.outWidth, bounds.outHeight, MAX_VISION_EDGE_PX * 2);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded = BitmapFactory.decodeFile(
                    file.getAbsolutePath(), opts);
            if (decoded == null) {
                return "";
            }
            Bitmap scaled = scaleDown(decoded, MAX_VISION_EDGE_PX);
            if (scaled != decoded) {
                decoded.recycle();
            }
            boolean usePng = scaled.hasAlpha()
                    && Math.max(scaled.getWidth(), scaled.getHeight()) <= 640;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            scaled.compress(
                    usePng ? Bitmap.CompressFormat.PNG
                            : Bitmap.CompressFormat.JPEG,
                    usePng ? 100 : 82, buffer);
            scaled.recycle();
            byte[] bytes = buffer.toByteArray();
            if (bytes.length == 0) {
                return "";
            }
            String mime = usePng ? "image/png" : "image/jpeg";
            String dataUrl = "data:" + mime + ";base64,"
                    + Base64.encodeToString(bytes, Base64.NO_WRAP);
            VISION_DATA_URL_CACHE.put(cacheKey, dataUrl);
            return dataUrl;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Decodes a small thumbnail for UI display (composer strip / chat bubble).
     */
    public static Bitmap decodeThumbnail(String filePath, int maxEdgePx) {
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        int target = Math.max(64, maxEdgePx);
        String key = filePath + "@" + target;
        Bitmap cached = THUMB_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = computeSampleSize(
                    bounds.outWidth, bounds.outHeight, target * 2);
            Bitmap decoded = BitmapFactory.decodeFile(
                    file.getAbsolutePath(), opts);
            if (decoded == null) {
                return null;
            }
            Bitmap scaled = scaleDown(decoded, target);
            if (scaled != decoded) {
                decoded.recycle();
            }
            THUMB_CACHE.put(key, scaled);
            return scaled;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Resolves an {@code attachment_id} (or empty string = newest attached
     * image in this project's chat / attachments folder) to a verified file.
     */
    public static File findAttachmentFile(String scId, String attachmentId) {
        String cleanId = attachmentId == null ? "" : attachmentId.trim();
        // Strip file extension if model passed e.g. "att_123.png"
        if (cleanId.toLowerCase(Locale.ROOT).endsWith(".png")
                || cleanId.toLowerCase(Locale.ROOT).endsWith(".jpg")) {
            cleanId = cleanId.substring(0, cleanId.length() - 4);
        } else if (cleanId.toLowerCase(Locale.ROOT).endsWith(".jpeg")) {
            cleanId = cleanId.substring(0, cleanId.length() - 5);
        }
        if (!cleanId.isEmpty() && !cleanId.matches("[A-Za-z0-9_\\-]+")) {
            return null;
        }
        // 1. Check saved project chat history first (newest to oldest)
        if (scId != null && !scId.isEmpty()) {
            List<ChatMessage> chat = AgentChatLog.load(scId);
            for (int i = chat.size() - 1; i >= 0; i--) {
                ChatMessage msg = chat.get(i);
                for (int j = msg.attachments.size() - 1; j >= 0; j--) {
                    ChatMessage.Attachment att = msg.attachments.get(j);
                    if (cleanId.isEmpty()
                            || cleanId.equalsIgnoreCase(att.id)) {
                        File f = new File(att.path);
                        if (f.exists() && f.isFile() && f.length() > 0) {
                            return f;
                        }
                    }
                }
            }
        }
        // 2. Check AiPaths.attachmentsDir() directly (covers un-bound / newly created project turns)
        File dir = AiPaths.attachmentsDir();
        if (!cleanId.isEmpty()) {
            for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                File candidate = new File(dir, cleanId + ext);
                if (candidate.exists() && candidate.isFile()
                        && candidate.length() > 0) {
                    return candidate;
                }
            }
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        File newest = null;
        long newestTime = -1L;
        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (!f.isFile() || f.length() == 0 || name.startsWith(".")) {
                continue;
            }
            if ((name.endsWith(".png") || name.endsWith(".jpg")
                    || name.endsWith(".jpeg"))
                    && f.lastModified() > newestTime) {
                newest = f;
                newestTime = f.lastModified();
            }
        }
        return newest;
    }

    public static int computeSampleSize(int width, int height,
                                        int targetMaxEdge) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / (sample * 2) >= targetMaxEdge) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    public static Bitmap scaleDown(Bitmap src, int maxEdge) {
        if (src == null) {
            return null;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxEdge || maxEdge <= 0) {
            return src;
        }
        float ratio = (float) maxEdge / (float) longest;
        int nw = Math.max(1, Math.round(w * ratio));
        int nh = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    private static Bitmap applyExifOrientation(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f);
            case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f);
            case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f);
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.preScale(-1f, 1f);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.preScale(1f, -1f);
            default -> {
                return bitmap;
            }
        }
        try {
            return Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        } catch (OutOfMemoryError ignored) {
            return bitmap;
        }
    }
}
