package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import android.graphics.Bitmap;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class InAppBrowserScreenTaskTest {
    @Test public void workerEncodesAnAlreadyCapturedBitmapAndReleasesIt() throws Exception {
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.compress(any(), eq(100), any())).thenAnswer(invocation -> {
            ((OutputStream) invocation.getArgument(2)).write(new byte[] {1, 2, 3}); return true;
        });
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        Thread worker = new Thread(new InAppBrowserScreenTask(bitmap, bytes::set, error::set));
        worker.start(); worker.join();
        assertArrayEquals(new byte[] {1, 2, 3}, bytes.get()); assertNull(error.get());
        verify(bitmap).recycle();
    }

    @Test public void failedEncodingReportsFailureAndStillReleasesBitmap() {
        Bitmap bitmap = mock(Bitmap.class);
        AtomicReference<Exception> error = new AtomicReference<>();
        new InAppBrowserScreenTask(bitmap, bytes -> fail("Unexpected success"), error::set).run();
        assertNotNull(error.get()); verify(bitmap).recycle();
    }
}
