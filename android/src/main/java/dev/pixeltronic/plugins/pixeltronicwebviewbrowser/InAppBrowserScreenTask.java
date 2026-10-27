package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/** Only encoding runs on the worker. WebView access and drawing stay on the UI thread. */
final class InAppBrowserScreenTask implements Runnable {
    private final Bitmap bitmap;
    private final Consumer<byte[]> success;
    private final Consumer<Exception> failure;

    InAppBrowserScreenTask(Bitmap bitmap, Consumer<byte[]> success, Consumer<Exception> failure) {
        this.bitmap = bitmap;
        this.success = success;
        this.failure = failure;
    }

    @Override
    public void run() {
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                throw new IllegalStateException("JPEG encoding failed.");
            }
            bytes = output.toByteArray();
        } catch (Exception ex) {
            failure.accept(ex);
            return;
        } finally {
            bitmap.recycle();
        }
        success.accept(bytes);
    }
}
