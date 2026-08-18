package io.tebex.model;

import com.google.gson.annotations.SerializedName;

/**
 * A community goal, as returned by {@code GET /community_goals} and
 * {@code GET /community_goals/{id}} (TBX_021).
 *
 * <p>Timestamps are exposed as the raw strings the API sends rather than as
 * {@code java.time} values. Parsing them here would mean registering a Gson type
 * adapter and picking a timezone policy on the caller's behalf; the strings are
 * unambiguous and a caller that needs a date can parse one.
 */
public final class CommunityGoal {

    @SerializedName("id")
    private int id;

    @SerializedName("created_at")
    private String createdAt;

    @SerializedName("updated_at")
    private String updatedAt;

    @SerializedName("account")
    private int accountId;

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("image")
    private String image;

    @SerializedName("target")
    private double target;

    @SerializedName("current")
    private double current;

    // The API sends 0/1 here, not a JSON boolean, so this must be an int: Gson's
    // boolean adapter rejects a number outright.
    @SerializedName("repeatable")
    private int repeatable;

    @SerializedName("last_achieved")
    private String lastAchieved;

    @SerializedName("times_achieved")
    private int timesAchieved;

    @SerializedName("status")
    private Status status;

    @SerializedName("sale")
    private boolean sale;

    /**
     * Returns the community goal id.
     *
     * @return the goal id
     */
    public int getId() {
        return id;
    }

    /**
     * Returns when the goal was created.
     *
     * @return the creation timestamp as sent by the API
     */
    public String getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns when the goal was last modified.
     *
     * @return the update timestamp as sent by the API
     */
    public String getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Returns the store account the goal belongs to.
     *
     * @return the account id
     */
    public int getAccountId() {
        return accountId;
    }

    /**
     * Returns the goal name.
     *
     * @return the goal name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the goal description.
     *
     * @return the goal description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Returns the goal image URL, normalising the API's empty string to
     * {@code null} as the old SDK did.
     *
     * @return the image URL, or {@code null} if none is set
     */
    public String getImage() {
        return image == null || image.isEmpty() ? null : image;
    }

    /**
     * Returns the amount that must be raised for the goal to complete.
     *
     * @return the goal target
     */
    public double getTarget() {
        return target;
    }

    /**
     * Returns how much has been raised so far.
     *
     * @return the current amount
     */
    public double getCurrent() {
        return current;
    }

    /**
     * Returns whether the goal restarts after being achieved.
     *
     * @return {@code true} if the goal is repeatable
     */
    public boolean isRepeatable() {
        return repeatable != 0;
    }

    /**
     * Returns when the goal was last achieved.
     *
     * @return the timestamp as sent by the API, or {@code null} if never achieved
     */
    public String getLastAchieved() {
        return lastAchieved;
    }

    /**
     * Returns how many times the goal has been achieved.
     *
     * @return the achievement count
     */
    public int getTimesAchieved() {
        return timesAchieved;
    }

    /**
     * Returns the goal's current status.
     *
     * @return the status, or {@code null} if the API sent an unrecognised value
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Returns whether the goal is a sale goal.
     *
     * @return {@code true} if this is a sale goal
     */
    public boolean isSale() {
        return sale;
    }

    /**
     * Returns progress toward the target as a fraction between 0 and 1,
     * saturating at 1 for an over-achieved goal.
     *
     * <p>Returns {@code 0} when the target is zero or negative rather than
     * dividing by it: a malformed goal must not produce {@code NaN} or
     * {@code Infinity} in a caller's progress bar.
     *
     * @return the completion fraction, in the range {@code [0, 1]}
     */
    public double getProgress() {
        if (target <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, current / target);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return "CommunityGoal{id=" + id + ", name='" + name + "', current=" + current
                + "/" + target + ", status=" + status + '}';
    }

    /**
     * The lifecycle state of a community goal.
     *
     * <p>The API sends these lowercase. Each constant declares both casings as
     * accepted wire forms because the old SDK upper-cased the raw string before
     * {@code valueOf}, so it tolerated either; matching only one casing here
     * would silently yield a {@code null} status if the API's casing ever
     * changed.
     */
    public enum Status {

        /** The goal is currently accepting contributions. */
        @SerializedName(value = "active", alternate = {"ACTIVE", "Active"})
        ACTIVE,

        /** The goal has reached its target. */
        @SerializedName(value = "completed", alternate = {"COMPLETED", "Completed"})
        COMPLETED,

        /** The goal has been turned off by the store owner. */
        @SerializedName(value = "disabled", alternate = {"DISABLED", "Disabled"})
        DISABLED
    }
}
