package android.media;

import org.bytedeco.javacv.FFmpegFrameGrabber;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaExtractor para VideoRemnants de Ludolog en el PC: que pistas tiene un fichero, mirado con
 * FFmpeg. Solo dice si hay imagen y si hay sonido, que es lo que se le pregunta.
 */
public class MediaExtractor {
    private final List<MediaFormat> tracks = new ArrayList<>();

    public void setDataSource(String path) throws java.io.IOException {
        tracks.clear();
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(path);
        try {
            g.start();
            if (g.getImageWidth() > 0) {
                MediaFormat f = new MediaFormat();
                f.setString(MediaFormat.KEY_MIME, "video/avc");
                f.setInteger(MediaFormat.KEY_WIDTH, g.getImageWidth());
                f.setInteger(MediaFormat.KEY_HEIGHT, g.getImageHeight());
                tracks.add(f);
            }
            if (g.getAudioChannels() > 0) {
                MediaFormat f = new MediaFormat();
                f.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
                tracks.add(f);
            }
        } catch (Exception e) {
            throw new java.io.IOException(e);
        } finally {
            try { g.release(); } catch (Exception ignored) { }
        }
    }

    public int getTrackCount() { return tracks.size(); }
    public MediaFormat getTrackFormat(int i) { return tracks.get(i); }
    public void release() { tracks.clear(); }
}
