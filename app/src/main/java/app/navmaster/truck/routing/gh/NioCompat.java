package app.navmaster.truck.routing.gh;

import java.nio.Buffer;
import java.nio.ByteBuffer;

/**
 * The absolute bulk get / put of ByteBuffer (Java 13), which Android has only from version 15:
 * GraphHopper's memory-mapped storage (MMapDataAccess) uses them to read and write names and road
 * shapes. At build time its calls are redirected here (app/build.gradle.kts, NioCompatFactory),
 * so the graph opens on every tablet. Same result, through a duplicate of the buffer (its own
 * position, the original is not touched).
 */
public final class NioCompat {
  private NioCompat() {}

  public static ByteBuffer get(ByteBuffer b, int index, byte[] dst, int offset, int length) {
    ByteBuffer d = b.duplicate();
    ((Buffer) d).position(index);
    d.get(dst, offset, length);
    return b;
  }

  public static ByteBuffer put(ByteBuffer b, int index, byte[] src, int offset, int length) {
    ByteBuffer d = b.duplicate();
    ((Buffer) d).position(index);
    d.put(src, offset, length);
    return b;
  }
}
