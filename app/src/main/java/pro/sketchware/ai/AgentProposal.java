package pro.sketchware.ai;

import com.google.gson.JsonObject;

/**
 * One mutation the model has proposed and the app has validated —
 * validated, but <em>not executed</em>. Execution only happens after
 * the user approves the card built from {@link #title}, {@link #body}
 * and {@link #details}; those strings are built by app code in plain
 * words, never by the model.
 */
public class AgentProposal {

    /** The tool that proposed this, e.g. {@code create_activity}. */
    public final String tool;
    /** The validated arguments the executor will run. */
    public final JsonObject args;
    /** The project the mutation applies to ("" for a new project). */
    public final String workingProjectId;
    /** Card headline, e.g. "Add a screen to Sporty Bet". */
    public final String title;
    /** Card summary in plain words: what, where. */
    public final String body;
    /** Collapsed card details: the exact effect, still in plain words. */
    public final String details;

    /** The transcript card showing this proposal (set by the UI). */
    public transient ChatMessage card;

    AgentProposal(String tool, JsonObject args, String workingProjectId,
                  String title, String body, String details) {
        this.tool = tool;
        this.args = args;
        this.workingProjectId = workingProjectId;
        this.title = title;
        this.body = body;
        this.details = details;
    }
}
