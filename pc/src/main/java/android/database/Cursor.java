package android.database;

/**
 * Lo que LogStats.kt de Ludolog usa del Cursor de Android, para compilarlo tal cual en el PC.
 * En Java y no en Kotlin a proposito: asi getString devuelve un tipo de plataforma, como en
 * Android, y el codigo de Ludolog compila sin cambiar ni una interrogacion.
 * La implementacion es JdbcCursor (ludolog-link/pc). Ver ludolog-front-end/docs/ludolog-link.md.
 */
public interface Cursor extends java.io.Closeable {
    boolean moveToFirst();
    boolean moveToNext();
    boolean isNull(int column);
    String getString(int column);
    long getLong(int column);
    int getInt(int column);
    double getDouble(int column);
    float getFloat(int column);
    int getCount();
    @Override void close();
}
