package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns a single bridge response, including competing timeout/teardown callbacks. */
final class CallResult {
    private final PluginCall call;
    private final AtomicBoolean settled = new AtomicBoolean();

    CallResult(PluginCall call) { this.call = call; }

    void resolve() {
        if (settled.compareAndSet(false, true)) call.resolve();
    }

    void resolve(JSObject data) {
        if (settled.compareAndSet(false, true)) call.resolve(data);
    }

    void reject(String code, String message) {
        if (settled.compareAndSet(false, true)) call.reject(message, code);
    }
}
