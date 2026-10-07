package android.media;

import java.util.HashMap;
import java.util.Map;

/**
 * Lo poco de MediaFormat que usa el scraper de Ludolog en el PC (VideoRemnants y la imitacion de
 * VideoScale): un mapa de claves.
 */
public class MediaFormat {
    public static final String KEY_MIME = "mime";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_BIT_RATE = "bitrate";
    public static final String KEY_FRAME_RATE = "frame-rate";

    private final Map<String, Object> values = new HashMap<>();

    public String getString(String key) { Object v = values.get(key); return v == null ? null : v.toString(); }
    public int getInteger(String key) { Object v = values.get(key); return v instanceof Number ? ((Number) v).intValue() : 0; }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public void setString(String key, String value) { values.put(key, value); }
    public void setInteger(String key, int value) { values.put(key, value); }
}
