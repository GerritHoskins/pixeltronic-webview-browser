package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.Assert.*;
import com.getcapacitor.PluginCall;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;

public class CallResultTest {
    @Test public void racingCompletionAndCancellationProduceOneBridgeResponse() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            PluginCall call = mock(PluginCall.class);
            CallResult result = new CallResult(call);
            CountDownLatch start = new CountDownLatch(1);
            Thread success = new Thread(() -> { await(start); result.resolve(); });
            Thread cancellation = new Thread(() -> { await(start); result.reject("CANCELLED", "closed"); });
            success.start(); cancellation.start(); start.countDown();
            success.join(); cancellation.join();
            assertEquals(1, mockingDetails(call).getInvocations().size());
        }
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
