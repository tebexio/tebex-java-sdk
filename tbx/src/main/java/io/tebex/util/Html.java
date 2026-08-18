package io.tebex.util;

/**
 * Turns the HTML a store writes into text a game server can print (TBX_020).
 *
 * <p>Package descriptions come out of the Tebex storefront as HTML, because that
 * is what a webstore renders. A chat line, a sign or a console reply cannot show
 * markup, so the SDK provides the stripped form rather than leaving every
 * integration to write the same regular expression.
 *
 * <p>This is a text cleaner, not a sanitiser: it exists to make descriptions
 * readable, and nothing here should be relied on to make untrusted HTML safe to
 * re-render somewhere else.
 */
public final class Html {

    /** Not instantiable: this is a holder for static text helpers. */
    private Html() {
    }

    /**
     * Returns the plain-text form of an HTML fragment.
     *
     * <p>Block-level breaks ({@code <br>}, {@code </p>}, {@code </div>},
     * {@code </li>}) become spaces rather than vanishing, so words either side of
     * them do not run together; the remaining tags are removed, the common
     * character entities are decoded, and runs of whitespace are collapsed.
     *
     * @param html the description as the store holds it, may be {@code null}
     * @return the stripped text, or {@code null} if {@code html} was {@code null}
     */
    public static String strip(String html) {
        if (html == null) {
            return null;
        }

        String text = html;
        // Whole elements whose *content* is markup rather than prose: dropping
        // only their tags would leave stylesheet or script source in the output.
        text = text.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("(?i)<br\\s*/?>", " ");
        text = text.replaceAll("(?i)</(p|div|li|tr|h[1-6])\\s*>", " ");
        text = text.replaceAll("(?s)<[^>]*>", "");
        text = decodeEntities(text);
        // Collapse after decoding, so an entity that decodes to a space does not
        // survive as a double space.
        text = text.replaceAll("\\s+", " ");
        return text.trim();
    }

    /**
     * Decodes the character entities a storefront description realistically
     * contains.
     *
     * <p>{@code &amp;} is decoded last so that a literal {@code &amp;lt;} in the
     * source reads as the text {@code &lt;} rather than being decoded twice into
     * a {@code <}.
     *
     * @param text the text to decode
     * @return the decoded text
     */
    private static String decodeEntities(String text) {
        String decoded = text;
        decoded = decoded.replace("&nbsp;", " ");
        decoded = decoded.replace("&lt;", "<");
        decoded = decoded.replace("&gt;", ">");
        decoded = decoded.replace("&quot;", "\"");
        decoded = decoded.replace("&#39;", "'");
        decoded = decoded.replace("&apos;", "'");
        decoded = decoded.replace("&amp;", "&");
        return decoded;
    }
}
