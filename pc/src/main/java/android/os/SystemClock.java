package android.os;

/** El reloj de Android para el scraper de Ludolog en el PC: milisegundos que solo avanzan. */
public class SystemClock {
    public static long elapsedRealtime() { return System.nanoTime() / 1_000_000L; }
    public static long uptimeMillis() { return elapsedRealtime(); }
}
