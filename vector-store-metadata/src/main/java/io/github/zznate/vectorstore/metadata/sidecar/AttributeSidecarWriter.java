package io.github.zznate.vectorstore.metadata.sidecar;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Serialises per-ordinal attribute maps into the {@code attributes.jsonl}
 * wire format one ordinal at a time:
 *
 * <pre>
 * {"ordinal":0,"attributes":{"category":"electronics","region":"us-west"}}
 * {"ordinal":1,"attributes":{"category":"books"}}
 * </pre>
 *
 * <p>Ordinals with no attributes are written as {@code "attributes":{}} so
 * the line-count equals the vector count; the reader's "empty map" path is
 * the same as "no line". Keys and values are plain strings; typed coercion
 * is future work.
 */
public final class AttributeSidecarWriter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AttributeSidecarWriter() {}

  /**
   * Write the JSONL sidecar into the given path. The file is created
   * afresh; existing content is overwritten. Ordinals are implicit in the
   * list position.
   */
  public static void write(Path target, List<Map<String, String>> byOrdinal) throws IOException {
    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
      write(out, byOrdinal);
    }
  }

  /**
   * Write the JSONL sidecar to the given stream. Caller owns the stream
   * lifecycle.
   */
  public static void write(OutputStream out, List<Map<String, String>> byOrdinal)
      throws IOException {
    for (int ordinal = 0; ordinal < byOrdinal.size(); ordinal++) {
      Map<String, String> attrs = byOrdinal.get(ordinal);
      if (attrs == null) {
        attrs = Map.of();
      }
      Line line = new Line();
      line.ordinal = ordinal;
      line.attributes = attrs;
      // writeValueAsBytes avoids Jackson's AUTO_CLOSE_TARGET closing the
      // caller-owned stream on every record.
      out.write(MAPPER.writeValueAsBytes(line));
      out.write('\n');
    }
    out.flush();
  }

  /** Wire representation. Same shape as {@link AttributeSidecar.Line}. */
  public static final class Line {
    public int ordinal;
    public Map<String, String> attributes;
  }
}
