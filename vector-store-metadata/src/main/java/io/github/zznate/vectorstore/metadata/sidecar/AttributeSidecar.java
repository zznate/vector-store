package io.github.zznate.vectorstore.metadata.sidecar;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed, read-only view of one segment's {@code attributes.jsonl}. Each
 * line of the source file is one {@code {"ordinal":N,"attributes":{...}}}
 * record; the sidecar stores those attributes in a dense array indexed by
 * ordinal so lookups are O(1).
 *
 * <p>A missing ordinal (no line in the file, or line with an empty
 * {@code attributes} object) returns an empty map; callers never see
 * {@code null}. The heap-size approximation used for cache weighting is
 * computed once at parse time.
 */
public final class AttributeSidecar implements OrdinalAttributes, CachedSidecar {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Line> LINE_TYPE = new TypeReference<>() {};

  private final List<Map<String, String>> byOrdinal;
  private final long sizeBytes;

  AttributeSidecar(List<Map<String, String>> byOrdinal, long sizeBytes) {
    this.byOrdinal = byOrdinal;
    this.sizeBytes = sizeBytes;
  }

  public static AttributeSidecar parse(InputStream in) throws IOException {
    List<int[]> byteCounts = new ArrayList<>();
    List<int[]> ordinals = new ArrayList<>();
    List<Map<String, String>> parsedLines = new ArrayList<>();
    int maxOrdinal = -1;
    long approxBytes = 0;

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isBlank()) {
          continue;
        }
        Line parsed = MAPPER.readValue(line, LINE_TYPE);
        Map<String, String> attrs =
            parsed.attributes == null ? Map.of() : new HashMap<>(parsed.attributes);
        parsedLines.add(attrs);
        ordinals.add(new int[] {parsed.ordinal});
        byteCounts.add(new int[] {line.length()});
        approxBytes += line.length();
        if (parsed.ordinal > maxOrdinal) {
          maxOrdinal = parsed.ordinal;
        }
      }
    }

    List<Map<String, String>> byOrdinal = new ArrayList<>(maxOrdinal + 1);
    for (int i = 0; i <= maxOrdinal; i++) {
      byOrdinal.add(Map.of());
    }
    for (int i = 0; i < parsedLines.size(); i++) {
      byOrdinal.set(ordinals.get(i)[0], parsedLines.get(i));
    }
    return new AttributeSidecar(Collections.unmodifiableList(byOrdinal), approxBytes);
  }

  /**
   * Build a sidecar directly from an in-memory {@code ordinal -> attrs}
   * list without round-tripping through JSONL. Intended for the write path
   * (segment-build time) so the sidecar can be cached immediately without
   * re-parsing what we just wrote.
   */
  public static AttributeSidecar of(List<Map<String, String>> byOrdinal) {
    List<Map<String, String>> copy = new ArrayList<>(byOrdinal.size());
    long bytes = 0;
    for (Map<String, String> attrs : byOrdinal) {
      Map<String, String> frozen = attrs == null ? Map.of() : Map.copyOf(attrs);
      copy.add(frozen);
      for (Map.Entry<String, String> e : frozen.entrySet()) {
        bytes += (long) e.getKey().length() + (long) e.getValue().length() + 8;
      }
    }
    return new AttributeSidecar(Collections.unmodifiableList(copy), bytes);
  }

  @Override
  public int size() {
    return byOrdinal.size();
  }

  @Override
  public Map<String, String> attributesOf(int ordinal) {
    return byOrdinal.get(ordinal);
  }

  @Override
  public long sizeBytes() {
    return sizeBytes;
  }

  /** Accessor used by tests that need to assert on the parsed structure. */
  public List<Map<String, String>> asList() {
    return byOrdinal;
  }

  static UncheckedIOException wrap(IOException e, String segmentId) {
    return new UncheckedIOException("failed to parse attributes.jsonl for segment " + segmentId, e);
  }

  /** Wire format. Package-private so only the parser sees it. */
  static final class Line {
    public int ordinal;
    public Map<String, String> attributes;
  }
}
