package io.tebex.requirements;

/**
 * A single entry in the requirements registry: a stable id and the behaviour it
 * describes. Tests trace to requirements by id.
 *
 * <p>A requirement may be <em>deferred</em>, meaning the project has decided not
 * to build it yet and has recorded why. A deferred requirement keeps its id and
 * its text — it is still part of the specification — but the traceability gate
 * does not demand a covering test for it. The reason is mandatory: that is what
 * stops deferral being a silent way to make a gap disappear.
 */
public class Required {
    private final String id;
    private final String description;
    private final String deferralReason;

    /**
     * Creates an active requirement, one the build expects a test for.
     *
     * @param id          the stable requirement id, for example {@code "TBX_002"}
     * @param description the behaviour the requirement describes
     */
    public Required(String id, String description) {
        this(id, description, null);
    }

    /**
     * Creates a requirement, optionally deferred.
     *
     * @param id             the stable requirement id, for example {@code "TBX_002"}
     * @param description    the behaviour the requirement describes
     * @param deferralReason why the requirement is not being built yet, or
     *                       {@code null} if it is active
     */
    public Required(String id, String description, String deferralReason) {
        this.id = id;
        this.description = description;
        this.deferralReason = deferralReason;
    }

    /**
     * Returns the requirement id.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the requirement description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns whether this requirement has been deliberately deferred.
     *
     * @return {@code true} if no covering test is expected yet
     */
    public boolean isDeferred() {
        return deferralReason != null;
    }

    /**
     * Returns why this requirement is deferred.
     *
     * @return the recorded reason, or {@code null} if the requirement is active
     */
    public String getDeferralReason() {
        return deferralReason;
    }
}
