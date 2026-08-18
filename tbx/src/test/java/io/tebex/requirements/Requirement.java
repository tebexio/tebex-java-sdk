package io.tebex.requirements;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Tags a test with the id of the requirement it verifies.
 *
 * <p>Every test in the requirements suite must carry at least one
 * {@code @Requirement}, and every id used must exist in
 * {@link io.tebex.requirements.Requirements}. This is what makes the suite
 * traceable: the {@code TraceabilityTest} scans for these tags to prove that
 * each requirement is covered by a test and that no test covers a requirement
 * that does not exist.
 *
 * <p>The annotation is {@link Repeatable} so a single test may legitimately
 * cover more than one requirement.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Repeatable(Requirement.Covers.class)
public @interface Requirement {
    /**
     * The requirement id this test covers, for example {@code "CODE_001"}.
     *
     * @return the covered requirement id
     */
    String value();

    /**
     * Container annotation that holds repeated {@link Requirement} tags.
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @interface Covers {
        /**
         * The repeated requirement tags.
         *
         * @return the requirements covered by the annotated element
         */
        Requirement[] value();
    }
}
