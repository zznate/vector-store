package io.github.zznate.vectorstore.datagen.wikipedia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Fetches a Wikipedia article's current (or pinned) revision as plain text
 * through the {@code action=parse} API. Uses JSoup to strip the rendered
 * HTML down to readable paragraphs — infoboxes, references, navboxes, and
 * footers are discarded.
 *
 * <p>The API returns the server-side {@code revid} in the response; callers
 * record that on the returned {@link WikipediaArticle} so the fixture
 * README can document the exact revision used.
 */
public final class WikipediaFetcher {

  private static final String API =
      "https://en.wikipedia.org/w/api.php?action=parse&format=json&prop=text%7Crevid&redirects=1&page=";

  private static final ObjectMapper JSON = new ObjectMapper();

  private final HttpClient http;

  public WikipediaFetcher() {
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** Fetch an article by its URL-safe title (spaces as underscores). */
  public WikipediaArticle fetch(String slug, String title)
      throws IOException, InterruptedException {
    URI uri = URI.create(API + encode(title));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header(
                "User-Agent",
                "vector-store-datagen/0.1 (https://github.com/zznate/vector-store)")
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException(
          "Wikipedia API returned " + response.statusCode() + " for " + title);
    }

    JsonNode root = JSON.readTree(response.body()).path("parse");
    if (root.isMissingNode()) {
      throw new IOException("no 'parse' node in response for " + title);
    }
    String canonicalTitle = root.path("title").asText(title);
    long oldid = root.path("revid").asLong();
    String html = root.path("text").path("*").asText();
    if (html.isEmpty()) {
      throw new IOException("no HTML body for " + title);
    }
    return new WikipediaArticle(slug, canonicalTitle, oldid, toPlainText(html));
  }

  private static String encode(String title) {
    return java.net.URLEncoder.encode(title, java.nio.charset.StandardCharsets.UTF_8);
  }

  private static String toPlainText(String html) {
    Document doc = Jsoup.parse(html);
    // Drop elements that never carry body prose.
    doc.select(
            "table, .navbox, .infobox, .sidebar, .thumb, .reference, .mw-editsection,"
                + " .reflist, .references, .hatnote, .noprint, .metadata, .mbox-small,"
                + " .mw-empty-elt, sup.reference, style, script")
        .remove();
    StringBuilder out = new StringBuilder();
    for (Element p : doc.select("p, h1, h2, h3, h4, li")) {
      String text = p.text().trim();
      if (text.isEmpty()) {
        continue;
      }
      // Skip boilerplate trailing section headings.
      if (text.equalsIgnoreCase("See also")
          || text.equalsIgnoreCase("References")
          || text.equalsIgnoreCase("External links")
          || text.equalsIgnoreCase("Further reading")
          || text.equalsIgnoreCase("Notes")) {
        break;
      }
      out.append(text).append("\n\n");
    }
    return out.toString().trim();
  }
}
