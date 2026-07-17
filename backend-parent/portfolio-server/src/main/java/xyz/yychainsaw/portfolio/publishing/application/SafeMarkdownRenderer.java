package xyz.yychainsaw.portfolio.publishing.application;

import java.util.Objects;
import java.util.Set;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

public final class SafeMarkdownRenderer {
    private static final Set<String> SAFE_SCHEMES = Set.of("http", "https", "mailto");

    private final Parser parser = Parser.builder().build();
    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    public String render(String markdown) {
        Node document = parser.parse(Objects.requireNonNull(markdown, "markdown"));
        document.accept(new UnsafeDestinationStripper());
        return renderer.render(document);
    }

    private static boolean safeDestination(String destination) {
        if (destination == null || destination.isEmpty()) {
            return true;
        }
        String decoded = decodeAsciiPercentEscapes(destination);
        if (decoded == null || decoded.chars().anyMatch(SafeMarkdownRenderer::isAsciiControlOrSpace)) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0 || delimiterBefore(decoded, colon)) {
            return true;
        }
        String scheme = decoded.substring(0, colon);
        if (!scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            return false;
        }
        return SAFE_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean delimiterBefore(String value, int colon) {
        for (int index = 0; index < colon; index++) {
            char current = value.charAt(index);
            if (current == '/' || current == '?' || current == '#') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiControlOrSpace(int value) {
        return value <= 0x20 || value == 0x7f;
    }

    private static String decodeAsciiPercentEscapes(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current != '%') {
                decoded.append(current);
                continue;
            }
            if (index + 2 >= value.length()) {
                return null;
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            decoded.append((char) ((high << 4) | low));
            index += 2;
        }
        return decoded.toString();
    }

    private static void unwrap(Node node) {
        Node child = node.getFirstChild();
        while (child != null) {
            Node next = child.getNext();
            child.unlink();
            node.insertBefore(child);
            child = next;
        }
        node.unlink();
    }

    private static final class UnsafeDestinationStripper extends AbstractVisitor {
        @Override
        public void visit(Link link) {
            if (safeDestination(link.getDestination())) {
                visitChildren(link);
            } else {
                unwrap(link);
            }
        }

        @Override
        public void visit(Image image) {
            if (safeDestination(image.getDestination())) {
                visitChildren(image);
            } else {
                unwrap(image);
            }
        }
    }
}
