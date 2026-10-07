package android.util;

/** Log de Android para el scraper de Ludolog en el PC: a la salida de error, con su etiqueta. */
public class Log {
    public static int i(String tag, String msg) { System.err.println(tag + " I " + msg); return 0; }
    public static int w(String tag, String msg) { System.err.println(tag + " W " + msg); return 0; }
    public static int d(String tag, String msg) { return 0; }
    public static int e(String tag, String msg) { System.err.println(tag + " E " + msg); return 0; }
}
