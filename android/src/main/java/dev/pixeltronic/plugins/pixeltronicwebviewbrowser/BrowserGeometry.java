package dev.pixeltronic.plugins.pixeltronicwebviewbrowser;

final class BrowserGeometry {
    static final int MAX_SNAPSHOT_EDGE = 2048;

    static final class Frame {
        final int width, height, clipWidth, clipHeight;
        final float x, y;
        Frame(int width, int height, float x, float y, int clipWidth, int clipHeight) {
            this.width = width; this.height = height; this.x = x; this.y = y;
            this.clipWidth = clipWidth; this.clipHeight = clipHeight;
        }
    }

    static Frame project(InAppBrowserOptions.Bounds bounds, int hostWidth, int hostHeight, float density) {
        if (hostWidth <= 0 || hostHeight <= 0) throw new IllegalArgumentException("The host WebView must be laid out.");
        double scale = bounds.viewportWidth == null ? density : hostWidth / bounds.viewportWidth;
        double width = bounds.width * scale, height = bounds.height * scale;
        double x = (bounds.x - bounds.offsetLeft) * scale, y = (bounds.y - bounds.offsetTop) * scale;
        if (!Double.isFinite(scale) || scale <= 0 || !Double.isFinite(width) || !Double.isFinite(height) ||
            width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || Math.abs(x) > Integer.MAX_VALUE || Math.abs(y) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Bounds exceed the native coordinate range.");
        }
        int clipHeight = bounds.viewportHeight == null ? hostHeight : (int) Math.min(hostHeight, Math.round(bounds.viewportHeight * scale));
        return new Frame(Math.max(1, (int) Math.round(width)), Math.max(1, (int) Math.round(height)), (float) x, (float) y,
            hostWidth, Math.max(1, clipHeight));
    }

    static int[] snapshotSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("The WebView has no drawable bounds.");
        double scale = Math.min(1.0, MAX_SNAPSHOT_EDGE / (double) Math.max(width, height));
        return new int[] { Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)) };
    }
}
