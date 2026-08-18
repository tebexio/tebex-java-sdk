package io.tebex.requirements;

/**
 * A single entry in the requirements registry: a stable id and the behaviour it
 * describes. Tests trace to requirements by id.
 */
public class Required {
    private final String id;
    private final String description;

    /**
     * Creates a requirement.
     *
     * @param id          the stable requirement id, for example {@code "TBX_002"}
     * @param description the behaviour the requirement describes
     */
    public Required(String id, String description) {
        this.id = id;
        this.description = description;
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
}
