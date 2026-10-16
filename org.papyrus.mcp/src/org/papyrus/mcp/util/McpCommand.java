package org.papyrus.mcp.util;

import com.google.gson.JsonObject;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.emf.transaction.RecordingCommand;
import org.eclipse.emf.transaction.TransactionalEditingDomain;

/**
 * The single EMF command every MCP write becomes. It is a RecordingCommand: everything done in
 * doExecute (plain UML2 API calls AND nested Papyrus edit-service ICommands) is recorded as one
 * ChangeDescription, so one Ctrl+Z in Papyrus reverts the whole tool call / batch.
 * On failure we remember the error and throw OperationCanceledException so the transaction is
 * rolled back and nothing reaches the undo history.
 * sessionId lets undo/redo restrict themselves to the caller's own edits.
 */
public class McpCommand extends RecordingCommand {
    public interface Body { JsonObject run() throws Exception; }

    public final String sessionId;
    private final Body body;
    private JsonObject result;
    private Exception error;

    public McpCommand(TransactionalEditingDomain d, String label, String sessionId, Body body) {
        super(d, label);
        this.sessionId = sessionId;
        this.body = body;
    }

    @Override
    protected void doExecute() {
        try { result = body.run(); }
        catch (Exception e) { error = e; throw new OperationCanceledException(String.valueOf(e.getMessage())); }
    }

    public JsonObject result() { return result; }
    public Exception error() { return error; }
}
