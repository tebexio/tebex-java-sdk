package io.tebex.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.tebex.requirements.Requirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Requirement tests for TBX_020: the SDK provides an HTML-stripped form of a
 * product description, because a store writes markup and a game server can only
 * print text.
 *
 * <p>The cases are drawn from what storefront descriptions actually contain —
 * paragraphs, line breaks, bullet lists, entities, and the odd inline style
 * block — rather than from the implementation's structure.
 */
class HtmlTest {

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: tags are removed and the words survive")
    void tagsAreStripped() {
        String description = "<p>Get <strong>VIP</strong> access to <em>every</em> server.</p>";

        assertEquals("Get VIP access to every server.", Html.strip(description));
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: block breaks become spaces so words do not run together")
    void blockBreaksBecomeSpaces() {
        // Without this, "rank" and "Includes" would be printed as "rankIncludes".
        String description = "<p>The VIP rank</p><p>Includes a kit</p>";

        assertEquals("The VIP rank Includes a kit", Html.strip(description));
        assertEquals("Line one Line two", Html.strip("Line one<br>Line two"));
        assertEquals("Fly Chat colours", Html.strip("<ul><li>Fly</li><li>Chat colours</li></ul>"));
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: character entities are decoded")
    void entitiesAreDecoded() {
        assertEquals("Tom & Jerry's \"best\" rank",
                Html.strip("Tom &amp; Jerry&#39;s &quot;best&quot; rank"));
        assertEquals("Costs < 5 USD", Html.strip("Costs &lt; 5 USD"));
        // A doubly-escaped entity is decoded once, which is what a browser would
        // render: the store wrote the visible text "&lt;b&gt;", not a bold tag.
        // Decoding twice would turn a description that talks about markup into
        // markup.
        assertEquals("&lt;b&gt;", Html.strip("&amp;lt;b&amp;gt;"));
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: whitespace is collapsed to something printable on one line")
    void whitespaceIsCollapsed() {
        assertEquals("Rank details here",
                Html.strip("  <p>Rank\n\n   details</p>\t<p>here</p>  "));
        assertEquals("A B", Html.strip("A&nbsp;&nbsp;B"));
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: markup that is not prose is dropped rather than printed")
    void styleAndScriptContentIsDropped() {
        String description = "<style>.vip { color: red; }</style><p>VIP</p>"
                + "<script>alert('hi')</script>";

        String stripped = Html.strip(description);

        assertEquals("VIP", stripped);
        assertFalse(stripped.contains("color"), "stylesheet source is not a description: " + stripped);
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: a missing description is not an exception")
    void nullAndEmptyAreHandled() {
        assertNull(Html.strip(null), "a package with no description must not blow up a caller");
        assertEquals("", Html.strip(""));
        assertEquals("", Html.strip("<p></p>"));
    }

    @Test
    @Requirement("TBX_020")
    @DisplayName("TBX_020: plain text is returned unchanged")
    void plainTextIsUntouched() {
        assertEquals("Just a rank.", Html.strip("Just a rank."));
    }
}
